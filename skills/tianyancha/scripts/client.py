#!/usr/bin/env python3
"""Two-operation Tianyancha adapter through QY; no supplier credentials in Skill."""
import argparse
import json
import os
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone, timedelta
from pathlib import Path

TEST_BASE_URL = "https://publicapi.chatglm.cn/chatglm_public/skill"
PATHS = {"search_company": "/legal/tyc_search/api/open/search/2.0", "get_basic_info": "/legal/tyc_search/api/open/ic/baseinfoV2/2.0"}

class ClientError(Exception):
    def __init__(self, message, status="error", trace_id=""):
        super().__init__(message)
        self.status, self.trace_id = status, trace_id

def build_request(payload):
    if not isinstance(payload, dict):
        raise ClientError("input_must_be_object")
    op = payload.get("operation")
    if op not in PATHS:
        raise ClientError("unsupported_operation")
    keyword = payload.get("keyword")
    if not isinstance(keyword, str) or not keyword.strip():
        raise ClientError("keyword_required")
    params = {"keyword": keyword.strip()} if op == "get_basic_info" else {"word": keyword.strip()}
    if op == "search_company":
        for source, target, default in (("page_size", "pageSize", 10), ("page_number", "pageNum", 1)):
            value = payload.get(source, default)
            if type(value) is not int or value < 1:
                raise ClientError(source + "_must_be_positive_integer")
            if source == "page_size" and value > 20:
                raise ClientError("page_size_exceeds_20_use_pagination")
            params[target] = value
    return PATHS[op], params

def date_from_millis(value):
    if value is None:
        return None
    if isinstance(value, (int, float)):
        try:
            return datetime.fromtimestamp(value / 1000, timezone(timedelta(hours=8))).date().isoformat()
        except (OverflowError, ValueError, OSError):
            return None
    return str(value)

def normalize(raw, payload):
    if not isinstance(raw, dict):
        raise ClientError("invalid_response_object")
    rid = str(raw.get("rid") or "")
    if raw.get("status") != 0:
        raise ClientError("legal_proxy_error:" + str(raw.get("status")), "unavailable", rid)
    result = raw.get("result")
    # Some upstream failures retain error_code because they contain no result.
    if isinstance(result, dict) and result.get("error_code") not in (None, 0):
        code = result["error_code"]
        if code == 300000:
            return {"status": "no_result", "records": [], "warnings": [], "trace_id": rid}
        raise ClientError("tianyancha_business_error:" + str(code), "unavailable", rid)
    if payload["operation"] == "search_company":
        if not isinstance(result, dict) or not isinstance(result.get("items"), list):
            raise ClientError("unexpected_search_result_not_verified", trace_id=rid)
        items = result["items"]
    else:
        if not isinstance(result, dict) or not result.get("name"):
            raise ClientError("unexpected_basic_info_not_verified", trace_id=rid)
        items = [result]
    records = []
    now = datetime.now(timezone.utc).isoformat()
    for item in items:
        if not isinstance(item, dict) or not item.get("name"):
            raise ClientError("invalid_company_record", trace_id=rid)
        records.append({
            "company_name": item["name"], "company_id": item.get("id"),
            "credit_code": item.get("creditCode"), "legal_representative": item.get("legalPersonName"),
            "registered_capital": item.get("regCapital"), "establishment_date": date_from_millis(item.get("estiblishTime")),
            "registration_status": item.get("regStatus"), "registered_address": item.get("regLocation"),
            "business_scope": item.get("businessScope"), "source_provider": "天眼查",
            "retrieved_at": now, "source_url": None,
        })
    return {"status": "ok" if records else "no_result", "operation": payload["operation"], "records": records,
        "total": result.get("total") if payload["operation"] == "search_company" else None,
        "warnings": [], "trace_id": rid}

def call(base_url, path, params):
    request = urllib.request.Request(base_url.rstrip("/") + path + "?" + urllib.parse.urlencode(params), headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        rid = error.headers.get("X-Request-ID", "")
        try:
            rid = json.loads(error.read().decode("utf-8")).get("rid") or rid
        except (ValueError, AttributeError):
            pass
        raise ClientError("http_error:" + str(error.code), "unavailable" if error.code >= 500 or error.code == 403 else "error", rid)
    except (urllib.error.URLError, socket.timeout, TimeoutError):
        raise ClientError("network_or_timeout", "unavailable")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="JSON object; or @path to a JSON file")
    parser.add_argument("--base-url", default=os.getenv("LEGAL_PROXY_BASE_URL", TEST_BASE_URL))
    parser.add_argument("--response-file")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        payload = json.loads(Path(args.input[1:]).read_text("utf-8") if args.input.startswith("@") else args.input)
        path, params = build_request(payload)
        if args.dry_run:
            output = {"method": "GET", "url": args.base_url.rstrip("/") + path + "?" + urllib.parse.urlencode(params)}
        else:
            raw = json.loads(Path(args.response_file).read_text("utf-8")) if args.response_file else call(args.base_url, path, params)
            output = normalize(raw, payload)
    except ClientError as error:
        output = {"status": error.status, "records": [], "warnings": [str(error)], "trace_id": error.trace_id}
    except (ValueError, OSError):
        output = {"status": "error", "records": [], "warnings": ["invalid_json_or_local_file"], "trace_id": ""}
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 2 if output.get("status") in ("error", "unavailable") else 0

if __name__ == "__main__":
    sys.exit(main())
