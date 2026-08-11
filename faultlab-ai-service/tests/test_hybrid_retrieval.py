import json
from pathlib import Path

from app import workflow
from app.retrieval.bm25_runbook_retriever import Bm25RunbookRetriever, QuerySignals
from app.retrieval.fusion import reciprocal_rank_fusion
from app.retrieval.hybrid_runbook_retriever import HybridRunbookRetriever
from app.retrieval.models import RunbookChunk
from app.retrieval.reranker import LightweightRunbookReranker
from app.retrieval.retrieval_service import RetrievalService
from app.schemas import DiagnosisRequest

RUNBOOK_DIR = Path(__file__).resolve().parents[1] / "runbooks"


def build_request(fault_type="MQ_BACKLOG", metric_name="publishCount", evidence=None):
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
    return DiagnosisRequest.model_validate({
        "experiment": {
            "experimentId": "exp_xxx",
            "scenarioCode": fault_type,
            "status": "RUNNING",
            "traceId": "trace_xxx",
        },
        "metrics": [
            {
                "metricName": metric_name,
                "metricValue": "10",
                "metricUnit": "count",
                "component": "RabbitMQ",
            }
        ],
        "traceTree": {
            "traceId": "trace_xxx",
            "roots": [],
        },
        "ruleResult": {
            "experimentId": "exp_xxx",
            "faultType": fault_type,
            "faultName": names[fault_type],
            "confidence": 0.85,
            "matched": True,
            "reason": "Matched rule evidence for " + fault_type,
            "evidence": evidence if evidence is not None else default_evidence[fault_type],
            "suggestions": ["Follow runbook."],
        },
    })


def chunk(
    doc_id="mq-backlog",
    section="Core Metrics",
    fault_type="MQ_BACKLOG",
    score=1.0,
    source="milvus",
    metadata=None,
    content=None,
    keywords=None,
):
    return RunbookChunk(
        docId=doc_id,
        title=f"{doc_id} runbook",
        faultType=fault_type,
        section=section,
        content=content or "publishCount consumeCount avgConsumeMs activeThreadCount queueSize hashMismatchCount",
        keywords=keywords if keywords is not None else [
            "publishCount",
            "consumeCount",
            "activeThreadCount",
            "hashMismatchCount",
        ],
        score=score,
        source=source,
        metadata=metadata or {},
    )


class RecordingRetriever:
    def __init__(self, chunks=None, exception=None):
        self.chunks = chunks if chunks is not None else []
        self.exception = exception
        self.calls = 0
        self.top_k_values = []

    def retrieve(self, request, trace_summary, top_k=3):
        self.calls += 1
        self.top_k_values.append(top_k)
        if self.exception:
            raise self.exception
        return self.chunks


class FakeLlmClient:
    def generate(self, system_prompt, user_prompt, model=None):
        return json.dumps({
            "experimentId": "exp_xxx",
            "faultType": "MQ_BACKLOG",
            "faultName": "MQ backlog",
            "confidence": 0.88,
            "summary": "Hybrid retrieval context was used.",
            "phenomenon": [],
            "evidence": [],
            "rootCauses": [],
            "suggestions": [],
            "runbookReferences": [{"docId": "mq-backlog", "title": "ignored", "section": "Core Metrics"}],
            "fallback": False,
        })


def test_bm25_runbook_retriever_retrieves_mq_backlog_runbook():
    request = build_request("MQ_BACKLOG", "publishCount")
    chunks = Bm25RunbookRetriever(RUNBOOK_DIR).retrieve(request, workflow.build_trace_summary(request))

    assert chunks
    assert chunks[0].docId == "mq-backlog"
    assert all(item.faultType == "MQ_BACKLOG" for item in chunks)


def test_bm25_runbook_retriever_retrieves_thread_pool_runbook():
    request = build_request("THREAD_POOL_SATURATION", "activeThreadCount")
    chunks = Bm25RunbookRetriever(RUNBOOK_DIR).retrieve(request, workflow.build_trace_summary(request))

    assert chunks
    assert chunks[0].docId == "thread-pool-saturation"
    assert all(item.faultType == "THREAD_POOL_SATURATION" for item in chunks)


def test_bm25_runbook_retriever_retrieves_idempotency_runbook():
    request = build_request("IDEMPOTENCY_CONFLICT", "hashMismatchCount")
    chunks = Bm25RunbookRetriever(RUNBOOK_DIR).retrieve(request, workflow.build_trace_summary(request))

    assert chunks
    assert chunks[0].docId == "idempotency-conflict"
    assert all(item.faultType == "IDEMPOTENCY_CONFLICT" for item in chunks)


def test_bm25_runbook_retriever_does_not_return_unmatched_fault_type():
    request = build_request("THREAD_POOL_SATURATION", "publishCount", ["publishCount=10", "consumeCount=1"])
    chunks = Bm25RunbookRetriever(RUNBOOK_DIR).retrieve(request, workflow.build_trace_summary(request))

    assert all(item.faultType == "THREAD_POOL_SATURATION" for item in chunks)
    assert all(item.docId != "mq-backlog" for item in chunks)


def test_bm25_scoring_boosts_fault_type_match():
    retriever = Bm25RunbookRetriever(RUNBOOK_DIR)
    signals = QuerySignals(terms=["publishcount"], metric_names=set(), evidence_keys=set(), section_intents={})
    matching = chunk(fault_type="MQ_BACKLOG", content="publishCount", score=0)
    mismatched = chunk(fault_type="THREAD_POOL_SATURATION", content="publishCount", score=0)
    document_frequency = {"publishcount": 2}

    matching_score = retriever._with_bm25_like_score(
        matching, ["publishcount"], signals, document_frequency, 2, 1, "MQ_BACKLOG"
    ).score
    mismatched_score = retriever._with_bm25_like_score(
        mismatched, ["publishcount"], signals, document_frequency, 2, 1, "MQ_BACKLOG"
    ).score

    assert matching_score > mismatched_score


def test_bm25_section_title_hit_can_lift_ranking(monkeypatch):
    retriever = Bm25RunbookRetriever(RUNBOOK_DIR)
    request = build_request("MQ_BACKLOG", "publishCount", ["publishCount=10"])
    title_hit = chunk(section="publishCount", content="generic", keywords=[], source="local_markdown")
    content_hit = chunk(section="Other", content="publishCount", keywords=[], source="local_markdown")
    monkeypatch.setattr(retriever, "_load_chunks", lambda: [content_hit, title_hit])

    results = retriever.retrieve(request, workflow.build_trace_summary(request), top_k=2)

    assert results[0].section == "publishCount"


def test_bm25_keyword_hit_can_lift_ranking(monkeypatch):
    retriever = Bm25RunbookRetriever(RUNBOOK_DIR)
    request = build_request("MQ_BACKLOG", "publishCount", ["publishCount=10"])
    keyword_hit = chunk(section="Keyword", content="generic", keywords=["publishCount"], source="local_markdown")
    content_hit = chunk(section="Content", content="publishCount", keywords=[], source="local_markdown")
    monkeypatch.setattr(retriever, "_load_chunks", lambda: [content_hit, keyword_hit])

    results = retriever.retrieve(request, workflow.build_trace_summary(request), top_k=2)

    assert results[0].section == "Keyword"


def test_bm25_metric_name_exact_hit_can_lift_ranking(monkeypatch):
    retriever = Bm25RunbookRetriever(RUNBOOK_DIR)
    request = build_request("THREAD_POOL_SATURATION", "activeThreadCount", ["activeThreadCount=16"])
    metric_hit = chunk(
        doc_id="thread-pool-saturation",
        section="Metric",
        fault_type="THREAD_POOL_SATURATION",
        content="activeThreadCount",
        keywords=[],
        source="local_markdown",
    )
    generic = chunk(
        doc_id="thread-pool-saturation",
        section="Generic",
        fault_type="THREAD_POOL_SATURATION",
        content="executor saturation",
        keywords=[],
        source="local_markdown",
    )
    monkeypatch.setattr(retriever, "_load_chunks", lambda: [generic, metric_hit])

    results = retriever.retrieve(request, workflow.build_trace_summary(request), top_k=2)

    assert results[0].section == "Metric"


def test_bm25_length_normalization_prevents_long_section_from_always_winning(monkeypatch):
    retriever = Bm25RunbookRetriever(RUNBOOK_DIR)
    request = build_request("MQ_BACKLOG", "publishCount", ["publishCount=10"])
    long_chunk = chunk(section="Long", content=" ".join(["publishCount"] * 200), keywords=[], source="local_markdown")
    short_keyword_chunk = chunk(section="Short", content="publishCount", keywords=["publishCount"], source="local_markdown")
    monkeypatch.setattr(retriever, "_load_chunks", lambda: [long_chunk, short_keyword_chunk])

    results = retriever.retrieve(request, workflow.build_trace_summary(request), top_k=2)

    assert results[0].section == "Short"


def test_rrf_fuses_vector_and_bm25_results():
    fused = reciprocal_rank_fusion([
        [chunk(section="Core Metrics", source="milvus")],
        [chunk(section="Fix", source="bm25")],
    ])

    assert len(fused) == 2
    assert all(item.metadata["fusionScore"] > 0 for item in fused)


def test_rrf_deduplicates_by_doc_id_and_section():
    fused = reciprocal_rank_fusion([
        [chunk(section="Core Metrics", source="milvus")],
        [chunk(section="Core Metrics", source="bm25")],
    ])

    assert len(fused) == 1
    assert fused[0].docId == "mq-backlog"
    assert fused[0].section == "Core Metrics"


def test_rrf_boosts_chunk_that_appears_in_both_result_lists():
    shared_vector = chunk(section="Core Metrics", source="milvus")
    shared_bm25 = chunk(section="Core Metrics", source="bm25")
    vector_only = chunk(section="Fix", source="milvus")

    fused = reciprocal_rank_fusion([[shared_vector, vector_only], [shared_bm25]])

    assert fused[0].section == "Core Metrics"
    assert set(fused[0].metadata["sources"]) == {"bm25", "milvus"}
    assert "vectorScore" in fused[0].metadata
    assert "bm25Score" in fused[0].metadata
    assert fused[0].score > fused[1].score


def test_lightweight_reranker_boosts_fault_type_match():
    request = build_request("MQ_BACKLOG", "publishCount")
    matching = chunk(section="A", fault_type="MQ_BACKLOG", score=1.0)
    mismatched = chunk(doc_id="thread", section="B", fault_type="THREAD_POOL_SATURATION", score=1.5)

    reranked = LightweightRunbookReranker().rerank([mismatched, matching], request, {}, top_k=2)

    assert reranked[0].faultType == "MQ_BACKLOG"


def test_lightweight_reranker_boosts_evidence_metric_hits():
    request = build_request("MQ_BACKLOG", "publishCount", ["avgConsumeMs=1500"])
    hit = chunk(section="A", score=1.0)
    miss = RunbookChunk(
        docId="mq-backlog",
        title="MQ",
        faultType="MQ_BACKLOG",
        section="B",
        content="no matching metric",
        keywords=[],
        score=1.0,
    )

    reranked = LightweightRunbookReranker().rerank([miss, hit], request, {}, top_k=2)

    assert reranked[0].section == "A"


def test_lightweight_reranker_boosts_dual_source_chunk():
    request = build_request("MQ_BACKLOG", "publishCount")
    dual = chunk(section="A", score=1.0, metadata={"sources": ["milvus", "bm25"]})
    single = chunk(section="B", score=1.5, metadata={"sources": ["milvus"]})

    reranked = LightweightRunbookReranker().rerank([single, dual], request, {}, top_k=2)

    assert reranked[0].section == "A"


def test_lightweight_reranker_boosts_root_cause_section_for_reason_query():
    request = build_request("MQ_BACKLOG", "avgConsumeMs", ["avgConsumeMs=1500"])
    request.rule_result.reason = "root cause is slow consumer logic"
    cause = chunk(section="常见原因", score=1.0)
    metrics = chunk(section="核心指标", score=1.0)

    reranked = LightweightRunbookReranker().rerank([metrics, cause], request, {}, top_k=2)

    assert reranked[0].section == "常见原因"


def test_lightweight_reranker_boosts_fix_section_for_suggestion_query():
    request = build_request("MQ_BACKLOG", "backlogCount", ["backlogCount=10"])
    request.rule_result.suggestions = ["increase consumer concurrency"]
    fix = chunk(section="修复建议", score=1.0)
    cause = chunk(section="常见原因", score=1.0)

    reranked = LightweightRunbookReranker().rerank([cause, fix], request, {}, top_k=2)

    assert reranked[0].section == "修复建议"


def test_lightweight_reranker_boosts_core_metrics_section_for_metric_query():
    request = build_request("THREAD_POOL_SATURATION", "rejectedTaskCount", ["rejectedTaskCount=2"])
    request.rule_result.reason = "metric evidence shows rejection"
    metrics = chunk(section="核心指标", fault_type="THREAD_POOL_SATURATION", score=1.0)
    cause = chunk(section="常见原因", fault_type="THREAD_POOL_SATURATION", score=1.0)

    reranked = LightweightRunbookReranker().rerank([cause, metrics], request, {}, top_k=2)

    assert reranked[0].section == "核心指标"


def test_hybrid_runbook_retriever_calls_vector_and_bm25():
    vector = RecordingRetriever([chunk(source="milvus")])
    bm25 = RecordingRetriever([chunk(section="Fix", source="bm25")])
    request = build_request()

    results = HybridRunbookRetriever(vector_retriever=vector, bm25_retriever=bm25).retrieve(
        request,
        workflow.build_trace_summary(request),
        top_k=3,
    )

    assert vector.calls == 1
    assert bm25.calls == 1
    assert results


def test_hybrid_runbook_retriever_returns_bm25_when_vector_fails():
    vector = RecordingRetriever(exception=RuntimeError("milvus down"))
    bm25 = RecordingRetriever([chunk(source="bm25")])
    request = build_request()

    results = HybridRunbookRetriever(vector_retriever=vector, bm25_retriever=bm25).retrieve(request, {}, top_k=3)

    assert vector.calls == 1
    assert bm25.calls == 1
    assert results[0].metadata["sources"] == ["bm25"]


def test_hybrid_runbook_retriever_returns_vector_when_bm25_fails():
    vector = RecordingRetriever([chunk(source="milvus")])
    bm25 = RecordingRetriever(exception=RuntimeError("bm25 failed"))
    request = build_request()

    results = HybridRunbookRetriever(vector_retriever=vector, bm25_retriever=bm25).retrieve(request, {}, top_k=3)

    assert vector.calls == 1
    assert bm25.calls == 1
    assert results[0].metadata["sources"] == ["milvus"]


def test_hybrid_runbook_retriever_returns_empty_when_both_fail():
    vector = RecordingRetriever(exception=RuntimeError("milvus down"))
    bm25 = RecordingRetriever(exception=RuntimeError("bm25 failed"))
    request = build_request()

    results = HybridRunbookRetriever(vector_retriever=vector, bm25_retriever=bm25).retrieve(request, {}, top_k=3)

    assert results == []


def test_retrieval_service_defaults_to_hybrid_retriever():
    service = RetrievalService()

    assert isinstance(service.primary_retriever, HybridRunbookRetriever)


def test_workflow_generates_diagnosis_with_hybrid_retrieval(monkeypatch):
    request = build_request()
    retriever = HybridRunbookRetriever(
        vector_retriever=RecordingRetriever([chunk(source="milvus")]),
        bm25_retriever=RecordingRetriever([chunk(source="bm25")]),
    )
    service = RetrievalService(primary_retriever=retriever)
    monkeypatch.setattr(workflow.settings, "llm_enabled", True)
    monkeypatch.setattr(workflow.settings, "dashscope_api_key", "test-key")
    monkeypatch.setattr(workflow, "LlmClient", lambda: FakeLlmClient())

    response = workflow.run_diagnosis_workflow(request, retrieval_service=service)

    assert response.fallback is False
    assert response.summary == "Hybrid retrieval context was used."


def test_runbook_reference_validation_ignores_retrieval_sources():
    request = build_request()
    response = workflow.validate_runbook_references(
        workflow.parse_and_validate_json(
            json.dumps({
                "experimentId": "exp_xxx",
                "faultType": "MQ_BACKLOG",
                "faultName": "MQ backlog",
                "confidence": 0.8,
                "summary": "ok",
                "phenomenon": [],
                "evidence": [],
                "rootCauses": [],
                "suggestions": [],
                "runbookReferences": [{"docId": "mq-backlog", "title": "x", "section": "Core Metrics"}],
                "fallback": False,
            }),
            request,
        ),
        [chunk(section="Core Metrics", source="hybrid", metadata={"sources": ["milvus", "bm25"]})],
    )

    assert len(response.runbook_references) == 1
    assert response.runbook_references[0].doc_id == "mq-backlog"
