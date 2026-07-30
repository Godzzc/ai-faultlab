import hashlib
import logging
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

from app.config import settings
from app.embedding_client import EmbeddingClient
from app.retrieval.index_state_store import IndexStateStore
from app.retrieval.keyword_runbook_retriever import KeywordRunbookRetriever
from app.retrieval.milvus_store import MilvusStore, stable_chunk_id
from app.retrieval.models import RunbookChunk

logger = logging.getLogger(__name__)


@dataclass
class RunbookDocument:
    doc_id: str
    title: str
    fault_type: str
    content_hash: str
    chunks: list[RunbookChunk]


@dataclass
class RunbookIndexResult:
    status: str = "success"
    collectionName: str = settings.milvus_collection_name
    indexedCount: int = 0
    skippedCount: int = 0
    deletedCount: int = 0
    failedCount: int = 0
    indexedDocuments: list[str] = field(default_factory=list)
    skippedDocuments: list[str] = field(default_factory=list)
    failedDocuments: list[str] = field(default_factory=list)
    forceRebuild: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "collectionName": self.collectionName,
            "indexedCount": self.indexedCount,
            "skippedCount": self.skippedCount,
            "deletedCount": self.deletedCount,
            "failedCount": self.failedCount,
            "indexedDocuments": self.indexedDocuments,
            "skippedDocuments": self.skippedDocuments,
            "failedDocuments": self.failedDocuments,
            "forceRebuild": self.forceRebuild,
        }


class RunbookIndexer:
    def __init__(
        self,
        runbooks_dir: Path | str | None = None,
        embedding_client: EmbeddingClient | None = None,
        milvus_store: MilvusStore | None = None,
        chunk_loader: KeywordRunbookRetriever | None = None,
        state_store: IndexStateStore | None = None,
    ) -> None:
        self.chunk_loader = chunk_loader or KeywordRunbookRetriever(runbooks_dir)
        self.embedding_client = embedding_client or EmbeddingClient()
        self.milvus_store = milvus_store or MilvusStore()
        self.state_store = state_store or IndexStateStore()

    def index_runbooks(self, force_rebuild: bool = False) -> RunbookIndexResult:
        result = RunbookIndexResult(
            collectionName=self.milvus_store.collection_name,
            forceRebuild=force_rebuild,
        )
        state = self.state_store.load_state()
        state.setdefault("documents", {})
        documents = self.load_documents()
        document_ids = set(documents)

        self._cleanup_deleted_documents(state, document_ids, result)

        for document in documents.values():
            if self._should_skip_document(state, document, force_rebuild):
                result.skippedCount += 1
                result.skippedDocuments.append(document.doc_id)
                continue

            try:
                deleted_count = self.milvus_store.delete_by_doc_id(document.doc_id)
                result.deletedCount += deleted_count
                embeddings = self._embed_document_chunks(document)
                indexed_count = self.milvus_store.upsert_chunks(document.chunks, embeddings)
                result.indexedCount += indexed_count
                result.indexedDocuments.append(document.doc_id)
                state["documents"][document.doc_id] = self._document_state(document)
            except Exception as exc:
                logger.warning(
                    "Failed to index runbook docId=%s error=%s",
                    document.doc_id,
                    exc,
                )
                result.failedCount += 1
                result.failedDocuments.append(document.doc_id)

        result.status = "success" if result.failedCount == 0 else "partial_success"
        self.state_store.save_state(state)
        return result

    def load_chunks(self) -> list[RunbookChunk]:
        chunks: list[RunbookChunk] = []
        for document in self.load_documents().values():
            chunks.extend(document.chunks)
        return chunks

    def load_documents(self) -> dict[str, RunbookDocument]:
        runbooks_dir = self.chunk_loader.runbooks_dir
        if not runbooks_dir.exists() or not runbooks_dir.is_dir():
            logger.warning("Runbook directory missing: %s", runbooks_dir)
            return {}

        documents: dict[str, RunbookDocument] = {}
        for path in sorted(runbooks_dir.glob("*.md")):
            try:
                raw = path.read_text(encoding="utf-8")
                chunks = self.chunk_loader._parse_runbook(path)
                if not chunks:
                    logger.warning("Runbook produced no chunks path=%s", path)
                    continue
                first_chunk = chunks[0]
                documents[first_chunk.docId] = RunbookDocument(
                    doc_id=first_chunk.docId,
                    title=first_chunk.title,
                    fault_type=first_chunk.faultType,
                    content_hash=self.content_hash(raw),
                    chunks=chunks,
                )
            except Exception as exc:
                logger.warning("Failed to load runbook document path=%s error=%s", path, exc)
        return documents

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

    def content_hash(self, raw_content: str) -> str:
        return hashlib.sha256(raw_content.encode("utf-8")).hexdigest()

    def _embed_document_chunks(self, document: RunbookDocument) -> list[list[float]]:
        embeddings: list[list[float]] = []
        for chunk in document.chunks:
            try:
                embeddings.append(self.embedding_client.embed_text(self.chunk_text(chunk)))
            except Exception as exc:
                logger.warning(
                    "Embedding failed docId=%s section=%s error=%s",
                    chunk.docId,
                    chunk.section,
                    exc,
                )
                raise RuntimeError(f"Embedding failed for docId={document.doc_id}") from exc
        return embeddings

    def _should_skip_document(
        self,
        state: dict[str, Any],
        document: RunbookDocument,
        force_rebuild: bool,
    ) -> bool:
        if force_rebuild:
            return False
        document_state = state.get("documents", {}).get(document.doc_id)
        if not document_state:
            return False
        return document_state.get("contentHash") == document.content_hash

    def _cleanup_deleted_documents(
        self,
        state: dict[str, Any],
        current_document_ids: set[str],
        result: RunbookIndexResult,
    ) -> None:
        indexed_doc_ids = set(state.get("documents", {}))
        for doc_id in sorted(indexed_doc_ids - current_document_ids):
            try:
                result.deletedCount += self.milvus_store.delete_by_doc_id(doc_id)
                state["documents"].pop(doc_id, None)
            except Exception as exc:
                logger.warning("Failed to delete removed runbook docId=%s error=%s", doc_id, exc)
                result.failedCount += 1
                result.failedDocuments.append(doc_id)

    def _document_state(self, document: RunbookDocument) -> dict[str, Any]:
        return {
            "docId": document.doc_id,
            "title": document.title,
            "faultType": document.fault_type,
            "contentHash": document.content_hash,
            "chunkIds": [stable_chunk_id(chunk) for chunk in document.chunks],
            "indexedAt": datetime.now().astimezone().isoformat(),
        }
