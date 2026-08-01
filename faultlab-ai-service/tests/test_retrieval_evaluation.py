import importlib.util
from pathlib import Path

from fastapi.testclient import TestClient

from app import main
from app.evaluation.models import RetrievalEvalCase
from app.evaluation.retrieval_evaluator import RetrievalEvaluator
from app.main import app
from app.retrieval.keyword_runbook_retriever import KeywordRunbookRetriever
from app.retrieval.models import RunbookChunk

client = TestClient(app)
CASES_PATH = Path(__file__).resolve().parents[1] / "evaluation" / "rag_eval_cases.json"
RUNBOOK_DIR = Path(__file__).resolve().parents[1] / "runbooks"


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


def test_can_load_rag_eval_cases_json():
    cases = RetrievalEvaluator(CASES_PATH).load_cases()

    assert len(cases) >= 9
    assert cases[0].case_id


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


def test_runbook_evaluate_api_defaults_to_hybrid(monkeypatch):
    calls = []

    def fake_evaluate(retriever="hybrid", top_k=3):
        calls.append((retriever, top_k))
        return {"retrieverName": retriever, "caseCount": 9}

    monkeypatch.setattr(main, "evaluate_retrievers", fake_evaluate)

    response = client.post("/ai/runbooks/evaluate")

    assert response.status_code == 200
    assert calls == [("hybrid", 3)]
    assert response.json()["retrieverName"] == "hybrid"


def test_runbook_evaluate_api_bm25_returns_summary(monkeypatch):
    monkeypatch.setattr(
        main,
        "evaluate_retrievers",
        lambda retriever, top_k: {"retrieverName": retriever, "caseCount": 9},
    )

    response = client.post("/ai/runbooks/evaluate", json={"retriever": "bm25", "topK": 3})

    assert response.status_code == 200
    assert response.json()["retrieverName"] == "bm25"


def test_runbook_evaluate_api_all_returns_multiple_summaries(monkeypatch):
    monkeypatch.setattr(
        main,
        "evaluate_retrievers",
        lambda retriever, top_k: {
            "summaries": [
                {"retrieverName": "bm25", "caseCount": 9},
                {"retrieverName": "hybrid", "caseCount": 9},
            ]
        },
    )

    response = client.post("/ai/runbooks/evaluate", json={"retriever": "all", "topK": 3})

    assert response.status_code == 200
    assert len(response.json()["summaries"]) == 2


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
