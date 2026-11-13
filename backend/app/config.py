"""Backend settings and deliberately bounded MVP resource limits."""

from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


BACKEND_DIR = Path(__file__).resolve().parent.parent
MAX_IMAGE_BYTES = 4 * 1024 * 1024
MAX_REQUEST_BYTES = 5 * 1024 * 1024
MAX_IMAGE_PIXELS = 20_000_000
MAX_IMAGE_EDGE = 1600
MODEL_TIMEOUT_SECONDS = 30.0
MAX_OUTPUT_TOKENS = 1500


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=BACKEND_DIR / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    openai_api_key: SecretStr | None = None
    openai_model: str = "gpt-6.1-terra"
    moss_api_key: SecretStr | None = None
    moss_model: str = "moss-live-1.0"
    api_bearer_token: SecretStr | None = None
    assistant_provider: Literal["openai", "stepfun"] = "openai"
    stepfun_api_key: SecretStr | None = None
    stepfun_region: Literal["global", "cn"] = "global"
    stepfun_base_url: str | None = None
    stepfun_model: str = "step-5-preview"
    stepfun_reasoning_effort: Literal["low", "medium", "high"] = "low"
    knowledge_data_dir: Path = BACKEND_DIR / ".data" / "knowledge"
    notebooklm_data_dir: Path = BACKEND_DIR / ".data" / "notebooklm"
    agent_max_rounds: int = Field(default=3, ge=1, le=5)
    agent_max_tool_calls: int = Field(default=6, ge=1, le=20)
    agent_max_passages: int = Field(default=12, ge=1, le=30)
    agent_max_retrieval_tokens: int = Field(default=6000, ge=100, le=12000)
    agent_max_estimated_usd: float = Field(default=0.10, gt=0, allow_inf_nan=False)
    stepfun_input_usd_per_million: float | None = Field(default=None, gt=0, allow_inf_nan=False)
    stepfun_output_usd_per_million: float | None = Field(default=None, gt=0, allow_inf_nan=False)
    stepfun_image_token_reserve: int = Field(default=4096, ge=1, le=100000)

    @field_validator("openai_api_key", "stepfun_api_key", "moss_api_key", "api_bearer_token", mode="before")
    @classmethod
    def allow_empty_key(cls, value: object) -> object:
        if isinstance(value, str):
            return value.strip() or None
        return value

    @field_validator("stepfun_input_usd_per_million", "stepfun_output_usd_per_million", mode="before")
    @classmethod
    def allow_empty_price(cls, value: object) -> object:
        if isinstance(value, str) and not value.strip():
            return None
        return value

    @field_validator("openai_model", "stepfun_model", "moss_model")
    @classmethod
    def require_model(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("The model name must not be blank.")
        return value.strip()

    @field_validator("stepfun_base_url", mode="before")
    @classmethod
    def validate_stepfun_url(cls, value: object) -> object:
        if value is None or (isinstance(value, str) and not value.strip()):
            return None
        if not isinstance(value, str):
            raise ValueError("StepFun base URL must be a URL.")
        value = value.strip().rstrip("/")
        parsed = urlsplit(value)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("StepFun base URL must be an HTTPS URL without credentials, query, or fragment.")
        return value

    @property
    def effective_stepfun_base_url(self) -> str:
        if self.stepfun_base_url is not None:
            return self.stepfun_base_url
        return "https://api.stepfun.ai/v1" if self.stepfun_region == "global" else "https://api.stepfun.com/v1"

    @property
    def active_model(self) -> str:
        return self.stepfun_model if self.assistant_provider == "stepfun" else self.openai_model
