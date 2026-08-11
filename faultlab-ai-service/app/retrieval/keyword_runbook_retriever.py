import logging
import re
from pathlib import Path
from typing import Any

from app.retrieval.base import BaseRunbookRetriever
from app.retrieval.models import RunbookChunk
from app.schemas import DiagnosisRequest

logger = logging.getLogger(__name__)

FRONT_MATTER_PATTERN = re.compile(r"\A---\s*\n(.*?)\n---\s*\n?", re.DOTALL)
SECTION_PATTERN = re.compile(r"^##\s+(.+?)\s*$", re.MULTILINE)
TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_\-.]+")


class KeywordRunbookRetriever(BaseRunbookRetriever):
    def __init__(self, runbooks_dir: Path | str | None = None) -> None:
        self.runbooks_dir = Path(runbooks_dir) if runbooks_dir else Path(__file__).resolve().parents[2] / "runbooks"

    def retrieve(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
        top_k: int = 3,
    ) -> list[RunbookChunk]:
        try:
            chunks = self._load_chunks()
            fault_type = self._request_fault_type(request)
            if fault_type:
                chunks = [chunk for chunk in chunks if chunk.faultType == fault_type]

            query_keywords = self._extract_query_keywords(request, trace_summary)
            scored_chunks = [
                self._with_score(chunk, query_keywords)
                for chunk in chunks
            ]
            matched_chunks = [
                chunk for chunk in scored_chunks if chunk.score > 0
            ]
            matched_chunks.sort(key=lambda chunk: chunk.score, reverse=True)
            return matched_chunks[:top_k]
        except Exception as exc:
            logger.warning("Keyword runbook retrieval failed: %s", exc)
            return []

    def _load_chunks(self) -> list[RunbookChunk]:
        if not self.runbooks_dir.exists() or not self.runbooks_dir.is_dir():
            logger.warning("Runbook directory missing: %s", self.runbooks_dir)
            return []

        chunks: list[RunbookChunk] = []
        for path in sorted(self.runbooks_dir.glob("*.md")):
            try:
                chunks.extend(self._parse_runbook(path))
            except Exception as exc:
                logger.warning("Failed to parse runbook path=%s error=%s", path, exc)
        return chunks

    def _parse_runbook(self, path: Path) -> list[RunbookChunk]:
        raw = path.read_text(encoding="utf-8")
        metadata, body = self._parse_front_matter(raw)
        doc_id = metadata.get("docId") or path.stem
        title = metadata.get("title") or doc_id
        fault_type = metadata.get("faultType") or ""
        keywords = self._split_keywords(metadata.get("keywords", ""))
        if not fault_type:
            logger.warning("Runbook missing faultType path=%s", path)
            return []

        sections = self._split_sections(body)
        return [
            RunbookChunk(
                docId=doc_id,
                title=title,
                faultType=fault_type,
                section=section_title,
                content=section_content,
                keywords=keywords,
                source="local_markdown",
                metadata={"path": str(path)},
            )
            for section_title, section_content in sections
            if section_content.strip()
        ]

    def _parse_front_matter(self, raw: str) -> tuple[dict[str, str | list[str]], str]:
        match = FRONT_MATTER_PATTERN.match(raw)
        if not match:
            logger.warning("Runbook front matter missing")
            return {}, raw

        metadata: dict[str, str | list[str]] = {}
        current_list_key = ""
        for line in match.group(1).splitlines():
            stripped = line.strip()
            if current_list_key and stripped.startswith("- "):
                list_value = metadata.setdefault(current_list_key, [])
                if isinstance(list_value, list):
                    list_value.append(stripped[2:].strip())
                continue
            if ":" not in line:
                logger.warning("Invalid front matter line ignored: %s", line)
                continue
            key, value = line.split(":", 1)
            current_list_key = ""
            key = key.strip()
            value = value.strip()
            if not value:
                metadata[key] = []
                current_list_key = key
                continue
            metadata[key] = value
        return metadata, raw[match.end():]

    def _split_sections(self, body: str) -> list[tuple[str, str]]:
        matches = list(SECTION_PATTERN.finditer(body))
        if not matches:
            title = self._first_heading(body) or "Overview"
            return [(title, body.strip())]

        sections: list[tuple[str, str]] = []
        for index, match in enumerate(matches):
            start = match.end()
            end = matches[index + 1].start() if index + 1 < len(matches) else len(body)
            sections.append((match.group(1).strip(), body[start:end].strip()))
        return sections

    def _first_heading(self, body: str) -> str | None:
        for line in body.splitlines():
            if line.startswith("# "):
                return line[2:].strip()
        return None

    def _request_fault_type(self, request: DiagnosisRequest) -> str:
        if request.rule_result and request.rule_result.fault_type:
            return request.rule_result.fault_type
        return request.experiment.scenario_code or ""

    def _extract_query_keywords(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
    ) -> set[str]:
        values: list[Any] = []
        if request.rule_result:
            values.extend([
                request.rule_result.fault_type,
                request.rule_result.fault_name,
                request.rule_result.reason,
                request.rule_result.evidence,
                request.rule_result.suggestions,
            ])

        values.append(request.experiment.scenario_code)
        values.append([metric.model_dump(by_alias=True) for metric in request.metrics])
        values.append(trace_summary)
        return self._tokens_from_value(values)

    def _tokens_from_value(self, value: Any) -> set[str]:
        if value is None:
            return set()
        if isinstance(value, str):
            return {token.lower() for token in TOKEN_PATTERN.findall(value)}
        if isinstance(value, dict):
            tokens: set[str] = set()
            for key, item in value.items():
                tokens.update(self._tokens_from_value(key))
                tokens.update(self._tokens_from_value(item))
            return tokens
        if isinstance(value, list | tuple | set):
            tokens: set[str] = set()
            for item in value:
                tokens.update(self._tokens_from_value(item))
            return tokens
        return self._tokens_from_value(str(value))

    def _split_keywords(self, value: str | list[str]) -> list[str]:
        if isinstance(value, list):
            return [item.strip() for item in value if item and item.strip()]
        return [item.strip() for item in value.split(",") if item.strip()]

    def _with_score(self, chunk: RunbookChunk, query_keywords: set[str]) -> RunbookChunk:
        if not query_keywords:
            return chunk

        keyword_tokens = self._tokens_from_value(" ".join(chunk.keywords))
        title_tokens = self._tokens_from_value(chunk.title)
        section_tokens = self._tokens_from_value(chunk.section)
        content_tokens = self._tokens_from_value(chunk.content)

        score = 0.0
        for keyword in query_keywords:
            if keyword in keyword_tokens:
                score += 3.0
            if keyword in title_tokens:
                score += 2.0
            if keyword in section_tokens:
                score += 2.0
            if keyword in content_tokens:
                score += 1.0

        return RunbookChunk(
            docId=chunk.docId,
            title=chunk.title,
            faultType=chunk.faultType,
            section=chunk.section,
            content=chunk.content,
            keywords=chunk.keywords,
            score=score,
            source=chunk.source,
            metadata=chunk.metadata,
        )
