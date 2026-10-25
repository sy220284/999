#!/usr/bin/env python3
"""Call the QY legal proxy and normalize YuanDian statute results."""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ENDPOINT = "/legal/auto_yuandian/api/mcp"
DEFAULT_TEST_BASE_URL = "https://publicapi.chatglm.cn/chatglm_public/skill"
TOOLS = {
    "semantic": "yuandian_law_vector_search",
    "search_article": "yuandian_rh_ft_search",
    "search_regulation": "yuandian_rh_fg_search",
    "article_detail": "yuandian_rh_ft_detail",
    "regulation_detail": "yuandian_rh_fg_detail",
}
DETAIL_RECORD_TYPES = {"article_detail": "article", "regulation_detail": "regulation"}
VALIDITY = {
    "effective": "现行有效",
    "amended": "已被修改",
    "repealed": "失效",
    "not_yet_effective": "尚未生效",
}
EFFECT_LEVEL = {
    "law": "法律",
    "administrative_regulation": "行政法规",
    "judicial_interpretation": "司法解释",
    "department_rule": "部门规章",
    "local_regulation": "地方性法规",
    "local_rule": "地方政府规章",
}
VALIDITY_OUT = {value: key for key, value in VALIDITY.items()}
VALIDITY_OUT.update({"已修改": "amended", "已废止": "repealed"})
EFFECT_LEVEL_OUT = {value: key for key, value in EFFECT_LEVEL.items()}


class ContractError(ValueError):
    pass


def _nonempty(value: Any) -> bool:
    return value not in (None, "", [], {})


def _join(values: Any) -> str | None:
    if isinstance(values, list):
        values = [str(item) for item in values if _nonempty(item)]
        return " ".join(values) or None
    return str(values) if _nonempty(values) else None


def _mapped_list(values: Any, mapping: dict[str, str]) -> list[str]:
    if not isinstance(values, list):
        return []
    return [mapping.get(str(value), str(value)) for value in values if _nonempty(value)]


def _top_k(payload: dict[str, Any]) -> int:
    value = int(payload.get("top_k", 10))
    if value < 1 or value > 50:
        raise ContractError("top_k must be between 1 and 50")
    return value


def build_call(payload: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    operation = payload.get("operation")
    if operation not in TOOLS:
        raise ContractError(f"unsupported operation: {operation!r}")

    query = payload.get("query")
    filters = payload.get("filters") or {}
    warnings: list[str] = []
    arguments: dict[str, Any] = {}

    if operation == "semantic":
        if not _nonempty(query):
            raise ContractError("query is required for semantic search")
        fatiao_filter: dict[str, Any] = {}
        validity = _mapped_list(filters.get("validity_statuses"), VALIDITY)
        levels = _mapped_list(filters.get("effect_levels"), EFFECT_LEVEL)
        if validity:
            fatiao_filter["sxx"] = validity
        if levels:
            fatiao_filter["effect1"] = levels
        region = filters.get("region")
        if _nonempty(region):
            regions = region if isinstance(region, list) else [region]
            fatiao_filter["dy"] = ["中央" if item == "CN" else str(item) for item in regions]
        if _nonempty(filters.get("effective_date_start")):
            fatiao_filter["law_start"] = filters["effective_date_start"]
        if _nonempty(filters.get("effective_date_end")):
            fatiao_filter["law_end"] = filters["effective_date_end"]
        arguments = {
            "query": query,
            "return_num": _top_k(payload),
            "rewrite_flag": bool(payload.get("rewrite", False)),
        }
        if fatiao_filter:
            arguments["fatiao_filter"] = fatiao_filter
        for key in ("publish_department", "publish_date_start", "publish_date_end"):
            if _nonempty(filters.get(key)):
                warnings.append(f"filter_not_supported_by_semantic:{key}")

    elif operation in {"search_article", "search_regulation"}:
        if operation == "search_article" and not _nonempty(query):
            raise ContractError("query is required for search_article")
        field_map = {
            "publish_department": "fbbm",
            "publish_date_start": "fbrq_start",
            "publish_date_end": "fbrq_end",
            "effective_date_start": "ssrq_start",
            "effective_date_end": "ssrq_end",
        }
        if _nonempty(query):
            arguments["keyword"] = query
        if _nonempty(payload.get("law_name")):
            arguments["fgmc"] = payload["law_name"]
        arguments["search_mode"] = str(payload.get("search_mode", "AND")).upper()
        arguments["top_k"] = _top_k(payload)
        validity = _mapped_list(filters.get("validity_statuses"), VALIDITY)
        levels = _mapped_list(filters.get("effect_levels"), EFFECT_LEVEL)
        if validity:
            arguments["sxx"] = _join(validity)
        if levels:
            arguments["xljb_1"] = _join(levels)
        region = filters.get("region")
        if _nonempty(region) and region != "CN":
            arguments["dy"] = _join(region)
        for source, target in field_map.items():
            if _nonempty(filters.get(source)):
                arguments[target] = filters[source]
        if operation == "search_regulation" and not any(
            _nonempty(value) for key, value in arguments.items() if key not in {"search_mode", "top_k"}
        ):
            raise ContractError("search_regulation requires query, law_name, or at least one filter")

    else:
        record_id = payload.get("record_id")
        law_name = payload.get("law_name")
        if _nonempty(record_id):
            record_type = payload.get("record_type")
            expected_type = DETAIL_RECORD_TYPES[operation]
            if record_type not in {"article", "regulation"}:
                raise ContractError(
                    "record_type is required with record_id and must be article or regulation; "
                    "otherwise use law_name plus article_number for article detail"
                )
            if record_type != expected_type:
                raise ContractError(f"record_type_mismatch:{operation}_requires_{expected_type}")
            arguments["id"] = record_id
        elif _nonempty(law_name):
            arguments["fgmc"] = law_name
            if operation == "article_detail":
                article_number = payload.get("article_number")
                if not _nonempty(article_number):
                    raise ContractError("article_number is required when article_detail has no record_id")
                arguments["ftnum"] = article_number
        else:
            raise ContractError("record_id or law_name is required for detail")
        if _nonempty(payload.get("refer_date")):
            arguments["refer_date"] = payload["refer_date"]

    request = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": TOOLS[operation], "arguments": arguments},
    }
    return request, warnings


def _unwrap(raw: Any) -> tuple[Any, str, str | None]:
    if not isinstance(raw, dict):
        raise ContractError("response is not a JSON object")
    trace_id = str(raw.get("rid") or "")
    if raw.get("status") != 0:
        raise ContractError(f"platform_error:{raw.get('status')}:{raw.get('message', '')}")
    result = raw.get("result")
    if not isinstance(result, dict):
        raise ContractError("platform result is not an object")
    if result.get("isError") is True:
        raise ContractError("yuandian_tool_error")
    structured = result.get("structuredContent")
    if not isinstance(structured, dict):
        raise ContractError("missing result.structuredContent")
    if structured.get("ok") is False:
        raise ContractError(f"yuandian_proxy_error:{structured.get('status')}")
    return structured.get("data"), trace_id or str(structured.get("requestId") or ""), structured.get("tool")


def _provider_records(provider: Any, operation: str) -> list[dict[str, Any]]:
    if not isinstance(provider, dict):
        return []
    extra = provider.get("extra")
    if isinstance(extra, dict) and isinstance(extra.get("fatiao"), list):
        data = extra["fatiao"]
    elif provider.get("code") == 201:
        extra = provider.get("extra") or {}
        data = extra.get("fatiao", []) if isinstance(extra, dict) else []
    else:
        data = provider.get("data")
    if isinstance(data, list):
        return [item for item in data if isinstance(item, dict)]
    if isinstance(data, dict):
        if operation in {"search_article", "search_regulation"} and isinstance(data.get("lst"), list):
            return [item for item in data["lst"] if isinstance(item, dict)]
        return [data]
    return []


def _date(value: Any) -> str:
    if value in (None, "", 99999999, "99999999"):
        return ""
    text = str(value)
    if len(text) == 8 and text.isdigit():
        return f"{text[:4]}-{text[4:6]}-{text[6:]}"
    return text


def normalize(raw: Any, payload: dict[str, Any], warnings: list[str] | None = None) -> dict[str, Any]:
    operation = str(payload.get("operation") or "")
    warnings = list(warnings or [])
    provider, trace_id, provider_tool = _unwrap(raw)
    items = _provider_records(provider, operation)
    detail = operation in {"article_detail", "regulation_detail"}
    records: list[dict[str, Any]] = []
    retrieved_at = datetime.now(timezone.utc).isoformat()
    for item in items:
        record_id = item.get("id") or item.get("ftid") or item.get("fgid") or ""
        record_type = "regulation" if operation in {"search_regulation", "regulation_detail"} else "article"
        records.append(
            {
                "record_id": record_id,
                "record_type": record_type,
                "law_name": item.get("fgmc") or item.get("fgtitle") or item.get("title") or "",
                "article_number": item.get("ft_num") or item.get("num") or "",
                "text": item.get("content") or "",
                "validity_status": VALIDITY_OUT.get(item.get("sxx"), item.get("sxx") or "unknown"),
                "effect_level": EFFECT_LEVEL_OUT.get(
                    item.get("xljb_1") or item.get("effect1"), item.get("xljb_1") or item.get("effect1") or "other"
                ),
                "publish_department": item.get("fbbm") or "",
                "publish_date": _date(item.get("fbrq")),
                "effective_date": _date(item.get("ssrq") or item.get("start")),
                "region": "CN" if item.get("dy") in {None, "", "中央"} else item.get("dy"),
                "version_at": payload.get("refer_date"),
                "source_provider": "元典",
                "source_url": item.get("url") or "",
                "retrieved_at": retrieved_at,
                "detail_verified": bool(detail and item.get("content")),
            }
        )
    status = "no_result" if not records else ("partial" if warnings else "ok")
    return {
        "status": status,
        "operation": operation,
        "query": payload.get("query") or "",
        "records": records,
        "warnings": warnings,
        "next_action": None,
        "trace_id": trace_id,
        "provider_tool": provider_tool or TOOLS.get(operation, ""),
    }


def call(base_url: str, request_body: dict[str, Any], timeout: int = 60) -> Any:
    url = base_url.rstrip("/") + ENDPOINT
    request = urllib.request.Request(
        url,
        data=json.dumps(request_body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
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
    parser.add_argument("--dry-run", action="store_true", help="Print the mapped request only")
    args = parser.parse_args()
    try:
        payload = parse_input(args.input)
        request_body, warnings = build_call(payload)
        if args.dry_run:
            output = {"request": request_body, "warnings": warnings}
        else:
            if args.response_file:
                raw = json.loads(Path(args.response_file).read_text("utf-8"))
            else:
                if not args.base_url:
                    raise ContractError("base URL is required; pass --base-url or LEGAL_PROXY_BASE_URL")
                raw = call(args.base_url, request_body)
            output = normalize(raw, payload, warnings)
    except (ContractError, json.JSONDecodeError, OSError) as exc:
        output = {"status": "error", "records": [], "warnings": [str(exc)], "trace_id": ""}
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0 if output.get("status") != "error" else 2


if __name__ == "__main__":
    sys.exit(main())
