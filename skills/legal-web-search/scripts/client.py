#!/usr/bin/env python3
"""Call QY web search and normalize its current reader-text response."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


ENDPOINT = "/legal/auto_search/api/glm/s/"
DEFAULT_TEST_BASE_URL = "https://publicapi.chatglm.cn/chatglm_public/skill"
FIELD_RE = re.compile(r"^\[(\d+)]\s+(Title|URL Source|Description|Date|Content):\s*(.*)$")


class ContractError(ValueError):
    pass


def build_query(payload: dict[str, Any]) -> tuple[dict[str, str], list[str]]:
    query = payload.get("query")
    if not isinstance(query, str) or not query.strip():
        raise ContractError("query is required")
    params = {"q": query.strip()}
    warnings: list[str] = []
    count = int(payload.get("count", 10))
    if count < 1 or count > 50:
        raise ContractError("count must be between 1 and 50")
    if payload.get("fetch_content") is True:
        params["fetch_content"] = "fetch"
        params["max_tokens"] = str(int(payload.get("max_tokens", 500)))
    for field in ("domain_filter", "recency"):
        value = payload.get(field)
        if value not in (None, "", [], "no_limit"):
            warnings.append(f"constraint_not_applied_by_backend:{field}")
    if payload.get("preferred_source_types") not in (None, "", []):
        warnings.append("preference_not_enforced_by_backend:preferred_source_types")
    return params, warnings


def parse_reader_text(text: str, count: int) -> tuple[list[dict[str, Any]], list[str]]:
    records: dict[int, dict[str, Any]] = {}
    warnings: list[str] = []
    current_rank: int | None = None
    current_field: str | None = None
    field_names = {
        "Title": "title",
        "URL Source": "url",
        "Description": "snippet",
        "Date": "published_at",
        "Content": "content",
    }
    for line in text.splitlines():
        match = FIELD_RE.match(line.strip())
        if match:
            current_rank = int(match.group(1))
            current_field = field_names[match.group(2)]
            record = records.setdefault(current_rank, {"rank": current_rank})
            record[current_field] = match.group(3).strip()
        elif current_rank is not None and current_field and line.strip():
            record = records[current_rank]
            record[current_field] = (record.get(current_field, "") + "\n" + line.strip()).strip()
    results = []
    for rank in sorted(records):
        record = records[rank]
        if not record.get("url"):
            warnings.append(f"result_missing_url:{rank}")
            continue
        results.append(
            {
                "rank": rank,
                "title": record.get("title", ""),
                "url": record["url"],
                "snippet": record.get("snippet", ""),
                "content": record.get("content", ""),
                "site_name": "",
                "source_type": "other",
                "published_at": record.get("published_at") or None,
                "is_first_party": None,
            }
        )
        parsed_url = urllib.parse.urlsplit(record["url"])
        if parsed_url.path in ("", "/") and not parsed_url.query and not parsed_url.fragment:
            warnings.append(f"result_url_is_site_root:{rank}")
        if len(results) >= count:
            break
    if text.strip() and not results:
        warnings.append("unrecognized_search_result_format")
    return results, warnings


def normalize(raw: Any, payload: dict[str, Any], warnings: list[str] | None = None) -> dict[str, Any]:
    warnings = list(warnings or [])
    if not isinstance(raw, dict):
        raise ContractError("response is not a JSON object")
    if raw.get("status") != 0:
        raise ContractError(f"platform_error:{raw.get('status')}:{raw.get('message', '')}")
    result = raw.get("result")
    count = max(1, min(int(payload.get("count", 10)), 50))
    if isinstance(result, str):
        results, parse_warnings = parse_reader_text(result, count)
        warnings.extend(parse_warnings)
    elif isinstance(result, dict) and isinstance(result.get("data"), list):
        results = []
        for index, item in enumerate(result["data"][:count], start=1):
            if not isinstance(item, dict) or not item.get("url"):
                continue
            results.append(
                {
                    "rank": item.get("rank", index),
                    "title": item.get("title", ""),
                    "url": item["url"],
                    "snippet": item.get("description") or item.get("snippet") or "",
                    "content": item.get("content") or "",
                    "site_name": item.get("site_name") or "",
                    "source_type": item.get("source_type") or "other",
                    "published_at": item.get("date") or item.get("published_at"),
                    "is_first_party": item.get("is_first_party"),
                }
            )
            parsed_url = urllib.parse.urlsplit(item["url"])
            if parsed_url.path in ("", "/") and not parsed_url.query and not parsed_url.fragment:
                warnings.append(f"result_url_is_site_root:{item.get('rank', index)}")
    else:
        raise ContractError("unsupported result format: expected reader text or result.data[]")
    status = "no_result" if not results and not warnings else ("partial" if warnings else "ok")
    return {
        "status": status,
        "query": payload.get("query") or "",
        "results": results,
        "warnings": warnings,
        "trace_id": str(raw.get("rid") or ""),
    }


def call(base_url: str, params: dict[str, str], timeout: int = 65) -> Any:
    url = base_url.rstrip("/") + ENDPOINT + "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"Accept": "application/json"}, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")[:1000]
        raise ContractError(f"http_error:{exc.code}:{body}") from exc
    except urllib.error.URLError as exc:
        raise ContractError(f"network_error:{exc.reason}") from exc


def parse_input(value: str) -> dict[str, Any]:
    """Parse inline JSON first; use @path or a legacy plain path for files."""
    if value.startswith("@"):
        return json.loads(Path(value[1:]).read_text("utf-8"))
    try:
        return json.loads(value)
    except json.JSONDecodeError as inline_error:
        try:
            input_path = Path(value)
            if input_path.is_file():
                return json.loads(input_path.read_text("utf-8"))
        except OSError:
            pass
        raise inline_error


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="Unified input JSON, @JSON-file path, or a legacy JSON-file path")
    parser.add_argument("--base-url", default=os.getenv("LEGAL_PROXY_BASE_URL", DEFAULT_TEST_BASE_URL))
    parser.add_argument("--response-file", help="Normalize a saved response instead of calling HTTP")
    parser.add_argument("--dry-run", action="store_true", help="Print query parameters only")
    args = parser.parse_args()
    try:
        payload = parse_input(args.input)
        params, warnings = build_query(payload)
        if args.dry_run:
            output = {"path": ENDPOINT, "query": params, "warnings": warnings}
        else:
            if args.response_file:
                raw = json.loads(Path(args.response_file).read_text("utf-8"))
            else:
                if not args.base_url:
                    raise ContractError("base URL is required; pass --base-url or LEGAL_PROXY_BASE_URL")
                raw = call(args.base_url, params)
            output = normalize(raw, payload, warnings)
    except (ContractError, json.JSONDecodeError, OSError) as exc:
        output = {"status": "error", "results": [], "warnings": [str(exc)], "trace_id": ""}
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0 if output.get("status") != "error" else 2


if __name__ == "__main__":
    sys.exit(main())
