"""Offline behavioral tests. No real reader, DNS or HTTP calls."""
import importlib.util
import json
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("reader_client", ROOT / "scripts/client.py")
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)
URL = "https://www.gov.cn/mock-policy.html"

def raw():
    return json.loads((ROOT / "tests/fixtures/reader_success_mock.json").read_text())

class ReaderTests(unittest.TestCase):
    def test_sdk_response(self):
        out = client.normalize(raw(), URL, client.DEFAULT_HOSTS)
        self.assertEqual(out["status"], "ok")
        self.assertEqual(out["http_status"], 200)
        self.assertEqual(out["token_usage"], 100)
        self.assertEqual(out["execution_mode"], "saved_response")
    def test_time_not_assumed(self):
        out = client.normalize(raw(), URL, client.DEFAULT_HOSTS)
        self.assertIsNone(out["published_at"])
        self.assertEqual(out["time_semantics"], "unknown")
        self.assertEqual(out["upstream_time"], raw()["data"]["publishedTime"])
    def test_css_script_removed(self):
        out = client.normalize(raw(), URL, client.DEFAULT_HOSTS)
        self.assertNotIn("display:none", out["text"])
        self.assertNotIn("alert", out["text"])
        self.assertIn("发布时间", out["text"])
    def test_flat_cli(self):
        out = client.normalize({"title":"政策", "url":URL, "text":"有用正文", "publish_time":"unknown"}, URL, client.DEFAULT_HOSTS)
        self.assertEqual(out["text"], "有用正文")
        self.assertIsNone(out["http_status"])
        self.assertIsNone(out["published_at"])
    def test_entities_and_paragraphs(self):
        self.assertEqual(client.clean_html("<p>甲&amp;乙</p><p>丙&nbsp;丁</p>"), "甲&乙\n丙\xa0丁")
    def test_truncation(self):
        out = client.normalize(raw(), URL, client.DEFAULT_HOSTS, max_chars=10)
        self.assertEqual(out["status"], "partial")
        self.assertTrue(out["truncated"])
        self.assertEqual(len(out["text"]), 10)
    def test_upstream_partial(self):
        r = raw(); r["data"]["truncated"] = True
        self.assertEqual(client.normalize(r, URL, client.DEFAULT_HOSTS)["status"], "partial")
    def test_missing_url(self):
        r = raw(); del r["data"]["url"]
        with self.assertRaisesRegex(client.ReaderError, "missing_final_url"):
            client.normalize(r, URL, client.DEFAULT_HOSTS)
    def test_private_final_url(self):
        r = raw(); r["data"]["url"] = "http://127.0.0.1/"
        with self.assertRaises(client.ReaderError):
            client.normalize(r, URL, client.DEFAULT_HOSTS)
    def test_unapproved_final_domain(self):
        r = raw(); r["data"]["url"] = "https://www.gov.cn.evil.example/"
        with self.assertRaises(client.ReaderError):
            client.normalize(r, URL, client.DEFAULT_HOSTS)
    def test_bad_input_urls(self):
        for url in ["file:///etc/passwd", "http://localhost/", "http://192.168.1.1/", "http://169.254.169.254/", "http://[::1]/", "https://user:secret@www.gov.cn/", "https://www.gov.cn:8080/", "https://www.gov.cn.evil.example/", "https://www.gov.cn/\n"]:
            with self.subTest(url=url), self.assertRaises(client.ReaderError):
                client.validate_url(url, client.DEFAULT_HOSTS)
    def test_custom_host(self):
        client.validate_url("https://example.com/", client.allowed_hosts(["example.com"]))
    def test_file_storage_cannot_be_allowed(self):
        for hosts in (client.allowed_hosts(["chatglm.cn"]), client.allowed_hosts(["pfiles.chatglm.cn"])):
            with self.subTest(hosts=hosts), self.assertRaisesRegex(client.ReaderError, "file_storage_url_not_supported"):
                client.validate_url("https://pfiles.chatglm.cn/operation/file/ec/example.md", hosts)
    def test_file_urls_blocked_before_request(self):
        urls = [
            "https://www.gov.cn/report.md",
            "https://www.gov.cn/report.DOCX?download=1",
            "https://www.gov.cn/report%2Emd",
            "https://www.gov.cn/archive.zip/",
            "https://www.gov.cn/report.pdf",
        ]
        for url in urls:
            with self.subTest(url=url), self.assertRaisesRegex(client.ReaderError, "file_url_not_supported"):
                client.validate_url(url, client.DEFAULT_HOSTS)
        client.validate_url("https://www.gov.cn/report.html?file=example.pdf", client.DEFAULT_HOSTS)
    def test_invalid_allow_host(self):
        for host in ["*", "*.com", "com", "https://example.com", "a..com"]:
            with self.assertRaises(client.ReaderError):
                client.allowed_hosts([host])
    def test_dns_private(self):
        with patch.object(client.socket, "getaddrinfo", return_value=[(2,1,6,"",("127.0.0.1",443))]):
            with self.assertRaisesRegex(client.ReaderError, "dns_non_public_ip"):
                client.validate_url(URL, client.DEFAULT_HOSTS, dns=True)
    def test_dns_public(self):
        with patch.object(client.socket, "getaddrinfo", return_value=[(2,1,6,"",("8.8.8.8",443))]):
            client.validate_url(URL, client.DEFAULT_HOSTS, dns=True)
    def test_page_forbidden(self):
        r=raw(); r["data"]["httpStatus"] = 403
        with self.assertRaises(client.ReaderError) as caught:
            client.normalize(r, URL, client.DEFAULT_HOSTS)
        self.assertEqual(caught.exception.status, "blocked")
    def test_reader_quota(self):
        with self.assertRaises(client.ReaderError) as caught:
            client.normalize({"code":429}, URL, client.DEFAULT_HOSTS)
        self.assertEqual(caught.exception.status, "unavailable")
    def test_reader_url_splicing(self):
        self.assertEqual(
            client.build_reader_url(client.DEFAULT_TEST_BASE_URL, "https://chatglm.cn"),
            "https://publicapi.chatglm.cn/chatglm_public/skill/legal/auto_search/api/glm/r/https://chatglm.cn",
        )
    def test_proxy_string_result(self):
        out = client.normalize(
            {"status": 0, "message": "success", "result": "正文内容", "rid": "rid-1"},
            URL,
            client.DEFAULT_HOSTS,
        )
        self.assertEqual(out["status"], "ok")
        self.assertEqual(out["text"], "正文内容")
        self.assertEqual(out["trace_id"], "rid-1")
    def test_proxy_object_result(self):
        out = client.normalize(
            {"status": 0, "message": "success", "result": raw()["data"], "rid": "rid-2"},
            URL,
            client.DEFAULT_HOSTS,
        )
        self.assertEqual(out["status"], "ok")
        self.assertEqual(out["trace_id"], "rid-2")
    def test_proxy_access_denied(self):
        with self.assertRaises(client.ReaderError) as caught:
            client.normalize(
                {"status": 40001, "message": "no access to this resource", "result": None, "rid": "rid-3"},
                URL,
                client.DEFAULT_HOSTS,
            )
        self.assertEqual(caught.exception.status, "blocked")
    def test_unknown_envelope(self):
        with self.assertRaisesRegex(client.ReaderError, "empty_proxy_result"):
            client.normalize({"status": 0, "result": []}, URL, client.DEFAULT_HOSTS)
    def test_empty_body(self):
        r=raw(); r["data"]["html"]="<style>noise</style>"
        with self.assertRaisesRegex(client.ReaderError, "empty_body"):
            client.normalize(r, URL, client.DEFAULT_HOSTS)
    def test_error_page(self):
        r=raw(); r["data"]["title"]="找不到页面"
        with self.assertRaisesRegex(client.ReaderError, "error_page_body"):
            client.normalize(r, URL, client.DEFAULT_HOSTS)
    def test_saved_cli_execution(self):
        r=subprocess.run(["python3",str(ROOT/"scripts/client.py"),"--url",URL,"--response-file",str(ROOT/"tests/fixtures/reader_success_mock.json")],capture_output=True,text=True)
        self.assertEqual(r.returncode,0)
        self.assertEqual(json.loads(r.stdout)["execution_mode"],"saved_response")
    def test_dry_run(self):
        r=subprocess.run(["python3",str(ROOT/"scripts/client.py"),"--url",URL,"--dry-run"],capture_output=True,text=True)
        self.assertEqual(r.returncode,0)
        out = json.loads(r.stdout)
        self.assertFalse(out["network_called"])
        self.assertEqual(
            out["url"],
            client.DEFAULT_TEST_BASE_URL + client.READER_PREFIX + URL,
        )
    def test_invalid_json(self):
        with tempfile.TemporaryDirectory() as t:
            p=pathlib.Path(t)/"raw.json"; p.write_text("invalid")
            with self.assertRaisesRegex(client.ReaderError,"invalid_json_output"):
                client.read_json(p,100)
    def test_output_size_guard(self):
        with self.assertRaisesRegex(client.ReaderError,"output_missing_or_too_large"):
            client.read_json(ROOT/"tests/fixtures/reader_success_mock.json",1)

if __name__ == "__main__":
    unittest.main()
