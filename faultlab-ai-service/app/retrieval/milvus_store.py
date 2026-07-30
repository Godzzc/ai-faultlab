import json
import logging
from typing import Any

from app.config import settings
from app.retrieval.models import RunbookChunk

logger = logging.getLogger(__name__)


class MilvusStore:
    def __init__(
        self,
        host: str | None = None,
        port: int | None = None,
        collection_name: str | None = None,
        dimension: int | None = None,
        metric_type: str | None = None,
    ) -> None:
        self.host = host or settings.milvus_host
        self.port = port or settings.milvus_port
        self.collection_name = collection_name or settings.milvus_collection_name
        self.dimension = dimension or settings.embedding_dimension
        self.metric_type = metric_type or settings.milvus_metric_type

    def ensure_collection(self) -> None:
        self._connect()
        utility, Collection, FieldSchema, CollectionSchema, DataType = self._milvus_types()
        if utility.has_collection(self.collection_name):
            collection = Collection(self.collection_name)
        else:
            fields = [
                FieldSchema(name="id", dtype=DataType.VARCHAR, is_primary=True, max_length=128),
                FieldSchema(name="docId", dtype=DataType.VARCHAR, max_length=256),
                FieldSchema(name="title", dtype=DataType.VARCHAR, max_length=512),
                FieldSchema(name="faultType", dtype=DataType.VARCHAR, max_length=128),
                FieldSchema(name="section", dtype=DataType.VARCHAR, max_length=512),
                FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=16384),
                FieldSchema(name="keywords", dtype=DataType.VARCHAR, max_length=2048),
                FieldSchema(name="metadata", dtype=DataType.VARCHAR, max_length=4096),
                FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=self.dimension),
            ]
            schema = CollectionSchema(fields=fields, description="AI FaultLab runbook chunks")
            collection = Collection(name=self.collection_name, schema=schema)

        if not collection.has_index():
            collection.create_index(
                field_name="embedding",
                index_params={
                    "index_type": "AUTOINDEX",
                    "metric_type": self.metric_type,
                    "params": {},
                },
            )
        collection.load()

    def upsert_chunks(
        self,
        chunks: list[RunbookChunk],
        embeddings: list[list[float]],
    ) -> int:
        if len(chunks) != len(embeddings):
            raise ValueError("chunks and embeddings length mismatch")
        if not chunks:
            return 0

        self.ensure_collection()
        _, Collection, _, _, _ = self._milvus_types()
        collection = Collection(self.collection_name)
        records = [
            self.chunk_to_entity(chunk, embedding)
            for chunk, embedding in zip(chunks, embeddings, strict=True)
        ]
        collection.upsert(records)
        collection.flush()
        logger.info("Upserted runbook chunks into Milvus count=%s", len(records))
        return len(records)

    def search(
        self,
        embedding: list[float],
        fault_type: str = "",
        top_k: int = 3,
    ) -> list[RunbookChunk]:
        self.ensure_collection()
        _, Collection, _, _, _ = self._milvus_types()
        collection = Collection(self.collection_name)
        expr = self._fault_type_expr(fault_type)
        results = collection.search(
            data=[embedding],
            anns_field="embedding",
            param={"metric_type": self.metric_type, "params": {}},
            limit=top_k,
            expr=expr,
            output_fields=[
                "docId",
                "title",
                "faultType",
                "section",
                "content",
                "keywords",
                "metadata",
            ],
        )
        hits = results[0] if results else []
        return [self._hit_to_chunk(hit) for hit in hits]

    def chunk_to_entity(self, chunk: RunbookChunk, embedding: list[float]) -> dict[str, Any]:
        return {
            "id": stable_chunk_id(chunk),
            "docId": chunk.docId,
            "title": chunk.title,
            "faultType": chunk.faultType,
            "section": chunk.section,
            "content": chunk.content,
            "keywords": ",".join(chunk.keywords),
            "metadata": json.dumps(chunk.metadata or {}, ensure_ascii=False),
            "embedding": embedding,
        }

    def _connect(self) -> None:
        from pymilvus import connections

        connections.connect(alias="default", host=self.host, port=str(self.port))

    def _milvus_types(self) -> tuple[Any, Any, Any, Any, Any]:
        from pymilvus import Collection, CollectionSchema, DataType, FieldSchema, utility

        return utility, Collection, FieldSchema, CollectionSchema, DataType

    def _hit_to_chunk(self, hit: Any) -> RunbookChunk:
        entity = getattr(hit, "entity", None)
        score = float(getattr(hit, "score", 0.0) or getattr(hit, "distance", 0.0) or 0.0)
        values = self._entity_values(entity)
        metadata = self._parse_metadata(values.get("metadata", ""))
        return RunbookChunk(
            docId=str(values.get("docId", "")),
            title=str(values.get("title", "")),
            faultType=str(values.get("faultType", "")),
            section=str(values.get("section", "")),
            content=str(values.get("content", "")),
            keywords=self._split_keywords(str(values.get("keywords", ""))),
            score=score,
            source="milvus",
            metadata=metadata,
        )

    def _entity_values(self, entity: Any) -> dict[str, Any]:
        if entity is None:
            return {}
        if isinstance(entity, dict):
            return entity
        values: dict[str, Any] = {}
        for field in ["docId", "title", "faultType", "section", "content", "keywords", "metadata"]:
            try:
                values[field] = entity.get(field)
            except AttributeError:
                values[field] = getattr(entity, field, "")
        return values

    def _fault_type_expr(self, fault_type: str) -> str | None:
        if not fault_type:
            return None
        escaped = fault_type.replace("\\", "\\\\").replace('"', '\\"')
        return f'faultType == "{escaped}"'

    def _split_keywords(self, value: str) -> list[str]:
        return [item.strip() for item in value.split(",") if item.strip()]

    def _parse_metadata(self, value: str) -> dict[str, Any]:
        try:
            parsed = json.loads(value) if value else {}
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            return {}


def stable_chunk_id(chunk: RunbookChunk) -> str:
    import hashlib

    raw = f"{chunk.docId}:{chunk.section}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()
