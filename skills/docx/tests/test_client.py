"""Offline protocol tests. No HTTP, C# compilation or DLL execution."""
import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("docx_http", Path(__file__).resolve().parents[1] / "scripts/docx_http.py")
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)


def bundle(result, extra=None):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("result.json", json.dumps(result))
        for key, value in (extra or {}).items():
            archive.writestr(key, value)
    return stream.getvalue()


class ProtocolTests(unittest.TestCase):
    def test_test_address(self):
        with patch.object(client, "_BASE", client.TEST_BASE_URL):
            self.assertEqual(client._service_url("", "/legal-dotnet") + "/v1/compile",
                "https://publicapi.chatglm.cn/chatglm_public/skill/legal/auto_file/api/legal-dotnet/v1/compile")

    def test_json_unwrap(self):
        body = json.dumps({"status": 0, "result": {"ok": True}, "rid": "mock"}).encode()
        self.assertEqual(json.loads(client._decode_response(body, {"Content-Type": "application/json"})), {"ok": True})

    def test_text_unwrap(self):
        body = b'{"status":0,"result":"environment report"}'
        self.assertEqual(client._decode_response(body, {"Content-Type": "application/json"}), b"environment report")

    def test_error_json_labeled_docx(self):
        with self.assertRaises(client.ClientError):
            client._decode_response(b'{"status":403,"message":"secret content"}', {"Content-Type": "application/octet-stream"})

    def test_false_header(self):
        with self.assertRaises(client.ClientError):
            client._decode_response(b"PKfake", {"Content-Type": "application/zip", "X-Docx-Command-Passed": "false"})

    def test_business_failure(self):
        with self.assertRaises(client.ClientError):
            client._decode_response(b'{"status":0,"result":{"passed":false}}', {"Content-Type": "application/json"})

    def test_preserves_binary(self):
        data = bundle({"passed": True, "exit_code": 0})
        self.assertEqual(client._decode_response(data, {"Content-Type": "application/zip"}), data)

    def test_no_fake_file(self):
        with self.assertRaises(client.ClientError):
            client._decode_response(b"error page", {"Content-Type": "application/octet-stream"})

    def test_unknown_content_type(self):
        with self.assertRaises(client.ClientError):
            client._decode_response(b"<html>error</html>", {"Content-Type": "text/html"})

    def test_valid_bundle(self):
        result, archive = client._read_result(bundle({"passed": True, "exit_code": 0}))
        archive.close()
        self.assertTrue(result["passed"])

    def test_failed_bundle(self):
        with self.assertRaises(client.ClientError):
            client._read_result(bundle({"exit_code": 1}))

    def test_missing_report(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("other.txt", "mock")
        with self.assertRaises(client.ClientError):
            client._read_result(stream.getvalue())

    def test_archive_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(client.ClientError):
                client._extract_publish(bundle({"exit_code": 0}, {"publish/../../escaped.txt": "mock"}), Path(directory) / "publish")

    def test_compile_field(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "Program.cs"
            source.touch()
            with patch.object(client, "WORK_DIR", Path(directory)), patch.object(client, "_post_multipart", return_value=b"mock") as post:
                client._compile_bundle()
            self.assertEqual(post.call_args.kwargs["files"][0][0], "source")

    def test_no_supplier_key(self):
        class FakeResponse:
            headers = {"Content-Type": "application/json"}
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self): return b'{"status":0,"result":{"ok":true}}'
        with patch.object(client.OPENER, "open", return_value=FakeResponse()) as opener:
            client._request("GET", "https://example.invalid/v1/env")
        request = opener.call_args.args[0]
        self.assertFalse(request.has_header("Authorization"))
        self.assertFalse(request.has_header("X-dept-api-key"))

    def test_env_string_result(self):
        with patch.object(client, "_request", return_value=b"environment ready"), \
             patch.object(client, "_local_dotnet_environment", return_value={"runtimes": []}), \
             patch("sys.stdout", new_callable=io.StringIO) as stream:
            self.assertEqual(client.command_env(None), 0)
            self.assertEqual(json.loads(stream.getvalue())["compiler_service"], "environment ready")

    def test_bad_conversion_does_not_overwrite(self):
        import argparse
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.docx"
            source.touch()
            output = Path(directory) / "output.pdf"
            output.touch()
            with patch.object(client, "_post_multipart", return_value=b'{"status":0,"result":{}}'):
                with self.assertRaises(client.ClientError):
                    client.command_convert(argparse.Namespace(source=source, output=output), "pdf")
            self.assertEqual(output.stat().st_size, 0)


if __name__ == "__main__":
    unittest.main()
