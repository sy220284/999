import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "client.py"
SPEC = importlib.util.spec_from_file_location("web_search_client", SCRIPT)
CLIENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT)


class WebSearchClientTest(unittest.TestCase):
    def test_site_root_is_marked_as_partial(self):
        raw = {"status": 0, "result": {"data": [{"rank": 0, "title": "测试", "url": "https://www.court.gov.cn"}]}, "rid": "r1"}
        output = CLIENT.normalize(raw, {"query": "测试", "count": 10})
        self.assertEqual(output["status"], "partial")
        self.assertIn("result_url_is_site_root:0", output["warnings"])

    def test_article_url_is_not_marked_as_site_root(self):
        raw = {"status": 0, "result": {"data": [{"rank": 1, "title": "测试", "url": "https://www.court.gov.cn/zixun/xiangqing/1.html"}]}}
        output = CLIENT.normalize(raw, {"query": "测试", "count": 10})
        self.assertEqual(output["status"], "ok")

    def test_invalid_count_is_rejected(self):
        with self.assertRaises(CLIENT.ContractError):
            CLIENT.build_query({"query": "测试", "count": 51})

    def test_long_inline_input_is_parsed_as_json(self):
        payload = '{"query":"' + ("测试" * 2000) + '"}'
        self.assertTrue(CLIENT.parse_input(payload)["query"].startswith("测试"))


if __name__ == "__main__":
    unittest.main()
