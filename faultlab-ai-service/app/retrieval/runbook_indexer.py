import logging
from pathlib import Path

from app.embedding_client import EmbeddingClient
from app.retrieval.keyword_runbook_retriever import KeywordRunbookRetriever
from app.retrieval.milvus_store import MilvusStore
from app.retrieval.models import RunbookChunk

logger = logging.getLogger(__name__)


class RunbookIndexer:
    def __init__(
        self,
        runbooks_dir: Path | str | None = None,
        embedding_client: EmbeddingClient | None = None,
        milvus_store: MilvusStore | None = None,
        chunk_loader: KeywordRunbookRetriever | None = None,
    ) -> None:
        self.chunk_loader = chunk_loader or KeywordRunbookRetriever(runbooks_dir)
        self.embedding_client = embedding_client or EmbeddingClient()
        self.milvus_store = milvus_store or MilvusStore()

    def index_runbooks(self) -> int:
        chunks = self.load_chunks()
        indexed_chunks: list[RunbookChunk] = []
        embeddings: list[list[float]] = []

        for chunk in chunks:
            try:
                embedding = self.embedding_client.embed_text(self.chunk_text(chunk))
                indexed_chunks.append(chunk)
                embeddings.append(embedding)
            except Exception as exc:
                logger.warning(
                    "Skipping runbook chunk embedding failure docId=%s section=%s error=%s",
                    chunk.docId,
                    chunk.section,
                    exc,
                )

        if not indexed_chunks:
            return 0
        return self.milvus_store.upsert_chunks(indexed_chunks, embeddings)

    def load_chunks(self) -> list[RunbookChunk]:
        try:
            return self.chunk_loader._load_chunks()
        except Exception as exc:
            logger.warning("Failed to load runbook chunks for indexing: %s", exc)
            return []

    def prepare_entities(
        self,
        chunks: list[RunbookChunk],
        embeddings: list[list[float]],
    ) -> list[dict]:
        return [
            self.milvus_store.chunk_to_entity(chunk, embedding)
            for chunk, embedding in zip(chunks, embeddings, strict=True)
        ]

    def chunk_text(self, chunk: RunbookChunk) -> str:
        return "\n".join([
            f"docId: {chunk.docId}",
            f"title: {chunk.title}",
            f"faultType: {chunk.faultType}",
            f"section: {chunk.section}",
            f"keywords: {', '.join(chunk.keywords)}",
            chunk.content,
        ])
