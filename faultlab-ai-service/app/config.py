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


def load_settings() -> Settings:
    return Settings(
        dashscope_api_key=os.getenv("DASHSCOPE_API_KEY", ""),
    )


settings = load_settings()
