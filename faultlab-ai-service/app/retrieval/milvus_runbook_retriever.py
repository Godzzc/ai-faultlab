from typing import Any

from app.embedding_client import EmbeddingClient
from app.retrieval.base import BaseRunbookRetriever
from app.retrieval.milvus_store import MilvusStore
from app.retrieval.models import RunbookChunk
from app.schemas import DiagnosisRequest


class MilvusRunbookRetriever(BaseRunbookRetriever):
    def __init__(
        self,
        embedding_client: EmbeddingClient | None = None,
        milvus_store: MilvusStore | None = None,
    ) -> None:
        self.embedding_client = embedding_client
        self.milvus_store = milvus_store

    def retrieve(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
        top_k: int = 3,
    ) -> list[RunbookChunk]:
        query = self.build_query(request, trace_summary)
        embedding_client = self.embedding_client or EmbeddingClient()
        milvus_store = self.milvus_store or MilvusStore()
        embedding = embedding_client.embed_text(query)
        return milvus_store.search(
            embedding,
            fault_type=self.request_fault_type(request),
            top_k=top_k,
        )

    def build_query(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
    ) -> str:
        parts: list[str] = []
        if request.rule_result:
            parts.extend([
                request.rule_result.fault_type,
                request.rule_result.fault_name,
                request.rule_result.reason,
                " ".join(request.rule_result.evidence or []),
                " ".join(request.rule_result.suggestions or []),
            ])

        metric_parts = [
            f"{metric.metric_name}={metric.metric_value}{metric.metric_unit} component={metric.component}"
            for metric in request.metrics or []
        ]
        parts.append(" ".join(metric_parts))
        parts.append(f"scenarioCode={request.experiment.scenario_code}")
        parts.append(f"trace nodeCount={trace_summary.get('nodeCount', 0)}")
        parts.append(f"slowSpans={trace_summary.get('slowSpans', [])}")
        parts.append(f"errorSpans={trace_summary.get('errorSpans', [])}")
        return "\n".join(part for part in parts if part)

    def request_fault_type(self, request: DiagnosisRequest) -> str:
        if request.rule_result and request.rule_result.fault_type:
            return request.rule_result.fault_type
        return request.experiment.scenario_code or ""
