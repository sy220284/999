#!/usr/bin/env python3
"""Qingyan DXE test adapter, based on the Auto protocol-2 client.
Active commands: read / validate / save / inspect.
Uses JSON/base64 input, backend-managed supplier keys, and no automatic retries.
Legacy local/extended implementations remain as reference but are not enabled.
Defaults to the supplied test root; override --base-url before production."""
from __future__ import annotations

import argparse
import base64
import xml.etree.ElementTree as ElementTree
import hashlib
import html
import ipaddress
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from typing import Any


def configure_utf8_stdio() -> None:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")


configure_utf8_stdio()

# ── Qingyan test configuration and transport ─────────────────────────
TEST_BASE_URL = "https://publicapi.chatglm.cn/chatglm_public/skill"
_BASE_URL = os.environ.get("LEGAL_PROXY_BASE_URL", TEST_BASE_URL).rstrip("/")
_ARTIFACT_HOSTS = {"autoglm-agent.aminer.cn", "publicapi.chatglm.cn"}
_MAX_JSON_BYTES = 60 * 1024 * 1024


def _server_url() -> str:
    parts = urllib.parse.urlsplit(_BASE_URL)
    if (parts.username or parts.password or parts.query or parts.fragment
            or not parts.hostname
            or (parts.scheme != "https" and not (
                parts.scheme == "http" and parts.hostname in {"localhost", "127.0.0.1", "::1"}))):
        raise RuntimeError("无效清言根地址：远程必须HTTPS，HTTP仅允许本地回环")
    return _BASE_URL + "/legal/auto_file/api/dxe-engine"


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_SERVICE_OPENER = urllib.request.build_opener(
    urllib.request.ProxyHandler({}), _NoRedirect())
_PUBLIC_DOWNLOAD_OPENER = urllib.request.build_opener(
    urllib.request.ProxyHandler({}), _NoRedirect())


def _open_service_request(req: urllib.request.Request, timeout: int):
    target = urllib.parse.urlsplit(req.full_url)
    base = urllib.parse.urlsplit(_server_url())
    if (target.scheme, target.netloc) != (base.scheme, base.netloc):
        raise RuntimeError("拒绝向非配置服务发送文档")
    return _SERVICE_OPENER.open(req, timeout=timeout)


def _public_download_target_allowed(url: str) -> bool:
    parts = urllib.parse.urlsplit(url)
    host = (parts.hostname or "").lower()
    if host == "localhost" or host.endswith(".localhost"):
        return False
    try:
        if not ipaddress.ip_address(host).is_global:
            return False
    except ValueError:
        pass
    return (parts.scheme == "https" and not parts.username and not parts.password
            and parts.port in (None, 443)
            and host in _ARTIFACT_HOSTS)


def _oss_upload(bytes_data: bytes, filename: str) -> str | None:
    raise RuntimeError("本版不接旧Auto OSS上传服务；请使用docx_b64输入")


def _oss_download(url: str, timeout: int = 120) -> bytes:
    if not _public_download_target_allowed(url):
        raise RuntimeError("输出URL不在批准的HTTPS下载域名内，请由平台确认后配置artifact-host")
    # No supplier key, Authorization or redirect-following on public downloads.
    with _PUBLIC_DOWNLOAD_OPENER.open(url, timeout=timeout) as response:
        if response.headers.get("Content-Type", "").split(";", 1)[0] == "application/json":
            raise RuntimeError("下载返回JSON，不是DOCX")
        data = response.read(_MAX_JSON_BYTES + 1)
        if len(data) > _MAX_JSON_BYTES:
            raise RuntimeError("下载文件超过本测试客户端限制")
        return data


def _check_docx_bytes(data: bytes) -> None:
    if not data or len(data) > _MAX_JSON_BYTES:
        raise RuntimeError("DOCX为空或超过本测试客户端限制")
    try:
        import io
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = archive.namelist()
            if "[Content_Types].xml" not in names or "word/document.xml" not in names:
                raise RuntimeError("文件缺少DOCX核心部件")
            info = archive.getinfo("word/document.xml")
            if info.file_size > _MAX_JSON_BYTES:
                raise RuntimeError("DOCX正文部件过大")
            ElementTree.fromstring(archive.read("word/document.xml"))
    except (zipfile.BadZipFile, ElementTree.ParseError) as exc:
        raise RuntimeError("文件不是有效DOCX") from exc


def _resolve_input(filepath: str) -> dict[str, Any]:
    with open(filepath, "rb") as source:
        data = source.read(_MAX_JSON_BYTES + 1)
    _check_docx_bytes(data)
    return {"docx_b64": base64.b64encode(data).decode("ascii"),
            "filename": os.path.basename(filepath)}


def _new_output_path(source: str, output: str) -> str:
    target = os.path.realpath(output)
    if target == os.path.realpath(source):
        raise RuntimeError("禁止覆盖原合同")
    if os.path.lexists(target):
        raise RuntimeError("输出文件已存在，请使用新的版本文件名")
    return target


def _emit_product(resp_json: dict[str, Any], out_path: str) -> None:
    if resp_json.get("out_b64"):
        try:
            data = base64.b64decode(resp_json["out_b64"], validate=True)
        except (ValueError, TypeError) as exc:
            raise RuntimeError("out_b64无法解码") from exc
    elif resp_json.get("out_url"):
        data = _oss_download(resp_json["out_url"])
    else:
        raise RuntimeError("DXE响应既无out_url也无out_b64")
    _check_docx_bytes(data)
    target = os.path.abspath(out_path)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=os.path.dirname(target), delete=False) as handle:
            temporary = handle.name
            handle.write(data)
        # Publish only a complete file and never overwrite an existing path.
        os.link(temporary, target)
    except FileExistsError as exc:
        raise RuntimeError("输出文件已存在，不覆盖") from exc
    finally:
        if temporary and os.path.exists(temporary):
            os.unlink(temporary)


def _atomic_json(path: str, value: dict[str, Any]) -> None:
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8",
                dir=os.path.dirname(os.path.abspath(path)), delete=False) as handle:
            temporary = handle.name
            json.dump(value, handle, ensure_ascii=False)
        os.replace(temporary, path)
    finally:
        if temporary and os.path.exists(temporary):
            os.unlink(temporary)


def _sha256_hex(filepath: str) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()

_WORD_TEXT_PART_PATTERN = re.compile(
    r"^word/(?:document|header[^/]*|footer[^/]*|comments[^/]*|footnotes|endnotes)\.xml$"
)
_WORD_PARAGRAPH_PATTERN = re.compile(r"<w:p(?:\s[^>]*)?>.*?</w:p>", re.DOTALL)
_WORD_TEXT_NODE_PATTERN = re.compile(
    r"(<w:(?:t|delText)\b[^>]*>)(.*?)(</w:(?:t|delText)>)",
    re.DOTALL,
)



def _is_xml_package_part(name: str) -> bool:
    lowered = name.lower()
    return lowered.endswith(".xml") or lowered.endswith(".rels")

def _load_sanitization_replacements(mapping_path: str) -> list[tuple[str, str]]:
    mapping = _read_input_json(mapping_path)
    if not isinstance(mapping, dict) or not mapping:
        raise ValueError("脱敏 mapping 必须是非空的 {占位符: 原文} JSON 对象")
    replacements_by_original: dict[str, str] = {}
    for placeholder, original in mapping.items():
        if not isinstance(placeholder, str) or not placeholder:
            raise ValueError("脱敏 mapping 的占位符必须是非空字符串")
        if not isinstance(original, str) or not original:
            raise ValueError(f"脱敏 mapping 中 {placeholder!r} 对应的原文必须是非空字符串")
        if original in placeholder:
            raise ValueError(f"占位符 {placeholder!r} 仍包含原文，无法满足零残留")
        previous = replacements_by_original.get(original)
        if previous is not None and previous != placeholder:
            raise ValueError(f"同一原文不能映射到多个占位符: {original!r}")
        replacements_by_original[original] = placeholder
    return sorted(replacements_by_original.items(), key=lambda item: len(item[0]), reverse=True)


def _replace_once_across_nodes(nodes: list[str], old: str, new: str, start: int) -> tuple[bool, int]:
    combined = "".join(nodes)
    match_start = combined.find(old, start)
    if match_start < 0:
        return False, start
    match_end = match_start + len(old)

    consumed = 0
    start_index = 0
    while start_index < len(nodes) - 1 and match_start >= consumed + len(nodes[start_index]):
        consumed += len(nodes[start_index])
        start_index += 1
    start_offset = match_start - consumed

    end_index = start_index
    end_consumed = consumed
    while end_index < len(nodes) - 1 and match_end > end_consumed + len(nodes[end_index]):
        end_consumed += len(nodes[end_index])
        end_index += 1
    end_offset = match_end - end_consumed

    if start_index == end_index:
        value = nodes[start_index]
        nodes[start_index] = value[:start_offset] + new + value[end_offset:]
    else:
        nodes[start_index] = nodes[start_index][:start_offset] + new
        for index in range(start_index + 1, end_index):
            nodes[index] = ""
        nodes[end_index] = nodes[end_index][end_offset:]
    return True, match_start + len(new)


def _replace_across_word_text_nodes(fragment: str, replacements: list[tuple[str, str]]) -> tuple[str, int]:
    matches = list(_WORD_TEXT_NODE_PATTERN.finditer(fragment))
    if not matches:
        return fragment, 0
    nodes = [html.unescape(match.group(2)) for match in matches]
    count = 0
    for old, new in replacements:
        search_from = 0
        while True:
            replaced, search_from = _replace_once_across_nodes(nodes, old, new, search_from)
            if not replaced:
                break
            count += 1
    if count == 0:
        return fragment, 0

    chunks: list[str] = []
    consumed = 0
    for match, value in zip(matches, nodes):
        chunks.append(fragment[consumed:match.start()])
        chunks.append(match.group(1))
        chunks.append(html.escape(value, quote=False))
        chunks.append(match.group(3))
        consumed = match.end()
    chunks.append(fragment[consumed:])
    return "".join(chunks), count


def _scrub_xml(xml_text: str, part_name: str, replacements: list[tuple[str, str]]) -> tuple[str, int]:
    """XML 感知脱敏：只改文本节点和受控属性，永不触碰声明、标签和结构属性。

    Word 文本 part（正文/批注/页眉页脚/脚注尾注）按段落处理，支持敏感词
    跨 run/w:t 分割的情况；其余 XML part（docProps 元数据等）只替换
    标签之间的文本段，rels 额外替换 Target 外链属性（URL 属于脱敏对象）。
    历史上这里对原始 XML 串做全局 str.replace，mapping 原文为 "1.0" 这类
    纯 ASCII 短串时会把 <?xml version="1.0"?> 声明和属性值一并改坏，
    产出的 docx 无法打开却报成功——禁止回到那种写法。
    """
    if _WORD_TEXT_PART_PATTERN.match(part_name):
        count = 0

        def replace_paragraph(match: re.Match[str]) -> str:
            nonlocal count
            updated, paragraph_count = _replace_across_word_text_nodes(match.group(0), replacements)
            count += paragraph_count
            return updated

        return _WORD_PARAGRAPH_PATTERN.sub(replace_paragraph, xml_text), count
    text, count = _replace_plain_xml_text_nodes(xml_text, replacements)
    if part_name.endswith(".rels"):
        text, attr_count = _replace_rels_target_attributes(text, replacements)
        count += attr_count
    return text, count


_XML_TEXT_SEGMENT_PATTERN = re.compile(r">[^<>]+<", re.DOTALL)


def _replace_plain_xml_text_nodes(xml_text: str, replacements: list[tuple[str, str]]) -> tuple[str, int]:
    """非 Word XML part 的文本段替换：只动 `>…<` 之间的内容。

    声明（<?xml …?>）、标签名、属性值都不属于文本段，天然不会被触碰。
    """
    count = 0

    def replace_segment(match: re.Match[str]) -> str:
        nonlocal count
        segment = match.group(0)
        for old, new in replacements:
            escaped_old = html.escape(old, quote=False)
            if escaped_old in segment:
                count += segment.count(escaped_old)
                segment = segment.replace(escaped_old, html.escape(new, quote=False))
        return segment

    return _XML_TEXT_SEGMENT_PATTERN.sub(replace_segment, xml_text), count


_RELS_TARGET_ATTR_PATTERN = re.compile(r'Target="([^"]*)"')


def _replace_rels_target_attributes(xml_text: str, replacements: list[tuple[str, str]]) -> tuple[str, int]:
    """rels 的 Target 属性是外部链接 URI，属于脱敏对象。

    属性值有引号定界，替换只发生在引号内，不会破坏结构；仅处理 Target，
    不动 Id/Type 等结构性属性。
    """
    count = 0

    def replace_attr(match: re.Match[str]) -> str:
        nonlocal count
        value = html.unescape(match.group(1))
        updated = value
        for old, new in replacements:
            if old in updated:
                count += updated.count(old)
                updated = updated.replace(old, new)
        if updated == value:
            return match.group(0)
        return f'Target="{html.escape(updated, quote=True)}"'

    return _RELS_TARGET_ATTR_PATTERN.sub(replace_attr, xml_text), count


def _find_xml_residuals(docx_path: str, replacements: list[tuple[str, str]]) -> list[str]:
    """零残留校验：只查剥掉标签后的可见文本，与替换范围同口径。

    不能对整份 XML 源码做子串检查——XML 声明 `version="1.0"`、属性值里的
    巧合子串不属于用户文本，替换不动它们，检查也不该把它们算成残留。
    剥标签后文本跨 run 连续，跨节点分割的敏感词同样能查出。
    """
    residual_parts: set[str] = set()
    with zipfile.ZipFile(docx_path, "r") as archive:
        for name in archive.namelist():
            if not _is_xml_package_part(name):
                continue
            xml_text = archive.read(name).decode("utf-8-sig", errors="replace")
            joined_text = html.unescape(re.sub(r"<[^>]+>", "", xml_text))
            if name.endswith(".rels"):
                joined_text += "\n" + "\n".join(
                    html.unescape(value) for value in _RELS_TARGET_ATTR_PATTERN.findall(xml_text)
                )
            if any(original in joined_text for original, _ in replacements):
                residual_parts.add(name)
    return sorted(residual_parts)


def _sanitize_docx(input_path: str, output_path: str, replacements: list[tuple[str, str]]) -> tuple[int, list[str]]:
    output_dir = os.path.dirname(os.path.abspath(output_path))
    os.makedirs(output_dir, exist_ok=True)
    temp_fd, temp_path = tempfile.mkstemp(prefix=f".{os.path.basename(output_path)}.", suffix=".tmp", dir=output_dir)
    os.close(temp_fd)
    replacement_count = 0
    changed_parts: list[str] = []
    try:
        with zipfile.ZipFile(input_path, "r") as source, zipfile.ZipFile(temp_path, "w") as target:
            target.comment = source.comment
            for info in source.infolist():
                data = source.read(info.filename)
                if _is_xml_package_part(info.filename):
                    xml_text = data.decode("utf-8-sig")
                    scrubbed, part_count = _scrub_xml(xml_text, info.filename, replacements)
                    if part_count:
                        try:
                            ElementTree.fromstring(scrubbed)
                        except ElementTree.ParseError as error:
                            raise RuntimeError(
                                f"脱敏后 {info.filename} XML 结构损坏，拒绝发布: {error}"
                            ) from error
                        data = scrubbed.encode("utf-8")
                        replacement_count += part_count
                        changed_parts.append(info.filename)
                target.writestr(info, data)
        residual_parts = _find_xml_residuals(temp_path, replacements)
        if residual_parts:
            raise RuntimeError(f"脱敏后仍有原文残留，拒绝发布: {', '.join(residual_parts)}")
        os.replace(temp_path, output_path)
        return replacement_count, sorted(set(changed_parts))
    finally:
        try:
            os.remove(temp_path)
        except FileNotFoundError:
            pass

def cmd_sanitize(args: argparse.Namespace) -> None:
    try:
        replacements = _load_sanitization_replacements(args.mapping)
        replacement_count, changed_parts = _sanitize_docx(args.file, args.out, replacements)
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as error:
        sys.exit(f"[dxe_client] 脱敏失败: {error}")
    print(
        f"[dxe_client] 脱敏完成: replacements={replacement_count} "
        f"parts={len(changed_parts)} → {args.out}"
    )


def _extract_sensitive_word_text(input_path: str) -> str:
    sections: list[str] = []
    with zipfile.ZipFile(input_path, "r") as archive:
        for name in sorted(archive.namelist()):
            if _WORD_TEXT_PART_PATTERN.match(name):
                xml_text = archive.read(name).decode("utf-8-sig", errors="replace")
                paragraphs: list[str] = []
                for paragraph_match in _WORD_PARAGRAPH_PATTERN.finditer(xml_text):
                    text = "".join(
                        html.unescape(node_match.group(2))
                        for node_match in _WORD_TEXT_NODE_PATTERN.finditer(paragraph_match.group(0))
                    )
                    if text:
                        paragraphs.append(text)
                sections.append(f"<part name={json.dumps(name, ensure_ascii=False)}>\n")
                sections.extend(f"{paragraph}\n" for paragraph in paragraphs)
                sections.append("</part>\n")
                continue
            if name.lower().endswith(".rels"):
                rels_text = archive.read(name).decode("utf-8-sig", errors="replace")
                external_targets = [
                    html.unescape(target)
                    for target in re.findall(r'\bTarget="([^"]+)"', rels_text)
                    if re.match(r"^(?:https?|mailto):", html.unescape(target), re.IGNORECASE)
                ]
                if external_targets:
                    sections.append(f"<part name={json.dumps(name, ensure_ascii=False)}>\n")
                    sections.extend(f"{target}\n" for target in external_targets)
                    sections.append("</part>\n")
    return "".join(sections)


def cmd_extract_text(args: argparse.Namespace) -> None:
    try:
        text = _extract_sensitive_word_text(args.file)
        with open(args.out, "w", encoding="utf-8") as output:
            output.write(text)
    except (OSError, zipfile.BadZipFile) as error:
        sys.exit(f"[dxe_client] 全 part 文本提取失败: {error}")
    print(f"[dxe_client] 全 part 文本已写入 {args.out}")


# ── HTTP ─────────────────────────────────────────────────────────────

def _post(path: str, payload: dict[str, Any], timeout: int = 600) -> dict[str, Any]:
    if path not in {"/read", "/validate", "/save", "/inspect"}:
        raise RuntimeError("本测试版只接通read/validate/save/inspect四项操作")
    url = f"{_server_url()}{path}"
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    if len(data) > _MAX_JSON_BYTES:
        raise RuntimeError("JSON请求超过60MiB；base64会增大体积，不能仅按原文件大小判断")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    req = urllib.request.Request(url, data=data, method="POST", headers=headers)
    try:
        with _open_service_request(req, timeout=timeout) as response:
            raw = response.read(_MAX_JSON_BYTES + 1)
            if len(raw) > _MAX_JSON_BYTES:
                raise RuntimeError("响应超过客户端限制")
            envelope = json.loads(raw)
    except urllib.error.HTTPError as exc:
        trace = exc.headers.get("X-Request-ID", "") or exc.headers.get("X-Trace-Id", "")
        try:
            failed = json.loads(exc.read())
            if isinstance(failed, dict):
                trace = failed.get("rid") or trace
        except (ValueError, UnicodeDecodeError):
            pass
        raise RuntimeError(f"{path} HTTP {exc.code} rid={trace}；不自动重试") from exc
    except (urllib.error.URLError, TimeoutError) as exc:
        hint = "保存结果不确定，请先排查调用记录，不重复提交" if path == "/save" else "操作未完成"
        raise RuntimeError(f"服务不可达或超时：{hint}") from exc
    except (ValueError, UnicodeDecodeError) as exc:
        raise RuntimeError("无法解析DXE统一JSON响应") from exc
    return _unwrap_response(envelope)


def _unwrap_response(envelope: Any) -> dict[str, Any]:
    if not isinstance(envelope, dict):
        raise RuntimeError("DXE响应必须为对象")
    trace = envelope.get("rid", "")
    if type(envelope.get("status")) is not int or envelope["status"] != 0:
        raise RuntimeError(f"清言代理失败 status={envelope.get('status', 'unknown')} rid={trace}")
    result = envelope.get("result")
    if not isinstance(result, dict):
        raise RuntimeError(f"DXE result结构未确认 rid={trace}")
    return {**result, "_trace_id": trace}


def _require_ok(resp: dict[str, Any], operation: str) -> None:
    """统一引擎逻辑失败的退出语义:ok=false 时带完整响应退出,错误格式全 CLI 一致。"""
    if resp.get("ok") is not True:
        raise RuntimeError(f"{operation}失败 code={resp.get('code', 'unknown')} "
                           f"failed_edit={resp.get('failed_edit')} "
                           f"message={resp.get('message', '')} rid={resp.get('_trace_id', '')}")

# ── CLI commands ─────────────────────────────────────────────────────

def _read_input_json(path: str) -> Any:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def cmd_read(args: argparse.Namespace) -> None:
    payload: dict[str, Any] = {**_resolve_input(args.file)}
    source_hash = hashlib.sha256(base64.b64decode(payload["docx_b64"])).hexdigest()
    if args.part:
        payload["part"] = args.part
    resp = _post("/read", payload)
    _require_ok(resp, "/read")
    view = resp.get("view")
    if not isinstance(view, str) or not view or not isinstance(resp.get("doc_hash"), str) or not resp["doc_hash"]:
        raise RuntimeError("read缺少真实视图或doc_hash，不保存读取记录")
    if _sha256_hex(args.file) != source_hash:
        raise RuntimeError("读取过程中原件发生变化，请重新read")
    if args.out:
        if os.path.realpath(args.out) == os.path.realpath(args.file):
            raise RuntimeError("视图输出不能覆盖原合同")
        with open(args.out, "w", encoding="utf-8") as output:
            output.write(view)
        print(f"[dxe_client] 视图已写入 {args.out}")
    else:
        print(view)
    sidecar = args.file + ".dxemeta"
    _atomic_json(sidecar, {"doc_hash": resp["doc_hash"], "part": resp.get("part"),
                          "local_sha256": source_hash, "trace_id": resp.get("_trace_id", "")})
    # A fresh read invalidates a previous preflight receipt.
    if os.path.exists(args.file + ".dxevalidated"):
        os.remove(args.file + ".dxevalidated")


_V2_ACTIONS = ("replace", "insert", "delete", "comment", "format")
_V1_FIELDS = ("replace_all", "comment_only", "delete_paragraph")


def _validated_v2_edits(records: Any) -> list[dict[str, Any]]:
    """v2 前置校验：五动作单数组直通，不合规直接退出、不发请求。

    只做结构性拒绝（缺字段、v1 残留）；跨字段校验由引擎 validate_edit_v2
    统一负责。下标口径与引擎一致：edit[N]，N 为请求数组下标（0 起）。
    """
    if not isinstance(records, list):
        sys.exit("[dxe_client] edits.json 顶层必须是数组（v2 协议，一条 edit 一个动作）")
    for index, record in enumerate(records):
        where = f"edit[{index}]"
        if not isinstance(record, dict):
            sys.exit(f"[dxe_client] edits.json {where} 必须是对象")
        for field in _V1_FIELDS:
            if field in record:
                sys.exit(
                    f"[dxe_client] edits.json {where} 含 v1 字段 {field}："
                    "协议已升级为 v2，请按新 SKILL 重新构造 edits"
                )
        action = record.get("action")
        if not isinstance(action, str) or not action:
            sys.exit(f"[dxe_client] edits.json {where} 缺 action（v2 必填：replace/insert/delete/comment/format）")
        if action not in _V2_ACTIONS:
            sys.exit(f"[dxe_client] edits.json {where} action={action} 不是五动作之一（replace/insert/delete/comment/format）")
        key = record.get("key")
        if not isinstance(key, str) or not key:
            sys.exit(f"[dxe_client] edits.json {where} 缺 key（v2 每条 edit 必填段落编号，如 P5/T2）")
    return records

def _count(resp: dict[str, Any], field: str) -> int:
    value = resp.get(field)
    if type(value) is not int or value < 0:
        raise RuntimeError(f"响应缺少有效{field}，不能默认按零条处理")
    return value


def _source_context(filepath: str) -> dict[str, Any]:
    path = filepath + ".dxemeta"
    if not os.path.isfile(path):
        raise RuntimeError("缺少读取记录，请先read原合同")
    meta = _read_input_json(path)
    if not isinstance(meta, dict) or not meta.get("doc_hash") or not meta.get("local_sha256"):
        raise RuntimeError("读取记录不完整，请重新read")
    if meta["local_sha256"] != _sha256_hex(filepath):
        raise RuntimeError("原合同已变化，请重新read并构造修改计划")
    return meta


def _guarded_input(filepath: str, meta: dict[str, Any]) -> dict[str, Any]:
    payload = _resolve_input(filepath)
    digest = hashlib.sha256(base64.b64decode(payload["docx_b64"])).hexdigest()
    if digest != meta["local_sha256"]:
        raise RuntimeError("待发送文件与读取记录不一致")
    return payload


def _edits_digest(edits: list[dict[str, Any]]) -> str:
    data = json.dumps(edits, ensure_ascii=False, sort_keys=True).encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def _write_preflight(filepath: str, edits: list[dict[str, Any]], meta: dict[str, Any]) -> None:
    if _sha256_hex(filepath) != meta["local_sha256"]:
        raise RuntimeError("预检过程中原件发生变化，请重新read")
    _atomic_json(filepath + ".dxevalidated", {
        "doc_hash": meta["doc_hash"], "local_sha256": meta["local_sha256"],
        "edits_sha256": _edits_digest(edits)})


def _require_preflight(filepath: str, edits: list[dict[str, Any]], meta: dict[str, Any]) -> None:
    path = filepath + ".dxevalidated"
    if not os.path.isfile(path):
        raise RuntimeError("缺少成功预检记录，请先validate同一原件与edits")
    receipt = _read_input_json(path)
    expected = {"doc_hash": meta["doc_hash"], "local_sha256": meta["local_sha256"],
                "edits_sha256": _edits_digest(edits)}
    if receipt != expected:
        raise RuntimeError("修改计划或原件与预检不一致，请重新validate")


def cmd_save(args: argparse.Namespace) -> None:
    records = _read_input_json(args.edits) if args.edits else []
    edits = _validated_v2_edits(records)
    if not edits:
        raise RuntimeError("没有修改项，不调用save")
    output = _new_output_path(args.file, args.out)
    meta = _source_context(args.file)
    if args.expect_hash and args.expect_hash != meta["doc_hash"]:
        raise RuntimeError("expect_hash与最近读取记录不一致，请重新read")
    _require_preflight(args.file, edits, meta)
    payload: dict[str, Any] = {
        **_guarded_input(args.file, meta),
        "protocol": 2,
        "edits": edits,
    }
    if args.author:
        payload["author"] = args.author
    payload["expect_hash"] = meta["doc_hash"]
    # Consume preflight before sending: on timeout the save outcome is uncertain.
    os.remove(args.file + ".dxevalidated")
    resp = _post("/save", payload)
    _require_ok(resp, "/save")
    n_edits = _count(resp, "edits_applied")
    n_comments = _count(resp, "comments_applied")
    if n_edits + n_comments == 0:
        raise RuntimeError("save返回零修改/零批注，不作为已完成修订交付")
    _emit_product(resp, output)
    warnings = resp.get("warnings", [])
    print(f"[dxe_client] 文件已保存待核验: edits={n_edits} comments={n_comments} → {args.out} rid={resp.get('_trace_id', '')}")
    if warnings:
        print(f"[dxe_client] {len(warnings)} 条警告:", file=sys.stderr)
    for w in warnings:
        print(f"  [{w.get('code','?')}] {w.get('message','')}", file=sys.stderr)
    # save 成功后默认保留 edits.json：inspect 发现遗漏后要"修改同一个
    # edits.json 再重新 validate → save"，删了它重试流程就断了。需要清理
    # 时显式传 --clean-edits。.dxemeta 是单次会话的写保护元数据，总是删。
    try:
        os.remove(args.file + ".dxemeta")
    except OSError:
        pass
    if getattr(args, "clean_edits", False) and args.edits:
        try:
            os.remove(args.edits)
            print(f"[dxe_client] 已清理 edits 文件: {args.edits}")
        except OSError:
            pass
    elif args.edits:
        print(f"[dxe_client] edits.json 已保留(重试/复用请继续维护同一文件;确认交付后可用 --clean-edits 清理): {args.edits}")


def cmd_validate(args: argparse.Namespace) -> None:
    records = _read_input_json(args.edits) if args.edits else []
    edits = _validated_v2_edits(records)
    meta = _source_context(args.file)
    receipt = args.file + ".dxevalidated"
    if os.path.exists(receipt):
        os.remove(receipt)
    payload = {**_guarded_input(args.file, meta), "protocol": 2, "edits": edits}
    resp = _post("/validate", payload)
    _require_ok(resp, "/validate")
    warnings = resp.get("warnings")
    if not isinstance(warnings, list):
        raise RuntimeError("validate缺少warnings列表，响应结构待确认，不生成预检记录")
    if not warnings and resp.get("failed_edit") is None:
        _write_preflight(args.file, edits, meta)
        print("[dxe_client] 校验通过，无问题")
        return
    if warnings:
        print(f"[dxe_client] {len(warnings)} 条校验警告:")
        for w in warnings:
            print(f"  [{w.get('code','?')}] {w.get('message','')}")
    if resp.get("failed_edit") is not None:
        print(f"[dxe_client] 第 {resp['failed_edit']} 条 edit 失败: {resp.get('code')} {resp.get('message')}")
        sys.exit(1)
    _write_preflight(args.file, edits, meta)
    print("[dxe_client] 预检警告需复核，尚未证明最终文件合格")


def cmd_inspect(args: argparse.Namespace) -> None:
    payload: dict[str, Any] = {**_resolve_input(args.file), "what": args.what}
    resp = _post("/inspect", payload)
    _require_ok(resp, "/inspect")
    what = resp.get("what", args.what)
    if what == "revisions":
        print(f"修订痕迹数: {_count(resp, 'revisions')}")
    elif what == "comments":
        print(f"批注数: {_count(resp, 'comments')}")
        for warning in resp.get("comment_warnings", []):
            print(f"  警告: {warning}")
    elif what == "headers_footers":
        hfs = resp.get("headers_footers", [])
        print(f"页眉页脚 part 数: {len(hfs)}")
        for hf in hfs:
            print(f"  {hf}")


def cmd_transform(args: argparse.Namespace) -> None:
    payload: dict[str, Any] = {
        **_resolve_input(args.file),
        "op": args.op,
    }
    if args.author:
        payload["author"] = args.author
    resp = _post("/transform", payload)
    _require_ok(resp, "/transform")
    _emit_product(resp, args.out)
    print(f"[dxe_client] {args.op} 完成 → {args.out}")


def cmd_upload(args: argparse.Namespace) -> None:
    with open(args.file, "rb") as f:
        oss_url = _oss_upload(f.read(), os.path.basename(args.file))
    if oss_url is None:
        print("[dxe_client] FILE_SERVICE_URL 未设置，无法上传", file=sys.stderr)
        sys.exit(1)
    print(oss_url)


def cmd_download(args: argparse.Namespace) -> None:
    data = _oss_download(args.url)
    with open(args.out, "wb") as f:
        f.write(data)
    print(f"[dxe_client] 下载 {len(data)} 字节 → {args.out}")


# ── extended DXE commands ────────────────────────────────────────────

def _file_to_b64(filepath: str) -> str:
    with open(filepath, "rb") as f:
        return base64.b64encode(f.read()).decode()


def cmd_compare(args: argparse.Namespace) -> None:
    payload = {
        "base_b64": _file_to_b64(args.base),
        "revised_b64": _file_to_b64(args.revised),
        "author": args.author,
    }
    resp = _post("/compare", payload)
    _require_ok(resp, "/compare")
    _emit_product(resp, args.out)
    print(f"[dxe_client] 对比完成 → {args.out}")


def cmd_compare_report(args: argparse.Namespace) -> None:
    payload = {
        "base_b64": _file_to_b64(args.base),
        "revised_b64": _file_to_b64(args.revised),
    }
    resp = _post("/compare-report", payload)
    _require_ok(resp, "/compare-report")
    print(json.dumps(resp, ensure_ascii=False, indent=2))


def cmd_embed_image(args: argparse.Namespace) -> None:
    payload: dict[str, Any] = {
        **_resolve_input(args.file),
        "image_b64": _file_to_b64(args.image),
        "filename": os.path.basename(args.image),
    }
    if args.width_emu is not None:
        payload["width_emu"] = args.width_emu
    if args.height_emu is not None:
        payload["height_emu"] = args.height_emu
    resp = _post("/embed-image", payload)
    _require_ok(resp, "/embed-image")
    _emit_product(resp, args.out)
    print(f"[dxe_client] 图片嵌入完成 → {args.out}")


def cmd_enable_update_fields(args: argparse.Namespace) -> None:
    payload = {**_resolve_input(args.file)}
    resp = _post("/enable-update-fields", payload)
    _require_ok(resp, "/enable-update-fields")
    _emit_product(resp, args.out)
    print(f"[dxe_client] 域更新已启用 → {args.out}")


def cmd_latex_to_omml(args: argparse.Namespace) -> None:
    resp = _post("/latex-to-omml", {"latex": args.latex})
    _require_ok(resp, "/latex-to-omml")
    print(resp.get("omml", ""))


def cmd_validate_docx(args: argparse.Namespace) -> None:
    payload = {**_resolve_input(args.file)}
    resp = _post("/validate-docx", payload)
    if not resp.get("ok"):
        for e in resp.get("errors", []):
            print(f"  [ERROR] {e}")
        sys.exit(1)
    for w in resp.get("warnings", []):
        print(f"  [WARN] {w}")
    print(f"[dxe_client] 结构校验通过 (parts={resp.get('parts_checked')}, rels={resp.get('rels_checked')})")


def cmd_unit_convert(args: argparse.Namespace) -> None:
    resp = _post(f"/utils/{args.unit}", {"value": args.value})
    _require_ok(resp, f"/utils/{args.unit}")
    print(resp.get("emu"))


# ── main ─────────────────────────────────────────────────────────────

def main() -> None:
    global _BASE_URL, _ARTIFACT_HOSTS
    p = argparse.ArgumentParser(description="Qingyan DXE test adapter (four active operations)")
    p.add_argument("--base-url", default=os.environ.get("LEGAL_PROXY_BASE_URL", TEST_BASE_URL),
                   help="清言环境根地址，默认当前环境地址；放在子命令之前")
    p.add_argument("--dry-run", action="store_true", help="只显示配置，不读取文件或发请求；放在子命令之前")
    p.add_argument("--artifact-host", action="append", default=[],
                   help="平台确认的额外HTTPS产物域名；仅填hostname，放在子命令之前")
    sub = p.add_subparsers(dest="cmd", required=True)

    pr = sub.add_parser("read", help="读 docx 视图 + hash")
    pr.add_argument("--file", required=True, help="docx 文件路径")
    pr.add_argument("--part", default="document", help="document/header:rIdN/footer:rIdN")
    pr.add_argument("--out", default=None, help="把 UTF-8 视图写入文件，不走 shell 重定向")
    pr.set_defaults(func=cmd_read)

    ps = sub.add_parser("save", help="应用 v2 edits.json 产出批注 docx")
    ps.add_argument("--file", required=True, help="输入 docx")
    ps.add_argument("--edits", required=True, help="v2 edits JSON 文件：顶层数组，五动作 replace/insert/delete/comment/format，每条必填 action+key；replace/insert/format 可选 attrs 指定格式")
    ps.add_argument("--author", default="法律助手")
    ps.add_argument("--expect-hash", default=None, help="陈旧写保护: read 返回的 doc_hash")
    # --workflow 是有意设计的单用途埋点标记:renderer 的 autoLegalSkillMatchers
    # 靠命令行里的 `--workflow contract-review` 字符串把 save 归类为合同审核
    # 交付。cmd_save 不读它、也不发给服务端——不要把它接入业务数据。
    ps.add_argument("--workflow", choices=["contract-review"], default=None, help="仅作 renderer 埋点识别的命令行标记;不参与业务逻辑,服务端不可见")
    ps.add_argument("--clean-edits", action="store_true", help="save 成功后删除 edits.json;默认保留供重试流程复用")
    ps.add_argument("--out", required=True, help="输出 docx 路径")
    ps.set_defaults(func=cmd_save)


    pv = sub.add_parser("validate", help="校验 v2 edits 不落盘（含 comment 动作）")
    pv.add_argument("--file", required=True)
    pv.add_argument("--edits", required=True, help="v2 edits JSON 文件")
    pv.set_defaults(func=cmd_validate)

    pi = sub.add_parser("inspect", help="核验修订或批注")
    pi.add_argument("--file", required=True)
    pi.add_argument("--what", default="revisions", choices=["revisions", "comments"])
    pi.set_defaults(func=cmd_inspect)


    args = p.parse_args()
    _BASE_URL = args.base_url.rstrip("/")
    _server_url()
    for host in args.artifact_host:
        parsed = urllib.parse.urlsplit("https://" + host)
        if parsed.hostname != host.lower() or parsed.port is not None or parsed.path or parsed.username:
            raise RuntimeError("artifact-host必须是平台批准的纯hostname")
        _ARTIFACT_HOSTS.add(host.lower())
    if args.cmd not in {"read", "validate", "save", "inspect"}:
        raise RuntimeError("本期只适配read/validate/save/inspect，扩展操作未联调，不启用旧链")
    if args.dry_run:
        print(json.dumps({"url": _server_url() + "/" + args.cmd,
            "method": "POST", "request_type": "application/json",
            "input_mode": "docx_b64", "supplier_key": "backend-managed",
            "http_sent": False}, ensure_ascii=False, indent=2))
        return
    args.func(args)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, urllib.error.URLError) as error:
        sys.exit(f"[dxe_client] {error}")
