import json
from pathlib import Path

from fastapi.testclient import TestClient

from app import workflow
from app.main import app
from app.prompt_builder import PromptBuilder
from app.retrieval.base import BaseRunbookRetriever
from app.retrieval.hybrid_runbook_retriever import HybridRunbookRetriever
from app.retrieval.keyword_runbook_retriever import KeywordRunbookRetriever
from app.retrieval.models import RunbookChunk
from app.retrieval.retrieval_service import RetrievalService
from app.schemas import DiagnosisRequest


client = TestClient(app)
RUNBOOK_DIR = Path(__file__).resolve().parents[1] / "runbooks"


class FakeLlmClient:
    def __init__(self, outputs=None, exception=None):
        self.outputs = list(outputs or [])
        self.exception = exception
        self.calls = 0
        self.models = []
        self.user_prompts = []

    def generate(self, system_prompt, user_prompt, model=None):
        self.calls += 1
        self.models.append(model)
        self.user_prompts.append(user_prompt)
        assert "Evidence Package" in user_prompt
        assert "Runbook Context" in user_prompt
        assert "strict JSON object" in system_prompt
        if self.exception:
            raise self.exception
        if self.outputs:
            return self.outputs.pop(0)
        return ""


class RecordingRunbookRetriever(BaseRunbookRetriever):
    def __init__(self, chunks=None, exception=None):
        self.chunks = chunks if chunks is not None else [mq_chunk()]
        self.exception = exception
        self.calls = 0

    def retrieve(self, request, trace_summary, top_k=3):
        self.calls += 1
        if self.exception:
            raise self.exception
        return self.chunks


class RecordingRetrievalService:
    def __init__(self, chunks=None, exception=None):
        self.chunks = chunks if chunks is not None else [mq_chunk()]
        self.exception = exception
        self.calls = 0

    def retrieve_runbooks(self, request, trace_summary, top_k=3):
        self.calls += 1
        if self.exception:
            raise self.exception
        return self.chunks


def build_request(rule_result=None, metrics=None, roots=None, scenario_code="MQ_BACKLOG"):
    payload = {
        "experiment": {
            "experimentId": "exp_xxx",
            "scenarioCode": scenario_code,
            "status": "RUNNING",
            "traceId": "trace_xxx",
        },
        "metrics": metrics if metrics is not None else [
            {
                "metricName": "publishCount",
                "metricValue": "10",
                "metricUnit": "count",
                "component": "RabbitMQ",
            }
        ],
        "traceTree": {
            "traceId": "trace_xxx",
            "roots": roots if roots is not None else [],
        },
    }
    if rule_result is not None:
        payload["ruleResult"] = rule_result
    return payload


def matched_rule_result(evidence=None, fault_type="MQ_BACKLOG"):
    names = {
        "MQ_BACKLOG": "MQ backlog",
        "THREAD_POOL_SATURATION": "Thread pool saturation",
        "IDEMPOTENCY_CONFLICT": "Idempotency conflict",
    }
    default_evidence = {
        "MQ_BACKLOG": ["publishCount=10", "consumeCount=1", "avgConsumeMs=1500"],
        "THREAD_POOL_SATURATION": ["activeThreadCount=16", "queueSize=20", "rejectedTaskCount=2"],
        "IDEMPOTENCY_CONFLICT": ["duplicateCount=3", "hashMismatchCount=1", "redisSetNxFailCount=3"],
    }
    return {
        "experimentId": "exp_xxx",
        "faultType": fault_type,
        "faultName": names.get(fault_type, fault_type),
        "confidence": 0.85,
        "matched": True,
        "reason": "Matched rule evidence for " + fault_type,
        "evidence": evidence if evidence is not None else default_evidence.get(fault_type, []),
        "suggestions": ["Follow the runbook and verify metrics."],
    }


def llm_response(confidence=0.86, runbook_references=None):
    return {
        "experimentId": "exp_xxx",
        "faultType": "MQ_BACKLOG",
        "faultName": "MQ backlog",
        "confidence": confidence,
        "summary": "LLM diagnosed MQ backlog from evidence.",
        "phenomenon": ["consumeCount is lower than publishCount"],
        "evidence": ["publishCount=10", "consumeCount=1"],
        "rootCauses": ["Consumer processing is slower than publishing."],
        "suggestions": ["Increase consumer capacity."],
        "runbookReferences": runbook_references if runbook_references is not None else [],
        "fallback": False,
    }


def mq_chunk():
    return RunbookChunk(
        docId="mq-backlog",
        title="MQ backlog runbook",
        faultType="MQ_BACKLOG",
        section="Core Metrics",
        content="Check publishCount, consumeCount, backlogCount, and avgConsumeMs.",
        keywords=["RabbitMQ", "publishCount", "consumeCount", "backlogCount"],
        score=9.0,
    )


def enable_llm(monkeypatch, fake_client):
    monkeypatch.setattr(workflow.settings, "llm_enabled", True)
    monkeypatch.setattr(workflow.settings, "dashscope_api_key", "test-key")
    monkeypatch.setattr(workflow.settings, "llm_max_retries", 0)
    monkeypatch.setattr(workflow, "LlmClient", lambda: fake_client)


def disable_llm(monkeypatch):
    monkeypatch.setattr(workflow.settings, "llm_enabled", False)
    monkeypatch.setattr(workflow.settings, "dashscope_api_key", "test-key")


def clear_api_key(monkeypatch):
    monkeypatch.setattr(workflow.settings, "llm_enabled", True)
    monkeypatch.setattr(workflow.settings, "dashscope_api_key", "")


def to_request(payload):
    return DiagnosisRequest.model_validate(payload)


def test_health_returns_up():
    response = client.get("/ai/health")

    assert response.status_code == 200
    assert response.json() == {
        "service": "faultlab-ai-service",
        "status": "UP",
    }


def test_generate_diagnosis_returns_fallback_when_llm_disabled(monkeypatch):
    disable_llm(monkeypatch)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    assert response.json()["fallback"] is True


def test_generate_diagnosis_does_not_call_llm_when_api_key_empty(monkeypatch):
    clear_api_key(monkeypatch)
    fake_client = FakeLlmClient(outputs=[json.dumps(llm_response())])
    monkeypatch.setattr(workflow, "LlmClient", lambda: fake_client)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    assert response.json()["fallback"] is True
    assert fake_client.calls == 0


def test_generate_diagnosis_with_valid_llm_json_returns_non_fallback(monkeypatch):
    fake_client = FakeLlmClient(outputs=[json.dumps(llm_response())])
    enable_llm(monkeypatch, fake_client)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["fallback"] is False
    assert body["summary"] == "LLM diagnosed MQ backlog from evidence."
    assert body["rootCauses"] == ["Consumer processing is slower than publishing."]
    assert fake_client.calls == 1


def test_generate_diagnosis_parses_markdown_json_code_block(monkeypatch):
    raw = "```json\n" + json.dumps(llm_response()) + "\n```"
    fake_client = FakeLlmClient(outputs=[raw])
    enable_llm(monkeypatch, fake_client)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    assert response.json()["fallback"] is False


def test_generate_diagnosis_fallbacks_when_llm_returns_invalid_json(monkeypatch):
    fake_client = FakeLlmClient(outputs=["not json"])
    enable_llm(monkeypatch, fake_client)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["fallback"] is True
    assert body["evidence"] == ["publishCount=10", "consumeCount=1", "avgConsumeMs=1500"]
    assert fake_client.calls == 1


def test_generate_diagnosis_fallbacks_when_llm_raises(monkeypatch):
    fake_client = FakeLlmClient(exception=RuntimeError("timeout"))
    enable_llm(monkeypatch, fake_client)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    assert response.json()["fallback"] is True


def test_generate_diagnosis_clamps_confidence(monkeypatch):
    fake_client = FakeLlmClient(outputs=[json.dumps(llm_response(confidence=1.8))])
    enable_llm(monkeypatch, fake_client)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["fallback"] is False
    assert body["confidence"] == 1.0


def test_fallback_report_keeps_rule_evidence_and_suggestions(monkeypatch):
    disable_llm(monkeypatch)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["fallback"] is True
    assert body["evidence"] == ["publishCount=10", "consumeCount=1", "avgConsumeMs=1500"]
    assert body["suggestions"] == ["Follow the runbook and verify metrics."]


def test_generate_diagnosis_accepts_empty_metrics_and_trace_roots(monkeypatch):
    disable_llm(monkeypatch)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result(), metrics=[], roots=[]),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["experimentId"] == "exp_xxx"
    assert body["fallback"] is True


def test_workflow_passes_model_router_selection_to_llm(monkeypatch):
    fake_client = FakeLlmClient(outputs=[json.dumps(llm_response())])
    enable_llm(monkeypatch, fake_client)
    metrics = [
        {
            "metricName": f"metric_{index}",
            "metricValue": str(index),
            "metricUnit": "count",
            "component": "Test",
        }
        for index in range(20)
    ]

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result(), metrics=metrics),
    )

    assert response.status_code == 200
    assert response.json()["fallback"] is False
    assert fake_client.models == [workflow.settings.llm_long_context_model]


def test_base_runbook_retriever_interface_exists():
    assert hasattr(BaseRunbookRetriever, "retrieve")


def test_keyword_runbook_retriever_retrieves_mq_backlog_runbook():
    retriever = KeywordRunbookRetriever(RUNBOOK_DIR)
    request = to_request(build_request(rule_result=matched_rule_result(["publishCount", "avgConsumeMs"])))

    chunks = retriever.retrieve(request, workflow.build_trace_summary(request))

    assert chunks
    assert all(chunk.faultType == "MQ_BACKLOG" for chunk in chunks)
    assert chunks[0].docId == "mq-backlog"


def test_keyword_runbook_retriever_retrieves_thread_pool_runbook():
    retriever = KeywordRunbookRetriever(RUNBOOK_DIR)
    request = to_request(build_request(
        rule_result=matched_rule_result(fault_type="THREAD_POOL_SATURATION"),
        scenario_code="THREAD_POOL_SATURATION",
        metrics=[{"metricName": "activeThreadCount", "metricValue": "16", "metricUnit": "count", "component": "Executor"}],
    ))

    chunks = retriever.retrieve(request, workflow.build_trace_summary(request))

    assert chunks
    assert all(chunk.faultType == "THREAD_POOL_SATURATION" for chunk in chunks)
    assert chunks[0].docId == "thread-pool-saturation"


def test_keyword_runbook_retriever_retrieves_idempotency_runbook():
    retriever = KeywordRunbookRetriever(RUNBOOK_DIR)
    request = to_request(build_request(
        rule_result=matched_rule_result(fault_type="IDEMPOTENCY_CONFLICT"),
        scenario_code="IDEMPOTENCY_CONFLICT",
        metrics=[{"metricName": "hashMismatchCount", "metricValue": "1", "metricUnit": "count", "component": "Redis"}],
    ))

    chunks = retriever.retrieve(request, workflow.build_trace_summary(request))

    assert chunks
    assert all(chunk.faultType == "IDEMPOTENCY_CONFLICT" for chunk in chunks)
    assert chunks[0].docId == "idempotency-conflict"


def test_keyword_runbook_retriever_does_not_return_unmatched_fault_type():
    retriever = KeywordRunbookRetriever(RUNBOOK_DIR)
    request = to_request(build_request(
        rule_result=matched_rule_result(
            evidence=["publishCount=10", "consumeCount=1"],
            fault_type="THREAD_POOL_SATURATION",
        ),
        scenario_code="THREAD_POOL_SATURATION",
    ))

    chunks = retriever.retrieve(request, workflow.build_trace_summary(request))

    assert all(chunk.docId != "mq-backlog" for chunk in chunks)


def test_keyword_runbook_retriever_missing_directory_returns_empty(tmp_path):
    retriever = KeywordRunbookRetriever(tmp_path / "missing-runbooks")
    request = to_request(build_request(rule_result=matched_rule_result()))

    chunks = retriever.retrieve(request, workflow.build_trace_summary(request))

    assert chunks == []


def test_retrieval_service_uses_hybrid_runbook_retriever_by_default():
    service = RetrievalService()

    assert isinstance(service.primary_retriever, HybridRunbookRetriever)
    assert isinstance(service.fallback_retriever, KeywordRunbookRetriever)


def test_retrieval_service_returns_empty_when_retriever_raises():
    service = RetrievalService(
        primary_retriever=RecordingRunbookRetriever(exception=RuntimeError("retrieval failed")),
        fallback_retriever=RecordingRunbookRetriever(chunks=[]),
    )
    request = to_request(build_request(rule_result=matched_rule_result()))

    chunks = service.retrieve_runbooks(request, workflow.build_trace_summary(request))

    assert chunks == []


def test_retrieval_service_prefers_primary_retriever():
    primary = RecordingRunbookRetriever(chunks=[mq_chunk()])
    fallback = RecordingRunbookRetriever(chunks=[
        RunbookChunk(
            docId="fallback",
            title="Fallback",
            faultType="MQ_BACKLOG",
            section="Fallback",
            content="Fallback",
            keywords=[],
        )
    ])
    service = RetrievalService(primary_retriever=primary, fallback_retriever=fallback)
    request = to_request(build_request(rule_result=matched_rule_result()))

    chunks = service.retrieve_runbooks(request, workflow.build_trace_summary(request))

    assert chunks == [mq_chunk()]
    assert primary.calls == 1
    assert fallback.calls == 0


def test_retrieval_service_fallbacks_when_primary_returns_empty():
    primary = RecordingRunbookRetriever(chunks=[])
    fallback = RecordingRunbookRetriever(chunks=[mq_chunk()])
    service = RetrievalService(primary_retriever=primary, fallback_retriever=fallback)
    request = to_request(build_request(rule_result=matched_rule_result()))

    chunks = service.retrieve_runbooks(request, workflow.build_trace_summary(request))

    assert chunks == [mq_chunk()]
    assert primary.calls == 1
    assert fallback.calls == 1


def test_prompt_builder_injects_runbook_context():
    request = to_request(build_request(rule_result=matched_rule_result()))

    _, user_prompt = PromptBuilder().build(request, workflow.build_trace_summary(request), [mq_chunk()])

    assert "Runbook Context" in user_prompt
    assert "mq-backlog" in user_prompt
    assert "Core Metrics" in user_prompt


def test_workflow_retrieves_runbook_before_llm(monkeypatch):
    fake_client = FakeLlmClient(outputs=[json.dumps(llm_response())])
    retrieval_service = RecordingRetrievalService()
    enable_llm(monkeypatch, fake_client)

    response = workflow.run_diagnosis_workflow(
        to_request(build_request(rule_result=matched_rule_result())),
        retrieval_service=retrieval_service,
    )

    assert response.fallback is False
    assert retrieval_service.calls == 1
    assert fake_client.calls == 1
    assert "mq-backlog" in fake_client.user_prompts[0]


def test_llm_valid_runbook_reference_is_retained(monkeypatch):
    references = [{"docId": "mq-backlog", "title": "fabricated title", "section": "Core Metrics"}]
    fake_client = FakeLlmClient(outputs=[json.dumps(llm_response(runbook_references=references))])
    retrieval_service = RecordingRetrievalService(chunks=[mq_chunk()])
    enable_llm(monkeypatch, fake_client)

    response = workflow.run_diagnosis_workflow(
        to_request(build_request(rule_result=matched_rule_result())),
        retrieval_service=retrieval_service,
    )

    assert response.fallback is False
    assert len(response.runbook_references) == 1
    assert response.runbook_references[0].doc_id == "mq-backlog"
    assert response.runbook_references[0].title == "MQ backlog runbook"
    assert response.runbook_references[0].section == "Core Metrics"


def test_llm_invalid_runbook_reference_is_filtered(monkeypatch):
    references = [{"docId": "missing-doc", "title": "Missing", "section": "Nope"}]
    fake_client = FakeLlmClient(outputs=[json.dumps(llm_response(runbook_references=references))])
    retrieval_service = RecordingRetrievalService(chunks=[mq_chunk()])
    enable_llm(monkeypatch, fake_client)

    response = workflow.run_diagnosis_workflow(
        to_request(build_request(rule_result=matched_rule_result())),
        retrieval_service=retrieval_service,
    )

    assert response.fallback is False
    assert response.runbook_references == []


def test_empty_runbook_context_still_allows_llm_diagnosis(monkeypatch):
    fake_client = FakeLlmClient(outputs=[json.dumps(llm_response())])
    retrieval_service = RecordingRetrievalService(chunks=[])
    enable_llm(monkeypatch, fake_client)

    response = workflow.run_diagnosis_workflow(
        to_request(build_request(rule_result=matched_rule_result())),
        retrieval_service=retrieval_service,
    )

    assert response.fallback is False
    assert response.runbook_references == []
    assert "Runbook Context:\n[]" in fake_client.user_prompts[0]


def test_runbook_retrieval_failure_does_not_cause_500(monkeypatch):
    fake_client = FakeLlmClient(outputs=[json.dumps(llm_response())])
    retrieval_service = RetrievalService(
        primary_retriever=RecordingRunbookRetriever(exception=RuntimeError("runbook read failed")),
        fallback_retriever=RecordingRunbookRetriever(chunks=[mq_chunk()]),
    )
    enable_llm(monkeypatch, fake_client)

    response = workflow.run_diagnosis_workflow(
        to_request(build_request(rule_result=matched_rule_result())),
        retrieval_service=retrieval_service,
    )

    assert response.fallback is False
    assert fake_client.calls == 1
