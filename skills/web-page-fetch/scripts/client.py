#!/usr/bin/env python3
"""Read an approved public page through the QY serp-moe reader proxy."""
import argparse
import ipaddress
import json
import os
import re
import socket
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit

MAX_BYTES = 2 * 1024 * 1024
DEFAULT_HOSTS = ["gov.cn"]
DEFAULT_TEST_BASE_URL = "https://publicapi.chatglm.cn/chatglm_public/skill"
READER_PREFIX = "/legal/auto_search/api/glm/r/"

class ReaderError(Exception):
    def __init__(self, reason, status="error"):
        super().__init__(reason)
        self.status = status

def allowed_hosts(extra):
    hosts = list(DEFAULT_HOSTS)
    for value in extra:
        value = value.lower().rstrip(".")
        if not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", value) or "." not in value or ".." in value:
            raise ReaderError("invalid_allow_host", "blocked")
        hosts.append(value)
    return hosts

def validate_url(url, hosts, dns=False):
    if not isinstance(url, str) or not url or len(url) > 4096 or any(ord(c) < 33 for c in url) or "\\" in url:
        raise ReaderError("invalid_url", "blocked")
    try:
        p = urlsplit(url)
        host = (p.hostname or "").encode("idna").decode("ascii").lower().rstrip(".")
        if p.scheme not in ("http", "https") or not host or p.username is not None or p.password is not None or p.port not in (None, 80, 443):
            raise ValueError()
    except (ValueError, UnicodeError):
        raise ReaderError("unsafe_url", "blocked")
    # This reader handles public web pages, not uploaded files or object storage.
    # File storage cannot be enabled through --allow-host or a parent domain.
    if host == "pfiles.chatglm.cn" or host.endswith(".pfiles.chatglm.cn"):
        raise ReaderError("file_storage_url_not_supported", "blocked")
    file_path = unquote(p.path).lower().rstrip("/")
    if file_path.endswith((
        ".md", ".markdown", ".txt", ".rtf", ".pdf",
        ".doc", ".docx", ".xls", ".xlsx", ".csv",
        ".ppt", ".pptx", ".zip", ".rar", ".7z",
    )):
        raise ReaderError("file_url_not_supported", "blocked")
    if host == "localhost" or host.endswith((".localhost", ".local", ".internal")):
        raise ReaderError("non_public_host", "blocked")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        address = None
    if address is not None and not address.is_global:
        raise ReaderError("non_public_ip", "blocked")
    if not any(host == h or host.endswith("." + h) for h in hosts):
        raise ReaderError("host_not_allowed", "blocked")
    if dns:
        try:
            records = socket.getaddrinfo(host, p.port or (443 if p.scheme == "https" else 80), type=socket.SOCK_STREAM)
        except OSError:
            raise ReaderError("dns_unavailable", "unavailable")
        if not records or any(not ipaddress.ip_address(r[4][0]).is_global for r in records):
            raise ReaderError("dns_non_public_ip", "blocked")
    return url

class TextExtractor(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts, self.suppressed = [], []
    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style", "noscript", "template"):
            self.suppressed.append(tag)
        if not self.suppressed and tag in ("p", "div", "br", "li", "tr", "article", "h1", "h2", "h3"):
            self.parts.append("\n")
    def handle_endtag(self, tag):
        if self.suppressed:
            if tag == self.suppressed[-1]:
                self.suppressed.pop()
            return
        if tag in ("p", "div", "li", "tr", "article", "h1", "h2", "h3"):
            self.parts.append("\n")
    def handle_data(self, data):
        if not self.suppressed:
            self.parts.append(data)

def clean_html(html):
    parser = TextExtractor()
    parser.feed(html)
    return "\n".join(line for line in (re.sub(r"[ \t\r\f\v]+", " ", x).strip() for x in "".join(parser.parts).split("\n")) if line)

def unwrap_proxy(raw, url):
    """Unwrap the QY status/message/result/rid envelope when present."""
    if not isinstance(raw, dict):
        raise ReaderError("invalid_response")
    if raw.get("status") == 0 and "result" in raw:
        result, rid = raw.get("result"), str(raw.get("rid") or "")
        if isinstance(result, str):
            return {"data": {"url": url, "text": result}, "rid": rid}
        if isinstance(result, dict):
            if any(key in result for key in ("code", "status", "data")):
                result = dict(result)
                result.setdefault("rid", rid)
                return result
            return {"data": result, "rid": rid}
        raise ReaderError("empty_proxy_result")
    if isinstance(raw.get("status"), int) and raw.get("status") not in (0, 200):
        status = "blocked" if raw.get("status") in (401, 403, 40001) else "unavailable"
        raise ReaderError("proxy_error:" + str(raw.get("status")), status)
    return raw

def normalize(raw, url, hosts, max_chars=50000, live=False):
    validate_url(url, hosts)
    raw = unwrap_proxy(raw, url)
    for code in (raw.get("code"), raw.get("status")):
        if code is not None and code != 200:
            raise ReaderError("reader_non_success", "blocked" if code in (401, 403) else "unavailable" if code == 429 else "error")
    data = raw.get("data", raw)
    if not isinstance(data, dict):
        raise ReaderError("unknown_envelope")
    page_status = data.get("httpStatus", data.get("http_status"))
    if page_status is not None and (type(page_status) is not int or not 200 <= page_status < 300):
        raise ReaderError("page_non_success", "blocked" if page_status in (401, 403) else "unavailable" if page_status == 429 else "error")
    final_url = data.get("url")
    if not final_url:
        raise ReaderError("missing_final_url")
    validate_url(final_url, hosts, dns=live)
    html, text = data.get("html"), data.get("text")
    if isinstance(html, str) and html.strip():
        text = clean_html(html)
    if not isinstance(text, str) or not text.strip():
        raise ReaderError("empty_body")
    text = text.strip()
    title = data.get("title") if isinstance(data.get("title"), str) else ""
    marker = r"404|403|page not found|access denied|找不到页面|页面不存在|页面未找到|访问被拒绝"
    if re.search(marker, title, re.I) or (len(text) < 300 and re.search(marker, text, re.I)):
        raise ReaderError("error_page_body")
    warnings = ["redirect_chain_safety_not_verified", "body_requires_source_and_completeness_review"]
    if not title:
        warnings.append("missing_title")
    if page_status is None:
        warnings.append("page_http_status_unknown")
    upstream_time = data.get("publishedTime", data.get("publish_time"))
    if upstream_time is not None:
        warnings.append("upstream_time_not_verified_as_publication_time")
    cut = len(text) > max_chars or data.get("truncated") is True
    if cut:
        warnings.append("body_truncated")
    meta = raw.get("meta") if isinstance(raw.get("meta"), dict) else {}
    usage = meta.get("usage") or data.get("usage")
    tokens = usage.get("tokens") if isinstance(usage, dict) else None
    return {"status": "partial" if cut else "ok", "requested_url": url, "final_url": final_url,
            "http_status": page_status, "content_type": data.get("content_type"), "title": title,
            "publisher": None, "published_at": None, "upstream_time": upstream_time, "time_semantics": "unknown",
            "text": text[:max_chars], "truncated": cut, "retrieved_at": datetime.now(timezone.utc).isoformat(),
            "warnings": warnings, "trace_id": str(raw.get("rid") or raw.get("request_id") or ""),
            "token_usage": tokens, "execution_mode": "live" if live else "saved_response"}

def read_json(path, limit):
    if not path.exists() or path.stat().st_size > limit:
        raise ReaderError("output_missing_or_too_large")
    try:
        return json.loads(path.read_text("utf-8"))
    except (ValueError, UnicodeError):
        raise ReaderError("invalid_json_output")

def build_reader_url(base_url, url):
    return base_url.rstrip("/") + READER_PREFIX + url

def invoke(url, hosts, timeout, limit, base_url):
    validate_url(url, hosts, dns=True)
    request = urllib.request.Request(
        build_reader_url(base_url, url), headers={"Accept": "application/json"}, method="GET"
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read(limit + 1)
    except urllib.error.HTTPError as error:
        raise ReaderError("http_error:" + str(error.code), "blocked" if error.code in (401, 403) else "unavailable")
    except (urllib.error.URLError, socket.timeout, TimeoutError):
        raise ReaderError("network_or_timeout", "unavailable")
    if len(body) > limit:
        raise ReaderError("output_too_large")
    try:
        return json.loads(body.decode("utf-8"))
    except (ValueError, UnicodeError):
        raise ReaderError("invalid_json_output")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--allow-host", action="append", default=[])
    parser.add_argument("--timeout-seconds", type=int, default=60)
    parser.add_argument("--max-bytes", type=int, default=MAX_BYTES)
    parser.add_argument("--max-chars", type=int, default=50000)
    parser.add_argument("--base-url", default=os.getenv("LEGAL_PROXY_BASE_URL", DEFAULT_TEST_BASE_URL))
    parser.add_argument("--response-file")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        if not (1 <= args.timeout_seconds <= 60 and 1 <= args.max_bytes <= MAX_BYTES and 1 <= args.max_chars <= 100000):
            raise ReaderError("invalid_local_limits")
        hosts = allowed_hosts(args.allow_host)
        validate_url(args.url, hosts)
        if args.dry_run:
            result = {"status": "planned", "execution_mode": "dry_run", "method": "GET", "url": build_reader_url(args.base_url, args.url), "allowed_hosts": hosts, "network_called": False}
        else:
            raw = read_json(Path(args.response_file), args.max_bytes) if args.response_file else invoke(args.url, hosts, args.timeout_seconds, args.max_bytes, args.base_url)
            result = normalize(raw, args.url, hosts, args.max_chars, live=not bool(args.response_file))
    except (ReaderError, OSError) as error:
        result = {"status": error.status if isinstance(error, ReaderError) else "error", "text": "", "warnings": [str(error) if isinstance(error, ReaderError) else "local_execution_error"], "trace_id": ""}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] in ("ok", "partial", "planned") else 2

if __name__ == "__main__":
    sys.exit(main())
