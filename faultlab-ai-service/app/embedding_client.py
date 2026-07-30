import logging

from openai import OpenAI

from app.config import settings

logger = logging.getLogger(__name__)


class EmbeddingClient:
    def __init__(self) -> None:
        if not settings.dashscope_api_key:
            raise RuntimeError("DASHSCOPE_API_KEY is required for embedding")
        self._client = OpenAI(
            api_key=settings.dashscope_api_key,
            base_url=settings.embedding_base_url,
            timeout=settings.embedding_timeout_seconds,
        )

    def embed_text(self, text: str) -> list[float]:
        embeddings = self.embed_texts([text])
        if not embeddings:
            raise RuntimeError("Embedding API returned no embedding")
        return embeddings[0]

    def embed_texts(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        try:
            logger.info(
                "Generating embeddings model=%s dimension=%s count=%s",
                settings.embedding_model,
                settings.embedding_dimension,
                len(texts),
            )
            response = self._client.embeddings.create(
                model=settings.embedding_model,
                input=texts,
                dimensions=settings.embedding_dimension,
                encoding_format="float",
            )
            embeddings = [item.embedding for item in response.data]
            if len(embeddings) != len(texts):
                raise RuntimeError("Embedding API returned unexpected embedding count")
            return embeddings
        except Exception as exc:
            logger.warning("Embedding generation failed: %s", exc)
            raise RuntimeError("Embedding generation failed") from exc
