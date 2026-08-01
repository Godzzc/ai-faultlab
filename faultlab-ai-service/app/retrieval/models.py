import json
from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class RunbookChunk:
    docId: str
    title: str
    faultType: str
    section: str
    content: str
    keywords: list[str]
    score: float = 0.0
    source: str = "local_markdown"
    metadata: dict[str, Any] = field(default_factory=dict)


def runbook_chunks_to_prompt_context(chunks: list[RunbookChunk]) -> list[dict[str, Any]]:
    return [
        {
            "docId": chunk.docId,
            "title": chunk.title,
            "section": chunk.section,
            "content": chunk.content,
        }
        for chunk in chunks
    ]


def runbook_chunks_to_json(chunks: list[RunbookChunk]) -> str:
    return json.dumps(runbook_chunks_to_prompt_context(chunks), ensure_ascii=False)
