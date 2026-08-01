import os

from pydantic import BaseModel


class Settings(BaseModel):
    service_name: str = "faultlab-ai-service"
    dashscope_api_key: str = ""
    dashscope_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    llm_enabled: bool = True
    llm_timeout_seconds: int = 90
    llm_max_retries: int = 0
    llm_default_model: str = "qwen3.7-flash"
    llm_fast_model: str = "qwen3.7-plus-2026-05-26"
    llm_reasoning_model: str = "qwen3.7-plus"
    llm_long_context_model: str = "qwen3.7-max"
    embedding_model: str = "text-embedding-v4"
    embedding_dimension: int = 1024
    embedding_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    embedding_timeout_seconds: int = 60
    milvus_host: str = "localhost"
    milvus_port: int = 19530
    milvus_collection_name: str = "faultlab_runbook_chunks"
    milvus_metric_type: str = "COSINE"
    retrieval_mode: str = "hybrid"
    hybrid_rrf_k: int = 60
    hybrid_vector_top_k: int = 5
    hybrid_bm25_top_k: int = 5
    retrieval_top_k: int = 3
    rerank_enabled: bool = True


def load_settings() -> Settings:
    return Settings(
        dashscope_api_key=os.getenv("DASHSCOPE_API_KEY", ""),
    )


settings = load_settings()
