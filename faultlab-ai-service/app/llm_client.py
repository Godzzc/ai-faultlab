from openai import OpenAI

from app.config import settings


class LlmClient:
    def __init__(self) -> None:
        self._client = OpenAI(
            api_key=settings.dashscope_api_key,
            base_url=settings.dashscope_base_url,
            timeout=settings.llm_timeout_seconds,
        )

    def generate(self, system_prompt: str, user_prompt: str) -> str:
        response = self._client.chat.completions.create(
            model=settings.dashscope_model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=0.2,
        )
        content = response.choices[0].message.content if response.choices else ""
        if not content:
            raise RuntimeError("LLM returned empty content")
        return content
