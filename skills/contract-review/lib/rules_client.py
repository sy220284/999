#!/usr/bin/env python3
"""QY rules proxy adapter. Test default; no Auto or DXE credential dependency."""
import argparse
import json
import os
import socket
import sys
import urllib.error
import urllib.request
from pathlib import Path

TEST_BASE_URL = "https://publicapi.chatglm.cn/chatglm_public/skill"
PREFIX = "/legal/auto_rule/api/rules-engine"

class ClientError(Exception):
    def __init__(self, message, status="error", trace_id=""):
        super().__init__(message)
        self.status, self.trace_id = status, trace_id

def build_request(command, values):
    if command in ("list-catalog", "list-types"):
        if values:
            raise ClientError("catalog commands accept no business arguments")
        return "GET", PREFIX + "/catalog", None
    if command == "load-by-types":
        if len(values) != 1:
            raise ClientError("load-by-types requires one JSON array")
        try:
            types = json.loads(values[0])
        except ValueError:
            raise ClientError("types must be a JSON array")
        if not isinstance(types, list) or not types or any(not isinstance(x, str) or not x.strip() for x in types):
            raise ClientError("types must be a nonempty array of nonempty strings")
    else:
        required = 2 if command == "get-clause" else 1
        if len(values) != required or any(not x.strip() for x in values):
            raise ClientError("get-clause requires contract type and clause name" if required == 2 else "get-baseline requires contract type")
        types = [values[0]]
    return "POST", PREFIX + "/load", {"types": types}

def unwrap(raw):
    if not isinstance(raw, dict):
        raise ClientError("invalid response object")
    rid = str(raw.get("rid") or "")
    if raw.get("status") != 0:
        raise ClientError("legal_proxy_error:" + str(raw.get("status")), "unavailable", rid)
    result = raw.get("result")
    if not isinstance(result, (dict, list)):
        raise ClientError("expected rules object or catalog list inside result", trace_id=rid)
    if isinstance(result, dict) and (result.get("ok") is False or result.get("error")):
        raise ClientError("rules_business_failure", trace_id=rid)
    return result, rid

def normalize(raw, command, values):
    result, rid = unwrap(raw)
    output = {"status": "ok", "operation": command, "data": {}, "warnings": [], "trace_id": rid}
    if command in ("list-catalog", "list-types"):
        catalog = result if isinstance(result, list) else result.get("catalog")
        if not isinstance(catalog, list) or any(not isinstance(x, dict) or not x.get("name") for x in catalog):
            raise ClientError("missing_or_invalid_catalog", trace_id=rid)
        output["data"] = {"catalog": catalog} if command == "list-catalog" else {"types": list(dict.fromkeys(x["name"] for x in catalog))}
        if not catalog:
            output["status"] = "no_result"
    elif command == "get-clause":
        clauses = result.get("merged_standard_clauses")
        if not isinstance(clauses, dict):
            raise ClientError("missing_merged_standard_clauses", trace_id=rid)
        name = values[1]
        output["data"] = {"contract_type": values[0], "clause_name": name, "clause": clauses.get(name)}
        if name not in clauses:
            output["status"] = "no_result"
    else:
        if not any(key in result for key in ("key_points", "merged_key_points", "contract_type_info")):
            raise ClientError("missing_load_rule_fields", trace_id=rid)
        # Preserve server-side merge semantics rather than inventing legal merge rules.
        output["data"] = result
    return output

def call(base_url, method, path, body):
    request = urllib.request.Request(base_url.rstrip("/") + path,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None,
        headers={"Content-Type": "application/json", "Accept": "application/json"}, method=method)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        rid = error.headers.get("X-Request-ID", "")
        try:
            parsed = json.loads(error.read().decode("utf-8"))
            rid = parsed.get("rid") or rid
        except (ValueError, AttributeError):
            pass
        raise ClientError("http_error:" + str(error.code), "unavailable" if error.code >= 500 or error.code == 403 else "error", rid)
    except (urllib.error.URLError, socket.timeout, TimeoutError):
        raise ClientError("network_or_timeout", "unavailable")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["list-catalog", "load-by-types", "get-baseline", "list-types", "get-clause"])
    parser.add_argument("values", nargs="*")
    parser.add_argument("--base-url", default=os.getenv("LEGAL_PROXY_BASE_URL", TEST_BASE_URL))
    parser.add_argument("--response-file")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        method, path, body = build_request(args.command, args.values)
        if args.dry_run:
            output = {"method": method, "url": args.base_url.rstrip("/") + path, "body": body}
        else:
            raw = json.loads(Path(args.response_file).read_text("utf-8")) if args.response_file else call(args.base_url, method, path, body)
            output = normalize(raw, args.command, args.values)
    except ClientError as error:
        output = {"status": error.status, "data": {}, "warnings": [str(error)], "trace_id": error.trace_id}
    except (ValueError, OSError):
        output = {"status": "error", "data": {}, "warnings": ["invalid_json_or_local_file"], "trace_id": ""}
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 2 if output.get("status") in ("error", "unavailable") else 0

if __name__ == "__main__":
    sys.exit(main())
