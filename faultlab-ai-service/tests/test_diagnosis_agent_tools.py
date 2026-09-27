import json

from fastapi.testclient import TestClient

from app import workflow
from app.agent.models import AgentToolCallStatus, DiagnosisAgentContext
from app.agent.orchestrator import DiagnosisAgentOrchestrator
from app.agent.run_store import AgentRunStore
from app.agent.state import DiagnosisAgentState
from app.agent.tool_registry import AgentToolRegistry, AgentToolRegistryError
from app.agent.tools.base import AgentTool
from app.agent.tools.evidence_tool import EvidenceCollectionTool
from app.agent.tools.models import AgentToolResult
from app.agent.tools.report_tool import DiagnosisReportGeneratorTool
from app.agent.tools.retrieval_tool import RunbookRetrievalTool
from app.agent.tools.validation_tool import RunbookReferenceValidationTool
from app.main import app
from app.retrieval.models import RunbookChunk
from app.schemas import DiagnosisRequest


client = TestClient(app)


class DummyTool(AgentTool):
    @property
    def name(self) -> str:
        return "dummy"

    @property
    def description(self) -> str:
        return "Dummy tool"

    def execute(self, context):
        return AgentToolResult.ok(self.name)


class ExplodingTool(AgentTool):
    @property
    def name(self) -> str:
        return "exploding"

    @property
    def description(self) -> str:
        return "Exploding tool"

    def execute(self, context):
        raise RuntimeError("tool exploded")


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


class FakeLlmClient:
    def __init__(self, output=None, exception=None):
        self.output = output
        self.exception = exception
        self.calls = 0

    def generate(self, system_prompt, user_prompt, model=None):
        self.calls += 1
        if self.exception:
            raise self.exception
        return self.output or json.dumps({
            "experimentId": "exp_tool",
            "faultType": "MQ_BACKLOG",
            "faultName": "MQ backlog",
            "confidence": 0.86,
            "summary": "LLM diagnosed MQ backlog from evidence.",
            "phenomenon": ["consumeCount is lower than publishCount"],
            "evidence": ["publishCount=10", "consumeCount=1"],
            "rootCauses": ["Consumer processing is slower than publishing."],
            "suggestions": ["Increase consumer capacity."],
            "runbookReferences": [],
            "fallback": False,
        })


def build_request(request_id="tool-request", include_rule=True):
    payload = {
        "requestId": request_id,
        "experiment": {
            "experimentId": "exp_tool",
            "scenarioCode": "MQ_BACKLOG",
            "status": "RUNNING",
            "traceId": "trace_tool",
        },
        "metrics": [
            {
                "metricName": "publishCount",
                "metricValue": "10",
                "metricUnit": "count",
                "component": "RabbitMQ",
            }
        ],
        "traceTree": {"traceId": "trace_tool", "roots": []},
    }
    if include_rule:
        payload["ruleResult"] = {
            "experimentId": "exp_tool",
            "faultType": "MQ_BACKLOG",
            "faultName": "MQ backlog",
            "confidence": 0.85,
            "matched": True,
            "reason": "Backlog detected",
            "evidence": ["publishCount=10", "consumeCount=1"],
            "suggestions": ["Increase consumer capacity."],
        }
    return DiagnosisRequest.model_validate(payload)


def mq_chunk(metadata=None, source="local_markdown"):
    return RunbookChunk(
        docId="mq-backlog",
        title="MQ backlog runbook",
        faultType="MQ_BACKLOG",
        section="Core Metrics",
        content="Check publishCount and consumeCount.",
        keywords=["publishCount", "consumeCount"],
        score=9.0,
        source=source,
        metadata=metadata or {},
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


def enable_llm(monkeypatch):
    monkeypatch.setattr(workflow.settings, "llm_enabled", True)
    monkeypatch.setattr(workflow.settings, "dashscope_api_key", "test-key")
    monkeypatch.setattr(workflow.settings, "llm_max_retries", 0)


def test_agent_tool_result_success_structure():
    result = AgentToolResult.ok("example", data={"value": 1}, warnings=["warn"], metadata={"m": True})

    assert result.tool_name == "example"
    assert result.success is True
    assert result.data == {"value": 1}
    assert result.warnings == ["warn"]
    assert result.metadata == {"m": True}


def test_agent_tool_result_failure_structure():
    result = AgentToolResult.fail("example", "TOOL_FAILED", "failed")

    assert result.success is False
    assert result.error_code == "TOOL_FAILED"
    assert result.error_message == "failed"


def test_tool_registry_registers_tool():
    registry = AgentToolRegistry()
    registry.register(DummyTool())

    assert registry.get("dummy").name == "dummy"
    assert [tool.name for tool in registry.list_tools()] == ["dummy"]


def test_tool_registry_rejects_duplicate_registration():
    registry = AgentToolRegistry()
    registry.register(DummyTool())

    try:
        registry.register(DummyTool())
    except AgentToolRegistryError as exc:
        assert "already registered" in str(exc)
    else:
        raise AssertionError("duplicate tool registration should fail")


def test_tool_registry_missing_tool_fails():
    registry = AgentToolRegistry()

    try:
        registry.get("missing")
    except AgentToolRegistryError as exc:
        assert "not found" in str(exc)
    else:
        raise AssertionError("missing tool lookup should fail")


def test_evidence_collection_tool_reads_evidence_package():
    context = DiagnosisAgentContext.from_request(build_request())

    result = EvidenceCollectionTool().execute(context)

    assert result.success is True
    assert result.data["experimentId"] == "exp_tool"
    assert result.data["faultType"] == "MQ_BACKLOG"
    assert result.data["metricCount"] == 1
    assert result.data["traceAvailable"] is True
    assert result.data["ruleDiagnosisAvailable"] is True


def test_evidence_collection_tool_summary_does_not_copy_sensitive_or_full_payload():
    context = DiagnosisAgentContext.from_request(build_request())
    orchestrator = DiagnosisAgentOrchestrator(run_store=AgentRunStore())
    registry = AgentToolRegistry()
    registry.register(EvidenceCollectionTool())

    orchestrator.execute_tool("evidence_collection", context, DiagnosisAgentState.COLLECT_EVIDENCE, registry)

    record = context.tool_calls[0]
    assert "evidencePackage" not in record.input_summary
    assert "metrics" not in record.input_summary
    assert record.input_summary["metricCount"] == 1


def test_runbook_retrieval_tool_returns_chunks():
    context = DiagnosisAgentContext.from_request(build_request())
    context.trace_summary = workflow.build_trace_summary(context.evidence_package)

    result = RunbookRetrievalTool(RecordingRetrievalService()).execute(context)

    assert result.success is True
    assert result.data["resultCount"] == 1
    assert result.data["retrievedChunks"][0].docId == "mq-backlog"


def test_retrieval_vector_unavailable_fallback_warning():
    context = DiagnosisAgentContext.from_request(build_request())
    context.trace_summary = workflow.build_trace_summary(context.evidence_package)

    result = RunbookRetrievalTool(RecordingRetrievalService(chunks=[])).execute(context)

    assert result.success is True
    assert "runbook retrieval returned no chunks" in result.warnings
    assert result.metadata["fallback"] is True


def test_reference_validation_tool_accepts_valid_runbook():
    context = DiagnosisAgentContext.from_request(build_request())
    context.retrieved_chunks = [mq_chunk(metadata={"path": "mq-backlog.md"})]

    result = RunbookReferenceValidationTool(StaticRunbookLoader([mq_chunk()])).execute(context)

    assert result.success is True
    assert result.data["validatedCount"] == 1


def test_reference_validation_tool_rejects_missing_section():
    context = DiagnosisAgentContext.from_request(build_request())
    context.retrieved_chunks = [invalid_chunk()]

    result = RunbookReferenceValidationTool(StaticRunbookLoader([mq_chunk()])).execute(context)

    assert result.success is False
    assert result.error_code == "REFERENCE_VALIDATION_FAILED"


def test_report_generator_tool_reuses_current_report_generation(monkeypatch):
    enable_llm(monkeypatch)
    context = DiagnosisAgentContext.from_request(build_request())
    context.trace_summary = workflow.build_trace_summary(context.evidence_package)
    context.retrieved_chunks = [mq_chunk()]

    result = DiagnosisReportGeneratorTool(llm_client=FakeLlmClient()).execute(context)

    assert result.success is True
    assert result.data["report"].fallback is False
    assert result.data["report"].summary == "LLM diagnosed MQ backlog from evidence."


def test_tool_execution_generates_tool_call_record():
    context = DiagnosisAgentContext.from_request(build_request())
    orchestrator = DiagnosisAgentOrchestrator(run_store=AgentRunStore())
    registry = AgentToolRegistry()
    registry.register(EvidenceCollectionTool())

    orchestrator.execute_tool("evidence_collection", context, DiagnosisAgentState.COLLECT_EVIDENCE, registry)

    assert len(context.tool_calls) == 1
    assert context.tool_calls[0].tool_name == "evidence_collection"


def test_tool_call_record_has_started_finished_duration():
    context = DiagnosisAgentContext.from_request(build_request())
    orchestrator = DiagnosisAgentOrchestrator(run_store=AgentRunStore())
    registry = AgentToolRegistry()
    registry.register(EvidenceCollectionTool())

    orchestrator.execute_tool("evidence_collection", context, DiagnosisAgentState.COLLECT_EVIDENCE, registry)
    record = context.tool_calls[0]

    assert record.started_at is not None
    assert record.finished_at is not None
    assert record.duration_ms is not None


def test_tool_success_status_is_recorded():
    context = DiagnosisAgentContext.from_request(build_request())
    orchestrator = DiagnosisAgentOrchestrator(run_store=AgentRunStore())
    registry = AgentToolRegistry()
    registry.register(EvidenceCollectionTool())

    orchestrator.execute_tool("evidence_collection", context, DiagnosisAgentState.COLLECT_EVIDENCE, registry)

    assert context.tool_calls[0].status == AgentToolCallStatus.SUCCESS


def test_tool_failure_status_is_recorded():
    context = DiagnosisAgentContext.from_request(build_request())
    orchestrator = DiagnosisAgentOrchestrator(run_store=AgentRunStore())
    registry = AgentToolRegistry()
    registry.register(ExplodingTool())

    result = orchestrator.execute_tool(
        "exploding",
        context,
        DiagnosisAgentState.COLLECT_EVIDENCE,
        registry,
        fail_fast=False,
    )

    assert result.success is False
    assert context.tool_calls[0].status == AgentToolCallStatus.FAILED
    assert context.tool_calls[0].error_code == "TOOL_EXECUTION_FAILED"


def test_tool_exception_does_not_crash_fastapi_process(monkeypatch):
    disable_llm(monkeypatch)
    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(request_id="tool-exception-compatible").model_dump(by_alias=True),
    )

    assert response.status_code == 200


def test_orchestrator_executes_expected_tools(monkeypatch):
    disable_llm(monkeypatch)
    store = AgentRunStore()
    orchestrator = DiagnosisAgentOrchestrator(
        run_store=store,
        runbook_chunk_loader=StaticRunbookLoader([mq_chunk()]),
    )

    orchestrator.run(build_request(request_id="tool-order"), retrieval_service=RecordingRetrievalService())
    run = store.get("tool-order")

    assert run is not None
    assert [call.tool_name for call in run.tool_calls] == [
        "evidence_collection",
        "runbook_retrieval",
        "reference_validation",
        "diagnosis_report_generator",
        "remediation_planning",
    ]


def test_agent_complete_flow_reaches_completed(monkeypatch):
    disable_llm(monkeypatch)
    store = AgentRunStore()
    orchestrator = DiagnosisAgentOrchestrator(
        run_store=store,
        runbook_chunk_loader=StaticRunbookLoader([mq_chunk()]),
    )

    orchestrator.run(build_request(request_id="tool-completed"), retrieval_service=RecordingRetrievalService())
    run = store.get("tool-completed")

    assert run is not None
    assert run.current_state == DiagnosisAgentState.COMPLETED
    assert run.tool_summary.total_calls == 5
    assert run.tool_summary.success_calls == 5


def test_debug_api_returns_tool_calls(monkeypatch):
    disable_llm(monkeypatch)

    post_response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(request_id="tool-debug-api").model_dump(by_alias=True),
    )
    debug_response = client.get("/ai/diagnosis/agent-runs/tool-debug-api")

    assert post_response.status_code == 200
    assert debug_response.status_code == 200
    body = debug_response.json()
    assert body["toolCalls"]
    assert body["toolSummary"]["totalCalls"] >= 1
