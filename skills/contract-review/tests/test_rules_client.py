import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "lib" / "rules_client.py"
SPEC = importlib.util.spec_from_file_location("rules_client", SCRIPT)
CLIENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT)

RAW_LIST = {
    "status": 0,
    "message": "success",
    "result": [
        {"name": "通用采购合同", "parent_type": "", "position": "甲方（买方）"},
        {"name": "API/AI采购合同", "parent_type": "通用采购合同", "position": "甲方（买方）"},
        {"name": "通用采购合同", "parent_type": "", "position": "甲方（买方）"},
    ],
}


class CatalogNormalizationTest(unittest.TestCase):
    def test_full_catalog_accepts_list_result(self):
        output = CLIENT.normalize(RAW_LIST, "list-catalog", [])
        self.assertEqual(len(output["data"]["catalog"]), 3)
        self.assertEqual(output["data"]["catalog"][1]["parent_type"], "通用采购合同")

    def test_type_names_are_deduplicated(self):
        output = CLIENT.normalize(RAW_LIST, "list-types", [])
        self.assertEqual(output["data"]["types"], ["通用采购合同", "API/AI采购合同"])

    def test_legacy_catalog_object_remains_supported(self):
        wrapped = {"status": 0, "result": {"catalog": RAW_LIST["result"]}}
        output = CLIENT.normalize(wrapped, "list-catalog", [])
        self.assertEqual(output["data"]["catalog"][0]["name"], "通用采购合同")


if __name__ == "__main__":
    unittest.main()
