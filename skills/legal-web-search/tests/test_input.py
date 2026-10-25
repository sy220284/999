import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "client.py"
SPEC = importlib.util.spec_from_file_location("web_search_client", SCRIPT)
CLIENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT)


class InputParsingTest(unittest.TestCase):
    def test_long_inline_json_is_not_treated_as_a_path(self):
        value = json.dumps({"query": "最高人民法院 劳动争议" * 300}, ensure_ascii=False)
        self.assertIn("最高人民法院", CLIENT.parse_input(value)["query"])

    def test_explicit_file_input(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            path.write_text('{"query":"最高人民法院 劳动争议"}', encoding="utf-8")
            self.assertEqual(CLIENT.parse_input("@" + str(path))["query"], "最高人民法院 劳动争议")

    def test_unsupported_constraints_are_not_sent_to_backend(self):
        params, warnings = CLIENT.build_query(
            {
                "query": "劳动争议",
                "domain_filter": "court.gov.cn",
                "recency": "one_month",
                "preferred_source_types": ["court", "government"],
            }
        )
        self.assertEqual(params, {"q": "劳动争议"})
        self.assertIn("constraint_not_applied_by_backend:domain_filter", warnings)
        self.assertIn("constraint_not_applied_by_backend:recency", warnings)
        self.assertIn("preference_not_enforced_by_backend:preferred_source_types", warnings)

    def test_unapplied_constraints_make_result_partial(self):
        payload = {"query": "劳动争议", "preferred_source_types": ["court"]}
        _, warnings = CLIENT.build_query(payload)
        raw = {"status": 0, "result": "[1] Title: 测试\n[1] URL Source: https://www.court.gov.cn/"}
        output = CLIENT.normalize(raw, payload, warnings)
        self.assertEqual(output["status"], "partial")
        self.assertTrue(any(item.startswith("preference_not_enforced_by_backend") for item in output["warnings"]))


if __name__ == "__main__":
    unittest.main()
