#!/usr/bin/env python3
"""Pure-Python HTTP client for the split DOCX worker services."""

from __future__ import annotations

import argparse
import io
import re
import json
import os
import shutil
import subprocess
import sys

# Windows 下 stdout/stderr 默认按本地代码页(如 cp936)编码,中文与"✓"等字符会
# 触发 UnicodeEncodeError;统一切换为 UTF-8(Python 3.7+),失败时静默保持原状。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from pathlib import Path
from typing import Iterable


SCRIPT_DIR = Path(__file__).resolve().parent
SKILL_DIR = SCRIPT_DIR.parent
WORKSPACE_DIR = SKILL_DIR.parent.parent
WORK_DIR = Path(os.environ.get("DOCX_HOST_WORK_DIR", WORKSPACE_DIR / ".docx-work")).resolve()
# Test delivery: supplier credentials are injected by the Qingyan backend.
# Override LEGAL_PROXY_BASE_URL (or --base-url) before staging/production.
TEST_BASE_URL = "https://publicapi.chatglm.cn/chatglm_public/skill"
_BASE = os.environ.get("LEGAL_PROXY_BASE_URL", TEST_BASE_URL).rstrip("/")


def _service_url(env_key: str, suffix: str) -> str | None:
    if _BASE:
        return f"{_BASE}/legal/auto_file/api{suffix}"
    return None


def _require_api(url: str | None) -> str:
    if not url:
        raise ClientError(
            "清言服务根地址未配置:请设置 --base-url 或 LEGAL_PROXY_BASE_URL"
        )
    return url


DOTNET_API = _service_url("DOCX_DOTNET_API_URL", "/legal-dotnet")
LIBREOFFICE_API = _service_url("DOCX_LIBREOFFICE_API_URL", "/legal-docx")
VALIDATOR_API = _service_url("DOCX_VALIDATOR_API_URL", "/legal-validator")
TIMEOUT = float(os.environ.get("DOCX_HTTP_TIMEOUT_SECONDS", "240"))
_USER_DOTNET = Path.home() / ".dotnet" / "dotnet"
HOST_DOTNET = os.environ.get(
    "DOCX_HOST_DOTNET",
    str(_USER_DOTNET) if _USER_DOTNET.is_file() else "dotnet",
)


class ClientError(RuntimeError):
    pass


def _decode_response(body: bytes, headers) -> bytes:
    """Unwrap JSON/text; preserve validated file bytes. Never save errors as DOCX."""
    content_type = headers.get("Content-Type", "").split(";", 1)[0].lower().strip()
    trace = headers.get("X-Request-ID", "") or headers.get("X-Trace-Id", "")
    passed = headers.get("X-Docx-Command-Passed", "").lower().strip()
    is_json = content_type == "application/json" or content_type.endswith("+json")
    # Detect an error JSON even when an upstream incorrectly labels it as a file.
    if is_json or body.lstrip().startswith(b"{"):
        try:
            payload = json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ClientError("服务返回无法解析的JSON") from exc
        if not isinstance(payload, dict):
            raise ClientError("服务JSON必须是对象")
        trace = payload.get("rid") or trace
        if "status" not in payload or payload["status"] != 0:
            raise ClientError(f"服务请求失败 status={payload.get('status', 'unknown')} rid={trace}")
        if "result" not in payload:
            raise ClientError(f"服务缺少result rid={trace}")
        result = payload["result"]
        if passed == "false" or (isinstance(result, dict) and (
            result.get("ok") is False or result.get("passed") is False
            or result.get("exit_code", 0) != 0
        )):
            raise ClientError(f"服务操作失败 rid={trace}")
        return (result.encode("utf-8") if isinstance(result, str)
                else json.dumps(result, ensure_ascii=False).encode("utf-8"))
    if passed == "false":
        raise ClientError(f"编译/校验失败 X-Docx-Command-Passed=false rid={trace}")
    if not body:
        raise ClientError(f"服务返回空内容 rid={trace}")
    if content_type not in {
        "application/zip", "application/pdf", "application/octet-stream",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    }:
        raise ClientError(f"未确认的响应类型 {content_type!r} rid={trace}")
    if not (body.startswith(b"PK") or body.startswith(b"%PDF-")):
        raise ClientError(f"文件响应缺少ZIP/DOCX/PDF签名 rid={trace}")
    return body


# Reject redirects for source code and legal files, even without supplier keys.
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def _request(method: str, url: str, data: bytes | None = None, headers: dict[str, str] | None = None) -> bytes:
    try:
        request = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
        with OPENER.open(request, timeout=TIMEOUT) as response:
            return _decode_response(response.read(), response.headers)
    except urllib.error.HTTPError as exc:
        trace = exc.headers.get("X-Request-ID", "") or exc.headers.get("X-Trace-Id", "")
        try:
            payload = json.loads(exc.read())
            if isinstance(payload, dict):
                trace = payload.get("rid") or trace
        except (ValueError, UnicodeDecodeError):
            pass
        raise ClientError(f"HTTP {exc.code}; 请求失败 rid={trace}") from exc
    except (urllib.error.URLError, TimeoutError) as exc:
        raise ClientError("服务不可达或请求超时；文件生成未完成") from exc


def _multipart(
    fields: dict[str, str] | None = None,
    files: Iterable[tuple[str, Path, str]] = (),
) -> tuple[bytes, str]:
    boundary = f"----docx-http-{uuid.uuid4().hex}"
    chunks: list[bytes] = []
    for name, value in (fields or {}).items():
        chunks.extend(
            [
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
                str(value).encode("utf-8"),
                b"\r\n",
            ]
        )
    for field_name, path, media_type in files:
        filename = path.name.replace('"', "_")
        chunks.extend(
            [
                f"--{boundary}\r\n".encode(),
                (
                    f'Content-Disposition: form-data; name="{field_name}"; '
                    f'filename="{filename}"\r\n'
                ).encode("utf-8"),
                f"Content-Type: {media_type}\r\n\r\n".encode(),
                path.read_bytes(),
                b"\r\n",
            ]
        )
    chunks.append(f"--{boundary}--\r\n".encode())
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def _post_multipart(
    url: str,
    *,
    fields: dict[str, str] | None = None,
    files: Iterable[tuple[str, Path, str]] = (),
) -> bytes:
    body, content_type = _multipart(fields, files)
    return _request("POST", url, body, {"Content-Type": content_type})


def _read_result(bundle: bytes) -> tuple[dict, zipfile.ZipFile]:
    try:
        archive = zipfile.ZipFile(io.BytesIO(bundle))
    except zipfile.BadZipFile as exc:
        raise ClientError("预期ZIP结果包，实际返回不匹配；请确认服务返回协议") from exc
    try:
        result = json.loads(archive.read("result.json"))
    except (KeyError, json.JSONDecodeError, UnicodeDecodeError) as exc:
        archive.close()
        raise ClientError("service response is missing a valid result.json") from exc
    if not isinstance(result, dict) or result.get("exit_code", 0) != 0 or result.get("passed") is False:
        archive.close()
        raise ClientError("ZIP中result.json表示操作失败或结构不正确")
    return result, archive


def _print_result(result: dict) -> None:
    print(json.dumps(result, ensure_ascii=False, indent=2))


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as handle:
        handle.write(data)
        temporary = Path(handle.name)
    temporary.replace(path)


def _local_dotnet_environment() -> dict[str, object]:
    try:
        completed = subprocess.run(
            [HOST_DOTNET, "--list-runtimes"],
            capture_output=True,
            text=True,
            timeout=20,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        return {"command": HOST_DOTNET, "exit_code": 127, "runtimes": [], "error": str(exc)}
    return {
        "command": HOST_DOTNET,
        "exit_code": completed.returncode,
        "runtimes": completed.stdout.strip().splitlines(),
        "error": completed.stderr.strip(),
    }


def command_env(_: argparse.Namespace) -> int:
    def probe(api):
        data = _request("GET", f"{_require_api(api)}/v1/env")
        try:
            return json.loads(data)
        except (ValueError, UnicodeDecodeError):
            return data.decode("utf-8", errors="replace")
    dotnet = probe(DOTNET_API)
    validator = probe(VALIDATOR_API)
    libreoffice = probe(LIBREOFFICE_API)
    print(
        json.dumps(
            {
                "compiler_service": dotnet,
                "host_dotnet_runtime": _local_dotnet_environment(),
                "validator_service": validator,
                "libreoffice": libreoffice,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


def command_setup(_: argparse.Namespace) -> int:
    _request("GET", f"{_require_api(DOTNET_API)}/v1/env")
    _request("GET", f"{_require_api(VALIDATOR_API)}/v1/env")
    _request("GET", f"{_require_api(LIBREOFFICE_API)}/v1/env")
    print("✓ DOCX HTTP services are reachable")
    print(f"  .NET: {DOTNET_API}")
    print(f"  Validator: {VALIDATOR_API}")
    print(f"  LibreOffice: {LIBREOFFICE_API}")
    return 0


def command_init(args: argparse.Namespace) -> int:
    result, archive = _read_result(_request("POST", f"{_require_api(DOTNET_API)}/v1/init", b""))
    try:
        WORK_DIR.mkdir(parents=True, exist_ok=True)
        program = WORK_DIR / "Program.cs"
        local_baseline = SKILL_DIR / "assets/templates/Program.cs"
        if args.fresh or not program.exists():
            # Program.cs is an agent-facing authoring baseline, so prefer the
            # version shipped with the active skill.  The compiler service may
            # still expose an older image-baked template until it is rebuilt.
            source = local_baseline.read_bytes() if local_baseline.is_file() else archive.read("Program.cs")
            _atomic_write(program, source)

        project = WORK_DIR / "Docx.csproj"
        if not project.exists():
            _atomic_write(project, archive.read("Docx.csproj"))
    finally:
        archive.close()
    _print_result(result)
    print(f"Edit on host: {WORK_DIR / 'Program.cs'}")
    print(f"Legal baseline: {SKILL_DIR / 'assets/templates/Program.cs'}")
    if program.read_bytes() != local_baseline.read_bytes():
        print("Note: existing Program.cs was preserved; for a new legal document, start from the legal baseline above.")
    return int(result.get("exit_code", 1))


def _compile_bundle() -> bytes:
    source = WORK_DIR / "Program.cs"
    if not source.is_file():
        raise ClientError(f"DOCX work directory is not initialized; missing: {source}")
    return _post_multipart(
        f"{_require_api(DOTNET_API)}/v1/compile",
        files=[("source", source, "text/plain; charset=utf-8")],
    )


def _extract_publish(bundle: bytes, destination: Path) -> dict:
    result, archive = _read_result(bundle)
    try:
        destination.mkdir(parents=True, exist_ok=True)
        for info in archive.infolist():
            if not info.filename.startswith("publish/") or info.is_dir():
                continue
            relative = Path(info.filename).relative_to("publish")
            target = (destination / relative).resolve()
            try:
                target.relative_to(destination.resolve())
            except ValueError as exc:
                raise ClientError(f"unsafe compiler artifact path: {info.filename}") from exc
            _atomic_write(target, archive.read(info))
    finally:
        archive.close()
    return result


def command_compile(args: argparse.Namespace) -> int:
    output_dir = args.output_dir.resolve()
    result = _extract_publish(_compile_bundle(), output_dir)
    _print_result(result)
    if result.get("passed"):
        print(f"Saved portable publish bundle: {output_dir}")
    return int(result.get("exit_code", 1))


def _run_local(entry_point: Path, output: Path) -> dict:
    try:
        completed = subprocess.run(
            [HOST_DOTNET, str(entry_point), str(output)],
            cwd=entry_point.parent,
            capture_output=True,
            text=True,
            timeout=TIMEOUT,
        )
        return {
            "command": [HOST_DOTNET, str(entry_point), str(output)],
            "exit_code": completed.returncode,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
            "output_generated": output.is_file(),
        }
    except FileNotFoundError as exc:
        raise ClientError(
            f"host .NET runtime not found ({HOST_DOTNET}); install .NET 8 Runtime or set DOCX_HOST_DOTNET"
        ) from exc
    except subprocess.TimeoutExpired as exc:
        return {
            "command": [HOST_DOTNET, str(entry_point), str(output)],
            "exit_code": 124,
            "stdout": exc.stdout or "",
            "stderr": exc.stderr or f"host execution timed out after {TIMEOUT} seconds",
            "output_generated": output.is_file(),
        }


def _validate_document(document: Path, *, apply_fixes: bool = True) -> dict:
    stages: dict[str, dict] = {}
    if apply_fixes:
        fix_bundle = _post_multipart(
            f"{_require_api(VALIDATOR_API)}/v1/fix",
            files=[
                (
                    "file",
                    document,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                )
            ],
        )
        fix_result, fix_archive = _read_result(fix_bundle)
        try:
            if fix_result.get("output_generated"):
                _atomic_write(document, fix_archive.read(fix_result["output_name"]))
        finally:
            fix_archive.close()
        stages["python_validation"] = fix_result.get("stages", {}).get(
            "python_validation",
            fix_result,
        )
        if not fix_result.get("passed"):
            return {
                "passed": False,
                "exit_code": int(fix_result.get("exit_code", 1)),
                "stages": stages,
                "validator_service": VALIDATOR_API,
            }

    bundle = _post_multipart(
        f"{_require_api(VALIDATOR_API)}/v1/validate",
        files=[
            (
                "file",
                document,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )
        ],
    )
    remote_result, archive = _read_result(bundle)
    archive.close()
    stages["openxml_validation"] = remote_result.get("stages", {}).get(
        "openxml_validation",
        remote_result,
    )
    return {
        "passed": bool(remote_result.get("passed")),
        "exit_code": int(remote_result.get("exit_code", 1)),
        "stages": stages,
        "validator_service": VALIDATOR_API,
    }


def _build(output: Path, *, validate_output: bool) -> int:
    output = output.resolve()
    with tempfile.TemporaryDirectory(prefix="docx-portable-publish-") as directory:
        publish_root = Path(directory) / "publish"
        compile_result = _extract_publish(_compile_bundle(), publish_root)
        if not compile_result.get("passed"):
            _print_result(compile_result)
            return int(compile_result.get("exit_code", 1))
        relative_entry = str(compile_result.get("entry_point") or "").removeprefix("publish/")
        entry_point = (publish_root / relative_entry).resolve()
        try:
            entry_point.relative_to(publish_root.resolve())
        except ValueError as exc:
            raise ClientError("编译包entry_point越出publish目录，拒绝执行") from exc
        if not relative_entry or not entry_point.is_file():
            raise ClientError("compiler response did not contain its declared entry point")
        generation = _run_local(entry_point, output)
        validation = None
        if generation["exit_code"] == 0 and generation["output_generated"] and validate_output:
            validation = _validate_document(output)

    passed = (
        generation["exit_code"] == 0
        and generation["output_generated"]
        and (validation is None or validation["passed"])
    )
    result = {
        "command": "build" if validate_output else "build-unvalidated",
        "passed": passed,
        "exit_code": 0 if passed else 1,
        "execution_location": "host",
        "output_name": output.name,
        "output_generated": generation["output_generated"],
        "stages": {
            "cloud_compile": compile_result["stages"]["compile"],
            "host_generate": generation,
            **({"host_validation": validation} if validation is not None else {}),
        },
    }
    _print_result(result)
    if result["output_generated"]:
        print(f"Saved: {output}")
    return int(result["exit_code"])


def command_build(args: argparse.Namespace) -> int:
    return _build(args.output, validate_output=True)


def command_build_unvalidated(args: argparse.Namespace) -> int:
    return _build(args.output, validate_output=False)


def command_validate(args: argparse.Namespace) -> int:
    document = args.document.resolve()
    if not document.is_file():
        raise ClientError(f"file not found: {document}")
    result = _validate_document(document)
    result.update(
        {
            "command": "validate",
            "execution_location": "validation-service",
            "file_name": document.name,
        }
    )
    _print_result(result)
    return int(result.get("exit_code", 1))


def command_convert(args: argparse.Namespace, target: str) -> int:
    source = args.source.resolve()
    if not source.is_file():
        raise ClientError(f"file not found: {source}")
    endpoint = "convert-to-pdf" if target == "pdf" else "convert-to-docx"
    data = _post_multipart(
        f"{_require_api(LIBREOFFICE_API)}/v1/{endpoint}",
        files=[("file", source, "application/octet-stream")],
    )
    output = (args.output or source.with_suffix(f".{target}")).resolve()
    if target == "pdf" and not data.startswith(b"%PDF-"):
        raise ClientError("转换结果不是PDF，不保存为成品")
    if target == "docx":
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                if "word/document.xml" not in archive.namelist():
                    raise ClientError("转换结果不是DOCX，不保存为成品")
        except zipfile.BadZipFile as exc:
            raise ClientError("转换结果不是有效DOCX") from exc
    _atomic_write(output, data)
    print(f"Saved: {output}")
    return 0


def command_render(args: argparse.Namespace) -> int:
    source = args.source.resolve()
    if not source.is_file():
        raise ClientError(f"file not found: {source}")
    bundle = _post_multipart(
        f"{_require_api(LIBREOFFICE_API)}/v1/render",
        fields={"dpi": str(args.dpi)},
        files=[("file", source, "application/octet-stream")],
    )
    output_dir = (args.output_dir or source.with_name(f"{source.stem}-rendered")).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    # Page count can shrink between renders; stale page PNGs from a previous
    # render would pollute the bundle (and the visual-check gate). Reset pages/.
    stale_pages = output_dir / "pages"
    if stale_pages.is_dir():
        shutil.rmtree(stale_pages)
    with zipfile.ZipFile(io.BytesIO(bundle)) as archive:
        for info in archive.infolist():
            destination = (output_dir / info.filename).resolve()
            try:
                destination.relative_to(output_dir)
            except ValueError as exc:
                raise ClientError(f"unsafe response archive path: {info.filename}") from exc
            if info.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                _atomic_write(destination, archive.read(info))
    print(f"Saved render bundle: {output_dir}")
    return 0


def command_extract_text(args: argparse.Namespace) -> int:
    source = args.source.resolve()
    if not source.is_file():
        raise ClientError(f"file not found: {source}")
    bundle = _post_multipart(
        f"{_require_api(VALIDATOR_API)}/v1/extract-text",
        files=[
            (
                "file",
                source,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )
        ],
    )
    result, archive = _read_result(bundle)
    try:
        _print_result(result)
        if result.get("output_generated"):
            output = (args.output or source.with_suffix(".txt")).resolve()
            _atomic_write(output, archive.read(result["output_name"]))
            print(f"Saved: {output}")
    finally:
        archive.close()
    return int(result.get("exit_code", 1))


def _zip_resource_directory(directory: Path, archive_path: Path) -> None:
    with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(directory.rglob("*")):
            if path.is_file() and not path.is_symlink():
                archive.write(path, path.relative_to(directory).as_posix())



def command_python(args: argparse.Namespace) -> int:
    return subprocess.call([sys.executable, str(args.script), *args.script_args])



def _load_vendored_pypdf():
    vendor = SKILL_DIR / "vendor"
    wheels = sorted(vendor.glob("pypdf-*-py3-none-any.whl")) if vendor.is_dir() else []
    if wheels:
        wheel = str(wheels[-1])
        if wheel not in sys.path:
            sys.path.insert(0, wheel)
    try:
        from pypdf import PdfReader  # noqa: F401
        return True
    except Exception:
        return False


def command_toc_sync(args: argparse.Namespace) -> int:
    """把渲染 PDF 的标题书签页码回填进 docx 目录 PAGEREF 域的缓存值。

    背景:目录行是「PAGEREF 书签域 + 缓存值」。LibreOffice 导出时解析域(PDF 正确),
    Word 打开可更新域,但 WPS 忽略 updateFieldsOnOpen、直接显示缓存值——不回填则
    WPS 里目录页码全是占位的 1。渲染 PDF 由 LibreOffice 按标题 OutlineLevel 导出
    完整书签(标题→页码),据此把缓存值改写为真实页码,三端显示一致。
    仅改缓存文本,不影响布局;最终交付前在 render 之后执行一次即可,无需重渲染。
    """
    import zipfile as _zip

    docx_path = args.source.resolve()
    if not docx_path.is_file():
        raise ClientError(f"file not found: {docx_path}")
    pdf_path = args.pdf.resolve() if args.pdf else (
        docx_path.with_name(f"{docx_path.stem}-rendered") / f"{docx_path.stem}.pdf")
    if not pdf_path.is_file():
        candidates = sorted((docx_path.with_name(f"{docx_path.stem}-rendered")).glob("*.pdf"))
        if candidates:
            pdf_path = candidates[0]
    if not pdf_path.is_file():
        raise ClientError(f"rendered pdf not found: {pdf_path} (run render first)")
    if not _load_vendored_pypdf():
        raise ClientError("pypdf unavailable (vendor wheel missing and not installed)")
    from pypdf import PdfReader

    reader = PdfReader(str(pdf_path))
    titles: dict[str, int] = {}

    def _walk(items):
        for item in items:
            if isinstance(item, list):
                _walk(item)
                continue
            try:
                page = reader.get_destination_page_number(item) + 1
            except Exception:
                continue
            title = (item.title or "").strip()
            if title and title not in titles:
                titles[title] = page

    try:
        _walk(reader.outline)
    except Exception:
        pass
    if not titles:
        print("toc-sync: PDF has no outline; nothing to sync", file=sys.stderr)
        return 0

    with _zip.ZipFile(docx_path) as z:
        items = {n: z.read(n) for n in z.namelist()}
    xml = items["word/document.xml"].decode("utf-8")

    # 书签名 → 标题文本(书签落在标题段落内)
    bookmark_title: dict[str, str] = {}
    for pm in re.finditer(r"<w:p[ >].*?</w:p>", xml, re.DOTALL):
        seg = pm.group(0)
        bm = re.search(r'<w:bookmarkStart[^>]*w:name="([^"]+)"', seg)
        if not bm:
            continue
        text = "".join(re.findall(r"<w:t[^>]*>([^<]*)</w:t>", seg)).strip()
        if text:
            bookmark_title[bm.group(1)] = text

    def _page_for(bookmark: str) -> int | None:
        title = bookmark_title.get(bookmark)
        if not title:
            return None
        if title in titles:
            return titles[title]
        for outline_title, page in titles.items():
            if outline_title.startswith(title[:20]) or title.startswith(outline_title[:20]):
                return page
        return None

    updated = 0
    # 逐个 PAGEREF 域:把 separate 与 end 之间的缓存 <w:t> 改为真实页码
    pattern = re.compile(
        r'(<w:instrText[^>]*>\s*PAGEREF\s+(\S+)[^<]*</w:instrText>.*?'
        r'<w:fldChar w:fldCharType="separate"[^>]*/>.*?<w:t[^>]*>)([^<]*)(</w:t>)',
        re.DOTALL)

    def _sub(m):
        nonlocal updated
        page = _page_for(m.group(2))
        if page is None:
            return m.group(0)
        updated += 1
        return f"{m.group(1)}{page}{m.group(4)}"

    xml = pattern.sub(_sub, xml)
    if not updated:
        print("toc-sync: no PAGEREF cached values updated (no TOC or bookmarks unmatched)")
        return 0
    items["word/document.xml"] = xml.encode("utf-8")
    tmp = docx_path.with_suffix(".tocsync.tmp")
    with _zip.ZipFile(tmp, "w", _zip.ZIP_DEFLATED) as out:
        for name, data in items.items():
            out.writestr(name, data)
    tmp.replace(docx_path)
    print(f"toc-sync: updated {updated} TOC page number(s) from {pdf_path.name}")
    return 0


def command_visual_check(args: argparse.Namespace) -> int:
    print(
        "本脚本不提供视觉模型调用。请按SKILL.md使用平台实际可用的视觉能力"
        "逐页检查渲染PNG；入口未具备时标记质量门禁未完成，不声称通过。",
        file=sys.stderr,
    )
    return 2


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description="DOCX HTTP toolchain client")
    root.add_argument("--base-url", default=os.getenv("LEGAL_PROXY_BASE_URL", TEST_BASE_URL),
                      help="清言环境根地址；默认测试入口。放在子命令之前")
    root.add_argument("--work-dir", type=Path, default=WORK_DIR,
                      help="每个任务独立的工作目录；放在子命令之前")
    root.add_argument("--dry-run", dest="plan_only", action="store_true",
                      help="只打印环境地址，不发HTTP请求或运行.NET；放在子命令之前")
    commands = root.add_subparsers(dest="command", required=True)
    for name, handler in (("env", command_env), ("setup", command_setup)):
        sub = commands.add_parser(name)
        sub.set_defaults(handler=handler)
    sub = commands.add_parser("init")
    sub.add_argument(
        "--fresh",
        action="store_true",
        help="replace Program.cs with the active skill's compile-tested legal baseline",
    )
    sub.set_defaults(handler=command_init)
    sub = commands.add_parser("compile")
    sub.add_argument("output_dir", type=Path)
    sub.set_defaults(handler=command_compile)
    for name, handler in (("build", command_build), ("build-unvalidated", command_build_unvalidated)):
        sub = commands.add_parser(name)
        sub.add_argument("output", type=Path)
        sub.set_defaults(handler=handler)
    sub = commands.add_parser("validate")
    sub.add_argument("document", type=Path)
    sub.set_defaults(handler=command_validate)
    sub = commands.add_parser("convert-to-pdf")
    sub.add_argument("source", type=Path)
    sub.add_argument("output", nargs="?", type=Path)
    sub.set_defaults(handler=lambda args: command_convert(args, "pdf"))
    sub = commands.add_parser("convert-to-docx")
    sub.add_argument("source", type=Path)
    sub.add_argument("output", nargs="?", type=Path)
    sub.set_defaults(handler=lambda args: command_convert(args, "docx"))
    sub = commands.add_parser("render")
    sub.add_argument("source", type=Path)
    sub.add_argument("output_dir", nargs="?", type=Path)
    sub.add_argument("--dpi", type=int, default=120)
    sub.set_defaults(handler=command_render)
    sub = commands.add_parser("extract-text")
    sub.add_argument("source", type=Path)
    sub.add_argument("output", nargs="?", type=Path)
    sub.set_defaults(handler=command_extract_text)
    sub = commands.add_parser("toc-sync")
    sub.add_argument("source", type=Path)
    sub.add_argument("--pdf", type=Path, default=None)
    sub.set_defaults(handler=command_toc_sync)

    sub = commands.add_parser("visual-check")
    sub.add_argument("input", type=Path)
    sub.add_argument("--output", type=Path)
    sub.add_argument("--model")
    sub.add_argument("--endpoint")
    sub.add_argument("--batch-size", type=int, default=3)
    sub.add_argument("--batch-overlap", type=int, default=1)
    sub.add_argument("--timeout", type=float, default=180.0)
    sub.add_argument("--allow-external-upload", action="store_true")
    sub.add_argument("--dry-run", action="store_true")
    sub.set_defaults(handler=command_visual_check)
    sub = commands.add_parser("python")
    sub.add_argument("script", type=Path)
    sub.add_argument("script_args", nargs=argparse.REMAINDER)
    sub.set_defaults(handler=command_python)
    return root


def main() -> int:
    global _BASE, DOTNET_API, VALIDATOR_API, LIBREOFFICE_API, WORK_DIR
    args = parser().parse_args()
    _BASE = args.base_url.rstrip("/")
    parts = urllib.parse.urlsplit(_BASE)
    if not parts.netloc or parts.scheme not in ("https", "http") or parts.query or parts.fragment or parts.username:
        print("Error: 无效的服务根地址", file=sys.stderr)
        return 1
    DOTNET_API = _service_url("", "/legal-dotnet")
    VALIDATOR_API = _service_url("", "/legal-validator")
    LIBREOFFICE_API = _service_url("", "/legal-docx")
    WORK_DIR = args.work_dir.resolve()
    if args.plan_only:
        _print_result({"base_url": _BASE, "compiler": DOTNET_API,
                       "validator": VALIDATOR_API, "renderer": LIBREOFFICE_API,
                       "command": args.command, "work_dir": str(WORK_DIR),
                       "compile_url": f"{DOTNET_API}/v1/compile",
                       "render_url": f"{LIBREOFFICE_API}/v1/render",
                       "supplier_credentials": "backend-managed", "http_sent": False})
        return 0
    try:
        return int(args.handler(args))
    except (ClientError, zipfile.BadZipFile, OSError, KeyError, ValueError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
