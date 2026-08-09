from typing import Any

from app.embedding_client import EmbeddingClient
from app.retrieval.base import BaseRunbookRetriever
from app.retrieval.milvus_store import MilvusStore
from app.retrieval.models import RunbookChunk
from app.retrieval.query_builder import build_retrieval_query, request_fault_type
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
        return build_retrieval_query(request, trace_summary)

    def request_fault_type(self, request: DiagnosisRequest) -> str:
        return request_fault_type(request)
