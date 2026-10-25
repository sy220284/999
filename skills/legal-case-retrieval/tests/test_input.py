import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "client.py"
SPEC = importlib.util.spec_from_file_location("case_client", SCRIPT)
CLIENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT)


class InputParsingTest(unittest.TestCase):
    def test_long_inline_json_is_not_treated_as_a_path(self):
        value = json.dumps({"operation": "semantic", "query": "买卖合同逾期付款" * 300}, ensure_ascii=False)
        self.assertEqual(CLIENT.parse_input(value)["operation"], "semantic")

    def test_explicit_file_input(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            path.write_text('{"operation":"semantic","query":"逾期付款"}', encoding="utf-8")
            self.assertEqual(CLIENT.parse_input("@" + str(path))["query"], "逾期付款")


if __name__ == "__main__":
    unittest.main()
