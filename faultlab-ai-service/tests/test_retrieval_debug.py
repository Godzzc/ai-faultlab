import importlib.util
from pathlib import Path

from fastapi.testclient import TestClient

from app import main
from app.main import app
from app.retrieval.debug_models import RetrievalDebugRequest
from app.retrieval.debug_service import RetrievalDebugService
from app.retrieval.models import RunbookChunk
from app.retrieval.query_builder import build_retrieval_query
from app.schemas import DiagnosisRequest
from app.workflow import build_trace_summary

client = TestClient(app)
CASES_PATH = Path(__file__).resolve().parents[1] / "evaluation" / "rag_eval_cases.json"


class RecordingRetriever:
    def __init__(self, chunks=None, exception=None):
        self.chunks = chunks if chunks is not None else []
        self.exception = exception
        self.calls = 0

    def retrieve(self, request, trace_summary, top_k=3):
        self.calls += 1
        if self.exception:
            raise self.exception
        return self.chunks[:top_k]


def build_request(include_content=True):
    return RetrievalDebugRequest.model_validate({
        "experiment": {
            "experimentId": "exp_debug_mq_001",
            "scenarioCode": "MQ_BACKLOG",
            "status": "RUNNING",
            "traceId": "trace_debug_mq_001",
        },
        "metrics": [
            {
                "metricName": "publishCount",
                "metricValue": "10",
                "metricUnit": "count",
                "component": "RabbitMQ",
            },
            {
                "metricName": "consumeCount",
                "metricValue": "1",
                "metricUnit": "count",
                "component": "RabbitMQ",
            },
        ],
        "traceTree": {
            "traceId": "trace_debug_mq_001",
            "roots": [],
        },
        "ruleResult": {
            "experimentId": "exp_debug_mq_001",
            "faultType": "MQ_BACKLOG",
            "faultName": "MQ backlog",
            "confidence": 0.85,
            "matched": True,
            "reason": "publish count is higher than consume count",
            "evidence": ["publishCount=10", "consumeCount=1", "backlogCount=9"],
            "suggestions": ["increase consumers"],
        },
        "topK": 3,
        "includeContent": include_content,
    })


def chunk(
    doc_id="mq-backlog",
    section="Core Metrics",
    score=1.0,
    source="milvus",
    metadata=None,
):
    return RunbookChunk(
        docId=doc_id,
        title="MQ backlog runbook",
        faultType="MQ_BACKLOG",
        section=section,
        content="publishCount consumeCount backlogCount avgConsumeMs",
        keywords=["publishCount", "consumeCount", "backlogCount"],
        score=score,
        source=source,
        metadata=metadata or {"retrievalSource": source},
    )


def test_query_builder_constructs_query_from_mq_backlog_request():
    request = build_request()
    query_text = build_retrieval_query(request, build_trace_summary(request))

    assert query_text


def test_query_text_contains_fault_type():
    request = build_request()
    query_text = build_retrieval_query(request, build_trace_summary(request))

    assert "MQ_BACKLOG" in query_text


def test_query_text_contains_metric_name():
    request = build_request()
    query_text = build_retrieval_query(request, build_trace_summary(request))

    assert "publishCount" in query_text


def test_query_text_contains_rule_result_evidence():
    request = build_request()
    query_text = build_retrieval_query(request, build_trace_summary(request))

    assert "backlogCount=9" in query_text


def test_debug_service_returns_all_retrieval_stages():
    service = RetrievalDebugService(
        vector_retriever=RecordingRetriever([chunk(score=0.9, source="milvus")]),
        bm25_retriever=RecordingRetriever([chunk(section="Fix", score=0.8, source="bm25")]),
    )

    response = service.debug(build_request())

    assert response.vector_results
    assert response.bm25_results
    assert response.fusion_results
    assert response.rerank_results
    assert response.final_results


def test_debug_service_returns_bm25_and_warning_when_milvus_fails():
    service = RetrievalDebugService(
        vector_retriever=RecordingRetriever(exception=RuntimeError("milvus down")),
        bm25_retriever=RecordingRetriever([chunk(source="bm25")]),
    )

    response = service.debug(build_request())

    assert response.vector_results == []
    assert response.bm25_results
    assert response.final_results
    assert any("vector_exception" in item for item in response.warnings)


def test_debug_service_returns_vector_and_warning_when_bm25_fails():
    service = RetrievalDebugService(
        vector_retriever=RecordingRetriever([chunk(source="milvus")]),
        bm25_retriever=RecordingRetriever(exception=RuntimeError("bm25 failed")),
    )

    response = service.debug(build_request())

    assert response.vector_results
    assert response.bm25_results == []
    assert response.final_results
    assert any("bm25_exception" in item for item in response.warnings)


def test_debug_service_returns_empty_results_and_warnings_when_both_retrievers_fail():
    service = RetrievalDebugService(
        vector_retriever=RecordingRetriever(exception=RuntimeError("milvus down")),
        bm25_retriever=RecordingRetriever(exception=RuntimeError("bm25 failed")),
    )

    response = service.debug(build_request())

    assert response.vector_results == []
    assert response.bm25_results == []
    assert response.fusion_results == []
    assert response.rerank_results == []
    assert response.final_results == []
    assert any("vector_exception" in item for item in response.warnings)
    assert any("bm25_exception" in item for item in response.warnings)


def test_debug_service_omits_content_when_include_content_false():
    service = RetrievalDebugService(
        vector_retriever=RecordingRetriever([chunk(source="milvus")]),
        bm25_retriever=RecordingRetriever([chunk(source="bm25")]),
    )

    response = service.debug(build_request(include_content=False))

    assert response.final_results
    assert all(item.content == "" for item in response.final_results)


def test_runbook_retrieve_debug_api_returns_query_and_final_results(monkeypatch):
    class FakeDebugService:
        def debug(self, request):
            return RetrievalDebugService(
                vector_retriever=RecordingRetriever([chunk(source="milvus")]),
                bm25_retriever=RecordingRetriever([chunk(source="bm25")]),
            ).debug(request)

    monkeypatch.setattr(main, "RetrievalDebugService", FakeDebugService)

    response = client.post("/ai/runbooks/retrieve/debug", json=build_request().model_dump(by_alias=True))

    assert response.status_code == 200
    body = response.json()
    assert body["queryText"]
    assert body["finalResults"]


def load_cli_module():
    script_path = Path(__file__).resolve().parents[1] / "scripts" / "debug_retrieval.py"
    spec = importlib.util.spec_from_file_location("debug_retrieval_script", script_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_debug_retrieval_cli_can_be_imported_without_running():
    module = load_cli_module()

    assert hasattr(module, "main")


def test_debug_retrieval_cli_loads_case_by_id():
    module = load_cli_module()

    request = module.load_case_request(
        "mq_backlog_core_metrics",
        cases_path=CASES_PATH,
        top_k=2,
        include_content=False,
    )

    assert request.experiment.experiment_id == "mq_backlog_core_metrics"
    assert request.top_k == 2
    assert request.include_content is False
