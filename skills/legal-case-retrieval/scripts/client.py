#!/usr/bin/env python3
"""Call the QY legal proxy and normalize YuanDian case results."""

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
    "semantic": "yuandian_case_vector_search",
    "keyword_regular": "yuandian_rh_ptal_search",
    "keyword_authoritative": "yuandian_rh_qwal_search__v2",
    "detail": "yuandian_rh_case_details",
}
DOC_TYPES = {"judgment": "判决书", "ruling": "裁定书", "mediation": "调解书", "decision": "决定书"}
DOC_CODES = {"judgment": "1", "ruling": "2", "mediation": "3", "decision": "4"}
CASE_CATEGORIES = {
    "civil": "民事案件",
    "criminal": "刑事案件",
    "administrative": "行政案件",
    "enforcement": "执行案件",
    "state_compensation": "国家赔偿与司法救助案件",
    "other": "其他案件",
}
COURT_LEVELS = {"supreme": "最高", "high": "高级", "intermediate": "中级", "basic": "基层"}


class ContractError(ValueError):
    pass


def _nonempty(value: Any) -> bool:
    return value not in (None, "", [], {})


def _values(value: Any) -> list[Any]:
    return value if isinstance(value, list) else ([] if not _nonempty(value) else [value])


def _map_values(value: Any, mapping: dict[str, str]) -> list[str]:
    return [mapping.get(str(item), str(item)) for item in _values(value)]


def _top_k(payload: dict[str, Any]) -> int:
    value = int(payload.get("top_k", 10))
    if value < 1 or value > 50:
        raise ContractError("top_k must be between 1 and 50")
    return value


def _doc_types(value: Any) -> list[str]:
    values = _values(value)
    unsupported = [str(item) for item in values if str(item) not in DOC_TYPES and str(item) not in DOC_TYPES.values()]
    if unsupported:
        raise ContractError("unsupported document_types: " + ",".join(unsupported))
    return _map_values(values, DOC_TYPES)


def build_call(payload: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    operation = payload.get("operation")
    filters = payload.get("filters") or {}
    warnings: list[str] = []
    arguments: dict[str, Any] = {}

    if operation == "semantic":
        query = payload.get("query")
        if not _nonempty(query):
            raise ContractError("query is required for semantic search")
        wf: dict[str, Any] = {}
        category = filters.get("case_category")
        if _nonempty(category):
            wf["wenshu_type"] = CASE_CATEGORIES.get(str(category), str(category))
        for source, target in (("causes", "ay"), ("courts", "fayuan"), ("authority_sources", "source")):
            if _nonempty(filters.get(source)):
                wf[target] = _values(filters[source])
        requested_doc_types = _doc_types(filters.get("document_types"))
        doc_types = [DOC_CODES[next(key for key, name in DOC_TYPES.items() if name == item)] for item in requested_doc_types]
        if doc_types:
            wf["wszl"] = doc_types
        field_map = {
            "judgment_date_start": "ja_start",
            "judgment_date_end": "ja_end",
            "city": "xzqh_c",
        }
        for source, target in field_map.items():
            if _nonempty(filters.get(source)):
                wf[target] = filters[source]
        if _nonempty(filters.get("court_level")):
            level = str(filters["court_level"])
            if level not in COURT_LEVELS and level not in COURT_LEVELS.values():
                raise ContractError("unsupported court_level: " + level)
            wf["cj"] = COURT_LEVELS.get(level, level)
        if _nonempty(filters.get("provinces")):
            provinces = _values(filters["provinces"])
            if len(provinces) != 1:
                raise ContractError("semantic search supports exactly one province filter")
            wf["xzqh_p"] = provinces[0]
        if filters.get("authoritative_only") is True:
            wf["dianxing"] = True
        arguments = {
            "query": query,
            "return_num": _top_k(payload),
            "rewrite_flag": bool(payload.get("rewrite", False)),
        }
        if wf:
            arguments["wenshu_filter"] = wf
        tool = TOOLS["semantic"]

    elif operation == "keyword":
        authoritative = filters.get("authoritative_only") is True
        tool = TOOLS["keyword_authoritative" if authoritative else "keyword_regular"]
        if authoritative:
            field_pairs = (
                (payload.get("case_number"), "case_number"),
                (payload.get("title"), "title"),
                (payload.get("query"), "keywords"),
            )
            for value, target in field_pairs:
                if _nonempty(value):
                    arguments[target] = value
            for source, target in (("causes", "cause_of_action"), ("courts", "court_name"), ("provinces", "province")):
                if _nonempty(filters.get(source)):
                    arguments[target] = _values(filters[source])
            doc_types = _doc_types(filters.get("document_types"))
            if doc_types:
                arguments["document_type"] = doc_types
            category = filters.get("case_category")
            if _nonempty(category):
                arguments["case_category"] = CASE_CATEGORIES.get(str(category), str(category))
            if _nonempty(filters.get("judgment_date_start")):
                arguments["decision_date_from"] = filters["judgment_date_start"]
            if _nonempty(filters.get("judgment_date_end")):
                arguments["decision_date_to"] = filters["judgment_date_end"]
            if _nonempty(filters.get("authority_sources")):
                arguments["authoritative_case_sources"] = _values(filters["authority_sources"])
        else:
            if _nonempty(payload.get("case_number")):
                arguments["ah"] = payload["case_number"]
            if _nonempty(payload.get("title")):
                arguments["title"] = payload["title"]
            if _nonempty(payload.get("query")):
                arguments["qw"] = payload["query"]
            for source, target in (("causes", "ay"), ("courts", "jbdw"), ("provinces", "xzqh_p")):
                if _nonempty(filters.get(source)):
                    arguments[target] = _values(filters[source])
            doc_types = _doc_types(filters.get("document_types"))
            if doc_types:
                arguments["wszl"] = doc_types
            category = filters.get("case_category")
            if _nonempty(category):
                arguments["ajlb"] = CASE_CATEGORIES.get(str(category), str(category))
            if _nonempty(filters.get("judgment_date_start")):
                arguments["ja_start"] = filters["judgment_date_start"]
            if _nonempty(filters.get("judgment_date_end")):
                arguments["ja_end"] = filters["judgment_date_end"]
            if _nonempty(filters.get("cited_laws")):
                arguments["yyft"] = _values(filters["cited_laws"])
                arguments["ft_search_mode"] = str(filters.get("cited_laws_mode", "or")).lower()
        arguments["search_mode"] = str(payload.get("search_mode", "AND")).lower()
        arguments["top_k"] = _top_k(payload)
        if not any(_nonempty(value) for key, value in arguments.items() if key not in {"search_mode", "top_k"}):
            raise ContractError("keyword search requires at least one query field or filter")
        for unsupported in ("court_level", "city"):
            if _nonempty(filters.get(unsupported)):
                warnings.append(f"filter_not_supported_by_keyword:{unsupported}")

    elif operation == "detail":
        tool = TOOLS["detail"]
        if _nonempty(payload.get("record_id")):
            arguments["id"] = payload["record_id"]
        elif _nonempty(payload.get("case_number")):
            arguments["ah"] = payload["case_number"]
        else:
            raise ContractError("record_id or case_number is required for detail")
        source_type = payload.get("source_type")
        if source_type in {"ptal", "qwal"}:
            arguments["type"] = source_type
        elif filters.get("authoritative_only") is True:
            arguments["type"] = "qwal"
        else:
            arguments["type"] = "ptal"
    else:
        raise ContractError(f"unsupported operation: {operation!r}")

    request = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": tool, "arguments": arguments},
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
    if "success" in structured:
        if structured.get("success") is False or structured.get("status") == "failed":
            raise ContractError(f"yuandian_business_error:{structured.get('message', '')}")
        return structured, trace_id, None
    return structured.get("data"), trace_id or str(structured.get("requestId") or ""), structured.get("tool")


def _provider_records(provider: Any, operation: str) -> list[dict[str, Any]]:
    if not isinstance(provider, dict):
        return []
    extra = provider.get("extra")
    if isinstance(extra, dict) and isinstance(extra.get("wenshu"), list):
        data = extra["wenshu"]
    elif provider.get("code") == 201:
        extra = provider.get("extra") or {}
        data = extra.get("wenshu", []) if isinstance(extra, dict) else []
    else:
        data = provider.get("data")
    if isinstance(data, list):
        return [item for item in data if isinstance(item, dict)]
    if isinstance(data, dict):
        if isinstance(data.get("lst"), list):
            return [item for item in data["lst"] if isinstance(item, dict)]
        return [data]
    return []


def _date(value: Any) -> str:
    if value in (None, ""):
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
    records: list[dict[str, Any]] = []
    retrieved_at = datetime.now(timezone.utc).isoformat()
    for item in items:
        authority = item.get("source") or item.get("case_kind") or (item.get("type") if item.get("type") not in {None, "", "普通案例"} else None)
        authority_labels = _values(authority)
        full_text = (item.get("content") or "") if operation == "detail" else ""
        records.append(
            {
                "record_id": item.get("id") or item.get("scid") or item.get("case_id") or "",
                "case_name": item.get("title") or "",
                "case_number": item.get("ah") or item.get("case_number") or "",
                "court": item.get("jbdw") or item.get("court_name") or "",
                "judgment_date": _date(item.get("cprq") or item.get("jaDate") or item.get("decision_date")),
                "cause": item.get("anyou") or item.get("ay") or item.get("cause_of_action") or "",
                "case_category": item.get("ajlb") or item.get("case_category") or "",
                "document_type": item.get("wszl") or item.get("document_type") or "",
                "authority_labels": authority_labels,
                "facts_summary": item.get("ajjbqk") or item.get("ajjbqk_content") or "",
                "reasoning_summary": item.get("fxgc") or item.get("fxgc_content") or "",
                "decision_summary": item.get("pjjg") or "",
                "summary_or_snippet": "" if operation == "detail" else (item.get("content") or item.get("summary_content") or ""),
                "full_text": full_text,
                "cited_laws": item.get("yyft") or [],
                "source_provider": "元典",
                "source_url": item.get("url") or "",
                "retrieved_at": retrieved_at,
                "detail_verified": bool(operation == "detail" and full_text),
            }
        )
    status = "no_result" if not records else ("partial" if warnings else "ok")
    return {
        "status": status,
        "operation": operation,
        "records": records,
        "warnings": warnings,
        "next_action": None,
        "trace_id": trace_id,
        "provider_tool": provider_tool or "",
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
