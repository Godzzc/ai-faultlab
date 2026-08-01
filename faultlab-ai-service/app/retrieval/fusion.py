from dataclasses import replace

from app.retrieval.models import RunbookChunk


def reciprocal_rank_fusion(
    result_lists: list[list[RunbookChunk]],
    k: int = 60,
) -> list[RunbookChunk]:
    fused: dict[tuple[str, str], RunbookChunk] = {}
    scores: dict[tuple[str, str], float] = {}
    source_scores: dict[tuple[str, str], dict[str, float]] = {}
    sources: dict[tuple[str, str], set[str]] = {}

    for result_list in result_lists:
        for rank, chunk in enumerate(result_list, start=1):
            key = (chunk.docId, chunk.section)
            source = chunk.source or str((chunk.metadata or {}).get("retrievalSource") or "unknown")
            score_key = "vectorScore" if source == "milvus" else f"{source}Score"
            fused.setdefault(key, chunk)
            scores[key] = scores.get(key, 0.0) + 1.0 / (k + rank)
            sources.setdefault(key, set()).add(source)
            source_scores.setdefault(key, {})[score_key] = chunk.score

    fused_chunks: list[RunbookChunk] = []
    for key, chunk in fused.items():
        fusion_score = scores[key]
        metadata = {
            **(chunk.metadata or {}),
            **source_scores.get(key, {}),
            "sources": sorted(sources.get(key, set())),
            "fusionScore": fusion_score,
        }
        fused_chunks.append(
            replace(
                chunk,
                score=fusion_score,
                source="hybrid",
                metadata=metadata,
            )
        )

    fused_chunks.sort(key=lambda chunk: chunk.score, reverse=True)
    return fused_chunks
