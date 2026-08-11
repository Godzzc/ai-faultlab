import importlib.util
import sys
from collections import Counter
from pathlib import Path

from fastapi.testclient import TestClient

from app import main
from app.evaluation.models import RetrievalEvalCase, RetrievalEvalSummary
from app.evaluation.report_generator import RetrievalEvaluationReportGenerator
from app.evaluation.retrieval_evaluator import RetrievalEvaluator
from app.main import app
from app.retrieval.bm25_runbook_retriever import Bm25RunbookRetriever
from app.retrieval.keyword_runbook_retriever import KeywordRunbookRetriever
from app.retrieval.models import RunbookChunk

client = TestClient(app)
CASES_PATH = Path(__file__).resolve().parents[1] / "evaluation" / "rag_eval_cases.json"
RUNBOOK_DIR = Path(__file__).resolve().parents[1] / "runbooks"
FIXED_CACHE_SECTIONS = {"现象", "核心指标", "常见原因", "排查步骤", "修复建议", "风险提示"}
NEW_CACHE_CASE_IDS = {
    "cache_penetration_invalid_key",
    "cache_penetration_null_cache",
    "cache_penetration_bloom_filter",
    "cache_penetration_db_pressure",
    "cache_penetration_risk_control",
    "cache_breakdown_hot_key_expired",
    "cache_breakdown_rebuild_storm",
    "cache_breakdown_mutex_lock",
    "cache_breakdown_logical_expire",
    "cache_breakdown_singleflight",
    "cache_avalanche_same_ttl",
    "cache_avalanche_redis_unavailable",
    "cache_avalanche_ttl_jitter",
    "cache_avalanche_fallback",
    "cache_avalanche_db_spike",
}


class MockRetriever:
    def __init__(self, chunks=None, exception=None):
        self.chunks = chunks if chunks is not None else []
        self.exception = exception
        self.calls = 0

    def retrieve(self, request, trace_summary, top_k=3):
        self.calls += 1
        if self.exception:
            raise self.exception
        return self.chunks[:top_k]


def chunk(doc_id="mq-backlog", section="核心指标", score=1.0):
    return RunbookChunk(
        docId=doc_id,
        title=f"{doc_id} runbook",
        faultType="MQ_BACKLOG",
        section=section,
        content="content",
        keywords=[],
        score=score,
    )


def eval_case(expected=None):
    return RetrievalEvalCase.model_validate({
        "caseId": "case_a",
        "scenarioCode": "MQ_BACKLOG",
        "query": {
            "ruleResult": {
                "faultType": "MQ_BACKLOG",
                "faultName": "MQ 消息堆积",
                "reason": "publishCount greater than consumeCount",
                "evidence": ["publishCount=10", "consumeCount=1"],
            },
            "metrics": [
                {
                    "metricName": "publishCount",
                    "metricValue": "10",
                    "component": "RabbitMQ",
                }
            ],
        },
        "expected": expected if expected is not None else [
            {"docId": "mq-backlog", "section": "核心指标"}
        ],
    })


def summary(name="bm25", hit=True, recall=1.0, reciprocal_rank=1.0, case_id="mq_backlog_core_metrics"):
    return RetrievalEvalSummary.model_validate({
        "retrieverName": name,
        "caseCount": 1,
        "hitAtK": 1.0 if hit else 0.0,
        "recallAtK": recall,
        "mrr": reciprocal_rank,
        "results": [
            {
                "caseId": case_id,
                "retrieverName": name,
                "topK": 3,
                "hit": hit,
                "reciprocalRank": reciprocal_rank,
                "recall": recall,
                "expected": [{"docId": "mq-backlog", "section": "鏍稿績鎸囨爣"}],
                "retrieved": [
                    {
                        "docId": "mq-backlog" if hit else "thread-pool-saturation",
                        "section": "鏍稿績鎸囨爣",
                        "score": 0.9,
                    }
                ],
            }
        ],
    })


def test_can_load_rag_eval_cases_json():
    cases = RetrievalEvaluator(CASES_PATH).load_cases()

    assert len(cases) >= 42
    assert cases[0].case_id


def test_eval_case_required_fields_and_unique_case_ids():
    cases = RetrievalEvaluator(CASES_PATH).load_cases()
    case_ids = [case.case_id for case in cases]

    assert len(case_ids) == len(set(case_ids))
    for case in cases:
        assert case.case_id
        assert case.scenario_code
        assert case.expected


def test_eval_case_fault_type_coverage_has_at_least_8_cases_each():
    cases = RetrievalEvaluator(CASES_PATH).load_cases()
    counts = Counter(case.scenario_code for case in cases)

    assert counts["MQ_BACKLOG"] >= 8
    assert counts["THREAD_POOL_SATURATION"] >= 8
    assert counts["IDEMPOTENCY_CONFLICT"] >= 8


def test_cache_eval_case_fault_type_coverage_has_at_least_5_cases_each():
    cases = RetrievalEvaluator(CASES_PATH).load_cases()
    counts = Counter(case.scenario_code for case in cases)

    assert counts["CACHE_PENETRATION"] >= 5
    assert counts["CACHE_BREAKDOWN"] >= 5
    assert counts["CACHE_AVALANCHE"] >= 5


def test_new_cache_eval_cases_exist():
    cases = RetrievalEvaluator(CASES_PATH).load_cases()
    case_ids = {case.case_id for case in cases}

    assert NEW_CACHE_CASE_IDS <= case_ids


def test_eval_case_expected_doc_id_and_section_are_not_empty():
    cases = RetrievalEvaluator(CASES_PATH).load_cases()

    for case in cases:
        assert case.expected
        for expected in case.expected:
            assert expected.doc_id
            assert expected.section


def test_eval_case_expected_refs_exist_in_runbooks():
    chunks = KeywordRunbookRetriever(RUNBOOK_DIR)._load_chunks()
    existing_refs = {(chunk.docId, chunk.section) for chunk in chunks}

    for case in RetrievalEvaluator(CASES_PATH).load_cases():
        for expected in case.expected:
            assert (expected.doc_id, expected.section) in existing_refs


def test_retrieval_evaluator_calculates_hit_at_k():
    evaluator = RetrievalEvaluator(CASES_PATH)
    result = evaluator.evaluate_case(eval_case(), "mock", MockRetriever([chunk()]), top_k=3)

    assert result.hit is True


def test_retrieval_evaluator_calculates_recall_at_k():
    evaluator = RetrievalEvaluator(CASES_PATH)
    case = eval_case([
        {"docId": "mq-backlog", "section": "核心指标"},
        {"docId": "mq-backlog", "section": "常见原因"},
    ])

    result = evaluator.evaluate_case(case, "mock", MockRetriever([chunk()]), top_k=3)

    assert result.recall == 0.5


def test_retrieval_evaluator_calculates_mrr():
    evaluator = RetrievalEvaluator(CASES_PATH)
    result = evaluator.evaluate_case(eval_case(), "mock", MockRetriever([chunk()]), top_k=3)

    assert result.reciprocal_rank == 1.0


def test_mrr_is_1_when_first_result_hits():
    _, reciprocal_rank = RetrievalEvaluator(CASES_PATH).match_metrics(
        [chunk()],
        {("mq-backlog", "核心指标")},
    )

    assert reciprocal_rank == 1.0


def test_mrr_is_half_when_second_result_hits():
    _, reciprocal_rank = RetrievalEvaluator(CASES_PATH).match_metrics(
        [chunk(section="常见原因"), chunk()],
        {("mq-backlog", "核心指标")},
    )

    assert reciprocal_rank == 0.5


def test_mrr_is_zero_when_no_result_hits():
    _, reciprocal_rank = RetrievalEvaluator(CASES_PATH).match_metrics(
        [chunk(section="常见原因")],
        {("mq-backlog", "核心指标")},
    )

    assert reciprocal_rank == 0.0


def test_evaluation_runner_can_evaluate_mock_retriever(tmp_path):
    cases_path = tmp_path / "cases.json"
    cases_path.write_text(
        '[{"caseId":"case_a","scenarioCode":"MQ_BACKLOG","query":{},'
        '"expected":[{"docId":"mq-backlog","section":"核心指标"}]}]',
        encoding="utf-8",
    )

    summary = RetrievalEvaluator(cases_path).evaluate("mock", MockRetriever([chunk()]), top_k=3)

    assert summary.case_count == 1
    assert summary.hit_at_k == 1.0
    assert summary.recall_at_k == 1.0
    assert summary.mrr == 1.0


def test_bm25_evaluation_runs_with_real_runbooks():
    summary = RetrievalEvaluator(CASES_PATH).evaluate("bm25", Bm25RunbookRetriever(RUNBOOK_DIR), top_k=3)

    assert summary.case_count >= 42
    assert 0.0 <= summary.hit_at_k <= 1.0
    assert 0.0 <= summary.recall_at_k <= 1.0
    assert 0.0 <= summary.mrr <= 1.0


def test_cache_runbooks_can_be_loaded_with_expected_metadata_and_sections():
    chunks = KeywordRunbookRetriever(RUNBOOK_DIR)._load_chunks()
    by_doc_id = {}
    for chunk_item in chunks:
        by_doc_id.setdefault(chunk_item.docId, []).append(chunk_item)

    expected = {
        "cache-penetration": "CACHE_PENETRATION",
        "cache-breakdown": "CACHE_BREAKDOWN",
        "cache-avalanche": "CACHE_AVALANCHE",
    }
    for doc_id, fault_type in expected.items():
        assert doc_id in by_doc_id
        doc_chunks = by_doc_id[doc_id]
        assert {chunk_item.section for chunk_item in doc_chunks} >= FIXED_CACHE_SECTIONS
        assert {chunk_item.faultType for chunk_item in doc_chunks} == {fault_type}
        assert all(chunk_item.keywords for chunk_item in doc_chunks)


def test_report_generator_returns_markdown_string():
    markdown = RetrievalEvaluationReportGenerator().generate_markdown_report([summary()])

    assert isinstance(markdown, str)
    assert markdown


def test_report_generator_markdown_contains_required_sections():
    markdown = RetrievalEvaluationReportGenerator().generate_markdown_report([summary()])

    assert "# RAG Retrieval Evaluation Report" in markdown
    assert "## Overall Metrics" in markdown
    assert "## Retriever Comparison" in markdown
    assert "## Metrics By Fault Type" in markdown
    assert "## Miss Cases" in markdown
    assert "## Optimization Suggestions" in markdown


def test_report_generator_handles_single_retriever_summary():
    markdown = RetrievalEvaluationReportGenerator().generate_markdown_report([summary("bm25")])

    assert "Only one retriever summary was provided" in markdown
    assert "| bm25 | 1 |" in markdown


def test_report_generator_handles_multiple_retriever_summaries():
    markdown = RetrievalEvaluationReportGenerator().generate_markdown_report([
        summary("bm25", hit=True, recall=0.5, reciprocal_rank=0.5),
        summary("hybrid", hit=True, recall=1.0, reciprocal_rank=1.0),
    ])

    assert "Highest Hit@K" in markdown
    assert "hybrid" in markdown


def test_report_generator_infers_fault_type_from_case_id():
    generator = RetrievalEvaluationReportGenerator()

    assert generator.infer_fault_type("mq_backlog_core_metrics") == "MQ_BACKLOG"
    assert generator.infer_fault_type("thread_pool_rejection") == "THREAD_POOL_SATURATION"
    assert generator.infer_fault_type("idempotency_setnx_duplicate") == "IDEMPOTENCY_CONFLICT"
    assert generator.infer_fault_type("cache_penetration_invalid_key") == "CACHE_PENETRATION"
    assert generator.infer_fault_type("cache_breakdown_hot_key_expired") == "CACHE_BREAKDOWN"
    assert generator.infer_fault_type("cache_avalanche_same_ttl") == "CACHE_AVALANCHE"
    assert generator.infer_fault_type("unknown_case") == "UNKNOWN"


def test_report_generator_lists_hit_false_case_in_miss_cases():
    markdown = RetrievalEvaluationReportGenerator().generate_markdown_report([
        summary(hit=False, recall=0.0, reciprocal_rank=0.0)
    ])

    assert "mq_backlog_core_metrics" in markdown
    assert "Wrong document retrieved" in markdown


def test_report_generator_lists_partial_recall_case_in_miss_cases():
    markdown = RetrievalEvaluationReportGenerator().generate_markdown_report([
        summary(hit=True, recall=0.5, reciprocal_rank=1.0)
    ])

    assert "mq_backlog_core_metrics" in markdown
    assert "Partial recall" in markdown


def test_runbook_evaluate_api_defaults_to_hybrid(monkeypatch):
    calls = []

    def fake_evaluate(retriever="hybrid", top_k=3, report=False):
        calls.append((retriever, top_k, report))
        return {"retrieverName": retriever, "caseCount": 9}

    monkeypatch.setattr(main, "evaluate_retrievers", fake_evaluate)

    response = client.post("/ai/runbooks/evaluate")

    assert response.status_code == 200
    assert calls == [("hybrid", 3, False)]
    assert response.json()["retrieverName"] == "hybrid"


def test_runbook_evaluate_api_bm25_returns_summary(monkeypatch):
    monkeypatch.setattr(
        main,
        "evaluate_retrievers",
        lambda retriever, top_k, report=False: {"retrieverName": retriever, "caseCount": 9},
    )

    response = client.post("/ai/runbooks/evaluate", json={"retriever": "bm25", "topK": 3})

    assert response.status_code == 200
    assert response.json()["retrieverName"] == "bm25"


def test_runbook_evaluate_api_all_returns_multiple_summaries(monkeypatch):
    monkeypatch.setattr(
        main,
        "evaluate_retrievers",
        lambda retriever, top_k, report=False: {
            "summaries": [
                {"retrieverName": "bm25", "caseCount": 9},
                {"retrieverName": "hybrid", "caseCount": 9},
            ]
        },
    )

    response = client.post("/ai/runbooks/evaluate", json={"retriever": "all", "topK": 3})

    assert response.status_code == 200
    assert len(response.json()["summaries"]) == 2


def test_runbook_evaluate_api_report_true_returns_markdown_report(monkeypatch):
    monkeypatch.setattr(
        main,
        "evaluate_retrievers",
        lambda retriever, top_k, report=False: {
            "summaries": [{"retrieverName": retriever, "caseCount": 1}],
            "markdownReport": "# RAG Retrieval Evaluation Report" if report else "",
        },
    )

    response = client.post("/ai/runbooks/evaluate", json={"retriever": "all", "topK": 3, "report": True})

    assert response.status_code == 200
    assert response.json()["markdownReport"].startswith("# RAG Retrieval Evaluation Report")


def test_runbook_evaluate_api_report_false_keeps_response_compatible(monkeypatch):
    monkeypatch.setattr(
        main,
        "evaluate_retrievers",
        lambda retriever, top_k, report=False: {"retrieverName": retriever, "caseCount": 1},
    )

    response = client.post("/ai/runbooks/evaluate", json={"retriever": "bm25", "topK": 3, "report": False})

    assert response.status_code == 200
    assert "markdownReport" not in response.json()


def test_single_retriever_failure_does_not_affect_all(monkeypatch):
    class FakeEvaluator:
        def evaluate(self, retriever_name, retriever, top_k=3):
            if retriever_name == "milvus":
                raise RuntimeError("milvus unavailable")
            return type("Summary", (), {
                "model_dump": lambda self, by_alias=True: {
                    "retrieverName": retriever_name,
                    "caseCount": 1,
                }
            })()

    monkeypatch.setattr("app.evaluation.retrieval_evaluator.create_retriever", lambda name: object())

    from app.evaluation.retrieval_evaluator import evaluate_retrievers

    report = evaluate_retrievers("all", 3, evaluator=FakeEvaluator())

    summaries = report["summaries"]
    assert summaries[0]["retrieverName"] == "bm25"
    assert summaries[1]["retrieverName"] == "hybrid"
    assert summaries[2]["retrieverName"] == "milvus"
    assert "milvus unavailable" in summaries[2]["error"]


def test_cli_script_can_be_imported_without_running():
    script_path = Path(__file__).resolve().parents[1] / "scripts" / "evaluate_retrieval.py"
    spec = importlib.util.spec_from_file_location("evaluate_retrieval_script", script_path)
    module = importlib.util.module_from_spec(spec)

    spec.loader.exec_module(module)

    assert hasattr(module, "main")


def load_cli_module():
    script_path = Path(__file__).resolve().parents[1] / "scripts" / "evaluate_retrieval.py"
    spec = importlib.util.spec_from_file_location("evaluate_retrieval_script_for_args", script_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_cli_script_supports_report_argument(monkeypatch):
    module = load_cli_module()
    monkeypatch.setattr(sys, "argv", ["evaluate_retrieval.py", "--retriever", "bm25", "--report"])

    args = module.parse_args()

    assert args.report is True


def test_cli_script_supports_output_argument(monkeypatch, tmp_path):
    module = load_cli_module()
    output_path = tmp_path / "rag_eval_report.md"
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "evaluate_retrieval.py",
            "--retriever",
            "bm25",
            "--report",
            "--output",
            str(output_path),
        ],
    )
    monkeypatch.setattr(
        module,
        "evaluate_retrievers",
        lambda retriever, top_k: summary("bm25").model_dump(by_alias=True),
    )

    module.main()

    assert output_path.exists()
    assert "# RAG Retrieval Evaluation Report" in output_path.read_text(encoding="utf-8")
