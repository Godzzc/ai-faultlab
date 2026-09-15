from fastapi.testclient import TestClient

from app import workflow
from app.agent.models import AgentStageStatus, DiagnosisAgentContext
from app.agent.orchestrator import DiagnosisAgentOrchestrator
from app.agent.run_store import AgentRunStore
from app.agent.state import DiagnosisAgentState
from app.agent.state_machine import DiagnosisAgentStateMachine, InvalidAgentStateTransition
from app.main import app
from app.retrieval.models import RunbookChunk
from app.schemas import DiagnosisRequest


client = TestClient(app)


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


class StaticRunbookLoader:
    def __init__(self, chunks):
        self.chunks = chunks

    def _load_chunks(self):
        return self.chunks


def build_request(request_id="", experiment_id="exp_agent"):
    return DiagnosisRequest.model_validate({
        "requestId": request_id,
        "experiment": {
            "experimentId": experiment_id,
            "scenarioCode": "MQ_BACKLOG",
            "status": "RUNNING",
            "traceId": "trace_agent",
        },
        "metrics": [
            {
                "metricName": "publishCount",
                "metricValue": "10",
                "metricUnit": "count",
                "component": "RabbitMQ",
            }
        ],
        "traceTree": {"traceId": "trace_agent", "roots": []},
        "ruleResult": {
            "experimentId": experiment_id,
            "faultType": "MQ_BACKLOG",
            "faultName": "MQ backlog",
            "confidence": 0.85,
            "matched": True,
            "reason": "Backlog detected",
            "evidence": ["publishCount=10", "consumeCount=1"],
            "suggestions": ["Increase consumer capacity."],
        },
    })


def mq_chunk():
    return RunbookChunk(
        docId="mq-backlog",
        title="MQ backlog runbook",
        faultType="MQ_BACKLOG",
        section="Core Metrics",
        content="Check publishCount and consumeCount.",
        keywords=["publishCount", "consumeCount"],
        score=9.0,
    )


def invalid_chunk():
    return RunbookChunk(
        docId="missing-doc",
        title="Missing",
        faultType="MQ_BACKLOG",
        section="Missing Section",
        content="Invalid",
        keywords=[],
        score=1.0,
        metadata={"path": "missing.md"},
    )


def disable_llm(monkeypatch):
    monkeypatch.setattr(workflow.settings, "llm_enabled", False)
    monkeypatch.setattr(workflow.settings, "dashscope_api_key", "test-key")


def test_initial_state_is_received():
    context = DiagnosisAgentContext.from_request(build_request())

    assert context.current_state == DiagnosisAgentState.RECEIVED


def test_received_can_transition_to_collect_evidence():
    context = DiagnosisAgentContext.from_request(build_request())
    machine = DiagnosisAgentStateMachine(context)

    machine.transition_to(DiagnosisAgentState.COLLECT_EVIDENCE)

    assert context.current_state == DiagnosisAgentState.COLLECT_EVIDENCE


def test_normal_full_state_flow_reaches_completed():
    context = DiagnosisAgentContext.from_request(build_request())
    machine = DiagnosisAgentStateMachine(context)

    machine.run_stage(DiagnosisAgentState.COLLECT_EVIDENCE, lambda: None)
    machine.run_stage(DiagnosisAgentState.ANALYZE, lambda: None)
    machine.run_stage(DiagnosisAgentState.RETRIEVE, lambda: None)
    machine.run_stage(DiagnosisAgentState.VALIDATE, lambda: None)
    machine.run_stage(DiagnosisAgentState.GENERATE_REPORT, lambda: None)
    machine.complete()

    assert context.current_state == DiagnosisAgentState.COMPLETED


def test_illegal_state_transition_is_rejected():
    context = DiagnosisAgentContext.from_request(build_request())
    machine = DiagnosisAgentStateMachine(context)

    try:
        machine.transition_to(DiagnosisAgentState.ANALYZE)
    except InvalidAgentStateTransition as exc:
        assert "RECEIVED -> ANALYZE" in str(exc)
    else:
        raise AssertionError("illegal transition should fail")


def test_stage_records_started_finished_and_duration():
    context = DiagnosisAgentContext.from_request(build_request())
    machine = DiagnosisAgentStateMachine(context)

    machine.run_stage(DiagnosisAgentState.COLLECT_EVIDENCE, lambda: "ok")

    record = context.stage_records[0]
    assert record.started_at is not None
    assert record.finished_at is not None
    assert record.duration_ms is not None


def test_stage_success_status_is_recorded():
    context = DiagnosisAgentContext.from_request(build_request())
    machine = DiagnosisAgentStateMachine(context)

    machine.run_stage(DiagnosisAgentState.COLLECT_EVIDENCE, lambda: None)

    assert context.stage_records[0].status == AgentStageStatus.SUCCESS


def test_handler_exception_moves_state_to_failed():
    context = DiagnosisAgentContext.from_request(build_request())
    machine = DiagnosisAgentStateMachine(context)

    try:
        machine.run_stage(DiagnosisAgentState.COLLECT_EVIDENCE, lambda: (_ for _ in ()).throw(RuntimeError("boom")))
    except Exception:
        pass

    assert context.current_state == DiagnosisAgentState.FAILED
    assert context.stage_records[0].status == AgentStageStatus.FAILED


def test_failed_state_cannot_continue_to_business_stage():
    context = DiagnosisAgentContext.from_request(build_request())
    machine = DiagnosisAgentStateMachine(context)
    machine.transition_to(DiagnosisAgentState.FAILED)

    try:
        machine.run_stage(DiagnosisAgentState.RETRIEVE, lambda: None)
    except InvalidAgentStateTransition:
        pass
    else:
        raise AssertionError("FAILED state should be terminal")


def test_request_id_is_generated_when_missing():
    context = DiagnosisAgentContext.from_request(build_request(request_id=""))

    assert context.request_id


def test_experiment_id_enters_context():
    context = DiagnosisAgentContext.from_request(build_request(experiment_id="exp_from_request"))

    assert context.experiment_id == "exp_from_request"


def test_retrieval_success_enters_validate(monkeypatch):
    disable_llm(monkeypatch)
    store = AgentRunStore()
    orchestrator = DiagnosisAgentOrchestrator(
        run_store=store,
        runbook_chunk_loader=StaticRunbookLoader([mq_chunk()]),
    )

    orchestrator.run(build_request(request_id="agent-retrieve-success"), retrieval_service=RecordingRetrievalService())
    run = store.get("agent-retrieve-success")

    assert run is not None
    assert DiagnosisAgentState.VALIDATE in [record.state for record in run.stage_records]


def test_retrieval_failure_uses_fallback_path(monkeypatch):
    disable_llm(monkeypatch)
    store = AgentRunStore()
    orchestrator = DiagnosisAgentOrchestrator(run_store=store)

    response = orchestrator.run(
        build_request(request_id="agent-retrieve-failure"),
        retrieval_service=RecordingRetrievalService(exception=RuntimeError("disk unavailable")),
    )
    run = store.get("agent-retrieve-failure")

    assert response.fallback is True
    assert run is not None
    assert run.current_state == DiagnosisAgentState.COMPLETED
    assert run.fallback_reason == "runbook retrieval unavailable"
    assert DiagnosisAgentState.FALLBACK in [record.state for record in run.stage_records]


def test_validate_rejects_missing_runbook_reference(monkeypatch):
    disable_llm(monkeypatch)
    store = AgentRunStore()
    orchestrator = DiagnosisAgentOrchestrator(
        run_store=store,
        runbook_chunk_loader=StaticRunbookLoader([mq_chunk()]),
    )

    response = orchestrator.run(
        build_request(request_id="agent-invalid-reference"),
        retrieval_service=RecordingRetrievalService(chunks=[invalid_chunk()]),
    )
    run = store.get("agent-invalid-reference")

    assert response.fallback is True
    assert run is not None
    assert run.current_state == DiagnosisAgentState.FAILED
    assert run.error_code == "REFERENCE_VALIDATION_FAILED"


def test_report_generation_success_completes(monkeypatch):
    disable_llm(monkeypatch)
    store = AgentRunStore()
    orchestrator = DiagnosisAgentOrchestrator(
        run_store=store,
        runbook_chunk_loader=StaticRunbookLoader([mq_chunk()]),
    )

    response = orchestrator.run(
        build_request(request_id="agent-report-success"),
        retrieval_service=RecordingRetrievalService(),
    )
    run = store.get("agent-report-success")

    assert response.fallback is True
    assert run is not None
    assert run.current_state == DiagnosisAgentState.COMPLETED


def test_original_generate_endpoint_still_returns_report(monkeypatch):
    disable_llm(monkeypatch)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(request_id="agent-api-compatible").model_dump(by_alias=True),
    )

    assert response.status_code == 200
    assert response.json()["fallback"] is True


def test_agent_run_store_save_get():
    store = AgentRunStore()
    context = DiagnosisAgentContext.from_request(build_request(request_id="store-one"))

    store.save(context)

    assert store.get("store-one") is not None


def test_agent_run_store_keeps_latest_100():
    store = AgentRunStore(max_size=100)

    for index in range(101):
        store.save(DiagnosisAgentContext.from_request(build_request(request_id=f"store-{index}")))

    assert store.get("store-0") is None
    assert store.get("store-100") is not None
    assert len(store.list()) == 100


def test_debug_api_reads_agent_run(monkeypatch):
    disable_llm(monkeypatch)

    post_response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(request_id="agent-debug-api").model_dump(by_alias=True),
    )
    debug_response = client.get("/ai/diagnosis/agent-runs/agent-debug-api")

    assert post_response.status_code == 200
    assert debug_response.status_code == 200
    body = debug_response.json()
    assert body["requestId"] == "agent-debug-api"
    assert body["currentState"] in {"COMPLETED", "FAILED"}
    assert body["stageRecords"]
