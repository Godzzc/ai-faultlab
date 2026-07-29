from pydantic import BaseModel


class Settings(BaseModel):
    service_name: str = "faultlab-ai-service"


settings = Settings()
