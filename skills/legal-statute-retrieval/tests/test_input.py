import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "client.py"
SPEC = importlib.util.spec_from_file_location("statute_client", SCRIPT)
CLIENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT)


class InputParsingTest(unittest.TestCase):
    def test_long_inline_json_is_not_treated_as_a_path(self):
        value = json.dumps({"operation": "semantic", "query": "劳动合同" * 300}, ensure_ascii=False)
        self.assertEqual(CLIENT.parse_input(value)["operation"], "semantic")

    def test_explicit_file_input(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            path.write_text('{"operation":"semantic","query":"劳动合同"}', encoding="utf-8")
            self.assertEqual(CLIENT.parse_input("@" + str(path))["query"], "劳动合同")

    def test_article_detail_rejects_regulation_record(self):
        with self.assertRaisesRegex(CLIENT.ContractError, "record_type_mismatch"):
            CLIENT.build_call(
                {"operation": "article_detail", "record_id": "regulation-id", "record_type": "regulation"}
            )

    def test_article_detail_requires_record_type_with_record_id(self):
        with self.assertRaisesRegex(CLIENT.ContractError, "record_type is required"):
            CLIENT.build_call({"operation": "article_detail", "record_id": "article-id"})

    def test_article_detail_accepts_article_record(self):
        request, warnings = CLIENT.build_call(
            {"operation": "article_detail", "record_id": "article-id", "record_type": "article"}
        )
        self.assertEqual(request["params"]["arguments"]["id"], "article-id")
        self.assertEqual(warnings, [])

    def test_article_detail_accepts_law_name_and_article_number(self):
        request, warnings = CLIENT.build_call(
            {"operation": "article_detail", "law_name": "中华人民共和国民法典", "article_number": "第五百零九条"}
        )
        self.assertEqual(request["params"]["arguments"]["ftnum"], "第五百零九条")
        self.assertEqual(warnings, [])

    def test_article_result_is_tagged_for_follow_up(self):
        fixture = Path(__file__).resolve().parent / "fixtures" / "article_detail_success.json"
        raw = json.loads(fixture.read_text("utf-8"))
        output = CLIENT.normalize(raw, {"operation": "article_detail"})
        self.assertEqual(output["records"][0]["record_type"], "article")

    def test_regulation_result_is_tagged_for_follow_up(self):
        fixture = Path(__file__).resolve().parent / "fixtures" / "article_detail_success.json"
        raw = json.loads(fixture.read_text("utf-8"))
        output = CLIENT.normalize(raw, {"operation": "search_regulation"})
        self.assertEqual(output["records"][0]["record_type"], "regulation")


if __name__ == "__main__":
    unittest.main()
