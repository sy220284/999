"""Offline tests only. Synthetic DOCX/fixtures do not prove real Track Changes."""
import argparse
import base64
import importlib.util
import io
import json
import tempfile
import unittest
import urllib.error
import zipfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("dxe_client", ROOT / "scripts/dxe_client.py")
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)


def fixture(name):
    return json.loads((ROOT / "tests/fixtures" / name).read_text())


def docx():
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("[Content_Types].xml", "<Types/>")
        archive.writestr("word/document.xml", '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>合成测试条款</w:t></w:r></w:p></w:body></w:document>')
    return stream.getvalue()


class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.source = self.directory / "source.docx"
        self.source.write_bytes(docx())
        self.edits = self.directory / "edits.json"
        self.records = [{"action": "replace", "key": "P1", "old_string": "合成测试条款", "new_string": "修改测试条款", "comment": "合成测试批注"}]
        self.edits.write_text(json.dumps(self.records), encoding="utf-8")
        self.output = self.directory / "revised.docx"
        self.args = argparse.Namespace(file=str(self.source), edits=str(self.edits), out=str(self.output),
            author="法律助手", expect_hash=None, workflow="contract-review", clean_edits=False)

    def tearDown(self):
        self.temp.cleanup()

    def read(self):
        args = argparse.Namespace(file=str(self.source), part="document", out=str(self.directory / "view.xml"))
        with patch.object(client, "_post", return_value=client._unwrap_response(fixture("read_success.json"))):
            client.cmd_read(args)

    def validate(self):
        with patch.object(client, "_post", return_value=client._unwrap_response(fixture("validate_success.json"))):
            client.cmd_validate(self.args)

    def prepared(self):
        self.read()
        self.validate()

    def test_address(self):
        with patch.object(client, "_BASE_URL", client.TEST_BASE_URL):
            self.assertEqual(client._server_url() + "/save", "https://publicapi.chatglm.cn/chatglm_public/skill/legal/auto_file/api/dxe-engine/save")

    def test_json_wrapper(self):
        result = client._unwrap_response(fixture("read_success.json"))
        self.assertEqual(result["_trace_id"], "mock-read")
        self.assertTrue(result["view"])

    def test_proxy_failure(self):
        with self.assertRaises(RuntimeError):
            client._unwrap_response({"status": 403, "result": {}})

    def test_unknown_wrapper(self):
        with self.assertRaises(RuntimeError):
            client._unwrap_response({"ok": True})

    def test_business_failure(self):
        with self.assertRaisesRegex(RuntimeError, "message=未匹配原文"):
            client._require_ok({"ok": False, "code": "NO_MATCH", "failed_edit": 0,
                                "message": "未匹配原文"}, "/validate")

    def test_input_base64(self):
        result = client._resolve_input(str(self.source))
        self.assertEqual(base64.b64decode(result["docx_b64"]), self.source.read_bytes())
        self.assertNotIn("docx_url", result)

    def test_read_context(self):
        self.read()
        self.assertEqual(client._source_context(str(self.source))["local_sha256"], client._sha256_hex(str(self.source)))

    def test_missing_read(self):
        with self.assertRaises(RuntimeError):
            client.cmd_save(self.args)

    def test_missing_preflight(self):
        self.read()
        with self.assertRaises(RuntimeError):
            client.cmd_save(self.args)

    def test_source_changed(self):
        self.prepared()
        self.source.write_bytes(self.source.read_bytes() + b"change")
        with self.assertRaises(RuntimeError):
            client.cmd_save(self.args)

    def test_edits_changed(self):
        self.prepared()
        self.records[0]["new_string"] = "另一个修改"
        self.edits.write_text(json.dumps(self.records), encoding="utf-8")
        with self.assertRaises(RuntimeError):
            client.cmd_save(self.args)

    def test_reread_invalidates_preflight(self):
        self.prepared()
        self.read()
        self.assertFalse(Path(str(self.source) + ".dxevalidated").exists())

    def test_failed_edit_does_not_authorize_save(self):
        self.read()
        with patch.object(client, "_post", return_value={"ok": True, "warnings": [], "failed_edit": 1}):
            with self.assertRaises(SystemExit):
                client.cmd_validate(self.args)
        self.assertFalse(Path(str(self.source) + ".dxevalidated").exists())

    def test_unknown_validate_structure(self):
        self.read()
        with patch.object(client, "_post", return_value={"ok": True}):
            with self.assertRaises(RuntimeError):
                client.cmd_validate(self.args)

    def test_output_not_source(self):
        self.args.out = str(self.source)
        with self.assertRaises(RuntimeError):
            client.cmd_save(self.args)

    def test_output_not_existing(self):
        self.output.touch()
        with self.assertRaises(RuntimeError):
            client.cmd_save(self.args)

    def test_zero_save_not_published(self):
        self.prepared()
        with patch.object(client, "_post", return_value=client._unwrap_response(fixture("save_zero_success.json"))):
            with self.assertRaises(RuntimeError):
                client.cmd_save(self.args)
        self.assertFalse(self.output.exists())

    def test_missing_count_not_zero(self):
        with self.assertRaises(RuntimeError):
            client._count({"ok": True}, "revisions")

    def test_inspect_comments(self):
        args = argparse.Namespace(file=str(self.source), what="comments")
        with patch.object(client, "_post", return_value=client._unwrap_response(fixture("inspect_success.json"))), patch("sys.stdout", new_callable=io.StringIO) as output:
            client.cmd_inspect(args)
            self.assertIn("批注数: 1", output.getvalue())

    def test_invalid_product(self):
        with self.assertRaises(RuntimeError):
            client._emit_product({"out_b64": base64.b64encode(b'{"error":true}').decode()}, str(self.output))
        self.assertFalse(self.output.exists())

    def test_unapproved_download_domain(self):
        self.assertFalse(client._public_download_target_allowed("https://unapproved.example/x.docx"))
        self.assertFalse(client._public_download_target_allowed("http://autoglm-agent.aminer.cn/x.docx"))
        self.assertTrue(client._public_download_target_allowed("https://autoglm-agent.aminer.cn/x.docx"))

    def test_save_builds_original_protocol(self):
        self.prepared()
        before = self.source.read_bytes()
        response = {"ok": True, "edits_applied": 1, "comments_applied": 1, "warnings": [],
            "out_b64": base64.b64encode(docx()).decode()}
        with patch.object(client, "_post", return_value=response) as post:
            client.cmd_save(self.args)
        payload = post.call_args.args[1]
        self.assertEqual(payload["protocol"], 2)
        self.assertEqual(payload["expect_hash"], "mock-document-hash")
        self.assertEqual(payload["edits"], self.records)
        self.assertNotIn("workflow", payload)
        self.assertNotIn("local_sha256", payload)
        self.assertEqual(before, self.source.read_bytes())
        self.assertTrue(self.output.is_file())

    def test_timeout_does_not_retry(self):
        with patch.object(client, "_open_service_request", side_effect=TimeoutError()) as send:
            with self.assertRaisesRegex(RuntimeError, "保存结果不确定"):
                client._post("/save", {"protocol": 2})
        self.assertEqual(send.call_count, 1)

    def test_no_supplier_credentials(self):
        class Response:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, *args): return json.dumps(fixture("read_success.json")).encode()
        with patch.object(client, "_open_service_request", return_value=Response()) as send:
            client._post("/read", {})
        request = send.call_args.args[0]
        self.assertFalse(request.has_header("Authorization"))
        self.assertFalse(request.has_header("X-dept-api-key"))

    def test_old_edit_version_rejected(self):
        with self.assertRaises(SystemExit):
            client._validated_v2_edits([{"action": "replace", "key": "P1", "replace_all": True}])


if __name__ == "__main__":
    with patch("sys.stdout", new_callable=io.StringIO):
        unittest.main()
