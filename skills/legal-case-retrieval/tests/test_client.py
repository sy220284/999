import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "client.py"
SPEC = importlib.util.spec_from_file_location("case_client", SCRIPT)
CLIENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT)


class CaseClientTest(unittest.TestCase):
    def test_authoritative_uses_v2_names(self):
        request, _ = CLIENT.build_call({
            "operation": "keyword",
            "query": "买卖合同 逾期付款",
            "top_k": 10,
            "filters": {
                "authoritative_only": True,
                "case_category": "civil",
                "causes": ["买卖合同纠纷"],
                "document_types": ["judgment"],
                "authority_sources": ["典型案例"],
            },
        })
        self.assertEqual(request["params"]["name"], "yuandian_rh_qwal_search__v2")
        arguments = request["params"]["arguments"]
        self.assertEqual(arguments["keywords"], "买卖合同 逾期付款")
        self.assertEqual(arguments["case_category"], "民事案件")
        self.assertEqual(arguments["document_type"], ["判决书"])
        self.assertEqual(arguments["authoritative_case_sources"], ["典型案例"])

    def test_semantic_proxy_envelope_reads_extra_wenshu(self):
        raw = {
            "status": 0,
            "result": {
                "isError": False,
                "structuredContent": {
                    "ok": True,
                    "tool": "yuandian_case_vector_search",
                    "data": {"extra": {"wenshu": [{"scid": "s1", "title": "案例一", "ah": "（2026）测1号"}]}},
                },
            },
            "rid": "trace-1",
        }
        output = CLIENT.normalize(raw, {"operation": "semantic"})
        self.assertEqual(output["status"], "ok")
        self.assertEqual(output["records"][0]["record_id"], "s1")

    def test_authoritative_direct_result_is_normalized(self):
        raw = {
            "status": 0,
            "result": {
                "isError": False,
                "structuredContent": {
                    "success": True,
                    "status": "success",
                    "data": {"lst": [{
                        "case_id": "q1",
                        "title": "典型案例",
                        "case_number": "（2026）测2号",
                        "court_name": "测试法院",
                        "decision_date": "2026年01月02日",
                        "cause_of_action": ["买卖合同纠纷"],
                        "case_category": "民事案件",
                        "document_type": "判决书",
                        "case_kind": "典型案例",
                        "content": "摘要",
                    }]},
                },
            },
            "rid": "trace-2",
        }
        output = CLIENT.normalize(raw, {"operation": "keyword"})
        record = output["records"][0]
        self.assertEqual(record["record_id"], "q1")
        self.assertEqual(record["court"], "测试法院")
        self.assertEqual(record["authority_labels"], ["典型案例"])

    def test_invalid_top_k_is_rejected(self):
        with self.assertRaises(CLIENT.ContractError):
            CLIENT.build_call({"operation": "keyword", "query": "测试", "top_k": 51})

    def test_semantic_multiple_provinces_are_rejected(self):
        with self.assertRaises(CLIENT.ContractError):
            CLIENT.build_call({"operation": "semantic", "query": "测试", "filters": {"provinces": ["北京", "上海"]}})

    def test_semantic_court_level_is_mapped(self):
        request, _ = CLIENT.build_call({"operation": "semantic", "query": "测试", "filters": {"court_level": "high"}})
        self.assertEqual(request["params"]["arguments"]["wenshu_filter"]["cj"], "高级")

    def test_unsupported_document_type_is_rejected(self):
        with self.assertRaises(CLIENT.ContractError):
            CLIENT.build_call({"operation": "keyword", "query": "测试", "filters": {"document_types": ["notice"]}})


if __name__ == "__main__":
    unittest.main()
