import os

from pydantic import BaseModel


class Settings(BaseModel):
    service_name: str = "faultlab-ai-service"
    dashscope_api_key: str = ""
    dashscope_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    dashscope_model: str = "qwen-plus"
    llm_enabled: bool = True
    llm_timeout_seconds: int = 20
    llm_max_retries: int = 1


def load_settings() -> Settings:
    return Settings(
        dashscope_api_key=os.getenv("DASHSCOPE_API_KEY", ""),
        dashscope_base_url=os.getenv(
            "DASHSCOPE_BASE_URL",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
        ),
        dashscope_model=os.getenv("DASHSCOPE_MODEL", "qwen-plus"),
        llm_enabled=os.getenv("LLM_ENABLED", "true").lower() == "true",
        llm_timeout_seconds=int(os.getenv("LLM_TIMEOUT_SECONDS", "20")),
        llm_max_retries=int(os.getenv("LLM_MAX_RETRIES", "1")),
    )


settings = load_settings()
