import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "client.py"
SPEC = importlib.util.spec_from_file_location("statute_client", SCRIPT)
CLIENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT)


class StatuteClientTest(unittest.TestCase):
    def test_semantic_region_and_filters_are_mapped(self):
        request, warnings = CLIENT.build_call({
            "operation": "semantic",
            "query": "全面履行合同",
            "top_k": 5,
            "filters": {"validity_statuses": ["amended"], "effect_levels": ["law"], "region": "CN"},
        })
        filters = request["params"]["arguments"]["fatiao_filter"]
        self.assertEqual(filters["sxx"], ["已被修改"])
        self.assertEqual(filters["effect1"], ["法律"])
        self.assertEqual(filters["dy"], ["中央"])
        self.assertEqual(warnings, [])

    def test_semantic_proxy_envelope_reads_extra_fatiao(self):
        raw = {
            "status": 0,
            "result": {"isError": False, "structuredContent": {
                "ok": True,
                "tool": "yuandian_law_vector_search",
                "data": {"extra": {"fatiao": [{
                    "id": "law_1", "fgmc": "测试法", "ft_num": "第一条", "content": "正文", "sxx": "现行有效"
                }]}},
            }},
            "rid": "trace-law",
        }
        output = CLIENT.normalize(raw, {"operation": "semantic"})
        self.assertEqual(output["status"], "ok")
        self.assertEqual(output["records"][0]["record_id"], "law_1")
        self.assertEqual(output["records"][0]["record_type"], "article")

    def test_record_type_mismatch_is_rejected(self):
        with self.assertRaises(CLIENT.ContractError):
            CLIENT.build_call({"operation": "article_detail", "record_id": "law_1", "record_type": "regulation"})

    def test_invalid_top_k_is_rejected(self):
        with self.assertRaises(CLIENT.ContractError):
            CLIENT.build_call({"operation": "search_article", "query": "测试", "top_k": 51})

    def test_long_inline_input_is_parsed_as_json(self):
        payload = '{"operation":"semantic","query":"' + ("测试" * 2000) + '"}'
        self.assertEqual(CLIENT.parse_input(payload)["operation"], "semantic")


if __name__ == "__main__":
    unittest.main()
