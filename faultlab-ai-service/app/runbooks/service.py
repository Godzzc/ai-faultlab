import hashlib
import logging
from pathlib import Path
from typing import Any

from app.retrieval.index_state_store import IndexStateStore
from app.retrieval.keyword_runbook_retriever import FRONT_MATTER_PATTERN, KeywordRunbookRetriever
from app.runbooks.models import (
    RunbookConflictError,
    RunbookDeleteResponse,
    RunbookDocumentDetail,
    RunbookDocumentSummary,
    RunbookNotFoundError,
    RunbookSaveRequest,
    RunbookSaveResponse,
    RunbookSection,
    RunbookValidationError,
    validate_doc_id_value,
)

logger = logging.getLogger(__name__)


class RunbookManagementService:
    def __init__(
        self,
        runbooks_dir: Path | str | None = None,
        state_store: IndexStateStore | None = None,
        parser: KeywordRunbookRetriever | None = None,
    ) -> None:
        self.runbooks_dir = Path(runbooks_dir) if runbooks_dir else Path(__file__).resolve().parents[2] / "runbooks"
        self.state_store = state_store or IndexStateStore()
        self.parser = parser or KeywordRunbookRetriever(self.runbooks_dir)

    def list_runbooks(self) -> list[RunbookDocumentSummary]:
        self.runbooks_dir.mkdir(parents=True, exist_ok=True)
        return [
            self._build_summary(path)
            for path in sorted(self.runbooks_dir.glob("*.md"))
            if path.is_file()
        ]

    def get_runbook(self, doc_id: str) -> RunbookDocumentDetail:
        path = self._path_for_doc_id(doc_id)
        if not path.exists():
            raise RunbookNotFoundError(f"Runbook not found: {doc_id}")
        return self._build_detail(path)

    def create_runbook(self, request: RunbookSaveRequest) -> RunbookSaveResponse:
        path = self._path_for_doc_id(request.doc_id)
        if path.exists():
            raise RunbookConflictError(f"Runbook already exists: {request.doc_id}")
        raw_markdown = self._normalize_markdown(request)
        self._write_markdown(path, raw_markdown)
        return RunbookSaveResponse(
            docId=request.doc_id,
            fileName=path.name,
            status="created",
            message="Runbook created.",
        )

    def update_runbook(self, doc_id: str, request: RunbookSaveRequest) -> RunbookSaveResponse:
        validate_doc_id_value(doc_id)
        if request.doc_id != doc_id:
            raise RunbookValidationError("Request docId must match path docId")
        path = self._path_for_doc_id(doc_id)
        if not path.exists():
            raise RunbookNotFoundError(f"Runbook not found: {doc_id}")
        raw_markdown = self._normalize_markdown(request)
        self._write_markdown(path, raw_markdown)
        return RunbookSaveResponse(
            docId=doc_id,
            fileName=path.name,
            status="updated",
            message="Runbook updated.",
        )

    def delete_runbook(self, doc_id: str) -> RunbookDeleteResponse:
        path = self._path_for_doc_id(doc_id)
        if not path.exists():
            raise RunbookNotFoundError(f"Runbook not found: {doc_id}")
        try:
            path.unlink()
        except Exception as exc:
            logger.warning("Failed to delete runbook docId=%s error=%s", doc_id, exc)
            raise RunbookValidationError(f"Failed to delete runbook: {doc_id}") from exc
        return RunbookDeleteResponse(
            docId=doc_id,
            fileName=path.name,
            status="deleted",
            message="Runbook Markdown deleted. Run indexing to clean stale chunks.",
        )

    def _build_summary(self, path: Path) -> RunbookDocumentSummary:
        detail = self._build_detail(path)
        return RunbookDocumentSummary(
            docId=detail.doc_id,
            title=detail.title,
            faultType=detail.fault_type,
            keywords=detail.keywords,
            sectionCount=len(detail.sections),
            fileName=detail.file_name,
            contentHash=detail.content_hash,
            indexedAt=detail.indexed_at,
            chunkCount=detail.chunk_count,
            existsInIndexState=detail.exists_in_index_state,
            status=detail.status,
        )

    def _build_detail(self, path: Path) -> RunbookDocumentDetail:
        try:
            raw_markdown = path.read_text(encoding="utf-8")
        except Exception as exc:
            logger.warning("Failed to read runbook path=%s error=%s", path, exc)
            raise RunbookValidationError(f"Failed to read runbook: {path.name}") from exc

        metadata, body = self.parser._parse_front_matter(raw_markdown)
        doc_id = metadata.get("docId") or path.stem
        try:
            validate_doc_id_value(doc_id)
        except ValueError as exc:
            raise RunbookValidationError(str(exc)) from exc

        sections = [
            RunbookSection(section=section, content=content)
            for section, content in self.parser._split_sections(body)
            if content.strip()
        ]
        content_hash = self.content_hash(raw_markdown)
        document_state = self.state_store.get_document_state(doc_id)
        index_fields = self._index_fields(document_state, content_hash)
        return RunbookDocumentDetail(
            docId=doc_id,
            title=metadata.get("title") or doc_id,
            faultType=metadata.get("faultType") or "",
            keywords=self.parser._split_keywords(metadata.get("keywords", "")),
            fileName=path.name,
            contentHash=content_hash,
            sections=sections,
            rawMarkdown=raw_markdown,
            **index_fields,
        )

    def _index_fields(
        self,
        document_state: dict[str, Any] | None,
        content_hash: str,
    ) -> dict[str, Any]:
        if not document_state:
            return {
                "indexedAt": None,
                "chunkCount": 0,
                "existsInIndexState": False,
                "status": "not_indexed",
            }
        state_hash = document_state.get("contentHash")
        return {
            "indexedAt": document_state.get("indexedAt"),
            "chunkCount": len(document_state.get("chunkIds") or []),
            "existsInIndexState": True,
            "status": "indexed" if state_hash == content_hash else "stale",
        }

    def _normalize_markdown(self, request: RunbookSaveRequest) -> str:
        raw = request.raw_markdown or ""
        if FRONT_MATTER_PATTERN.match(raw):
            metadata, _ = self.parser._parse_front_matter(raw)
            front_matter_doc_id = metadata.get("docId")
            if front_matter_doc_id:
                try:
                    validate_doc_id_value(front_matter_doc_id)
                except ValueError as exc:
                    raise RunbookValidationError(str(exc)) from exc
                if front_matter_doc_id != request.doc_id:
                    raise RunbookValidationError("Front matter docId must match request docId")
            return raw if raw.endswith("\n") else raw + "\n"
        keywords = ", ".join(request.keywords or [])
        body = raw.strip() or f"# {request.title or request.doc_id}\n\n## Overview\n\n"
        return "\n".join([
            "---",
            f"docId: {request.doc_id}",
            f"title: {request.title or request.doc_id}",
            f"faultType: {request.fault_type}",
            f"keywords: {keywords}",
            "---",
            "",
            body,
            "",
        ])

    def _write_markdown(self, path: Path, raw_markdown: str) -> None:
        self.runbooks_dir.mkdir(parents=True, exist_ok=True)
        try:
            path.write_text(raw_markdown, encoding="utf-8")
        except Exception as exc:
            logger.warning("Failed to write runbook path=%s error=%s", path, exc)
            raise RunbookValidationError(f"Failed to write runbook: {path.name}") from exc

    def _path_for_doc_id(self, doc_id: str) -> Path:
        try:
            validate_doc_id_value(doc_id)
        except ValueError as exc:
            raise RunbookValidationError(str(exc)) from exc
        self.runbooks_dir.mkdir(parents=True, exist_ok=True)
        root = self.runbooks_dir.resolve()
        path = (self.runbooks_dir / f"{doc_id}.md").resolve()
        if root not in path.parents:
            raise RunbookValidationError("Invalid runbook path")
        return path

    def content_hash(self, raw_content: str) -> str:
        return hashlib.sha256(raw_content.encode("utf-8")).hexdigest()
