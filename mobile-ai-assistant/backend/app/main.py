import logging
import time
import uuid
from typing import Literal

from fastapi import Depends, FastAPI, HTTPException
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator, model_validator

from app.providers import AIProvider, AnthropicProvider, ProviderUnavailableError

logger = logging.getLogger(__name__)

app = FastAPI(title="Mobile AI Assistant Backend")

MAX_CONTEXT_MESSAGES = 11


# ---------------------------------------------------------------------------
# Provider dependency
# ---------------------------------------------------------------------------

_provider: AIProvider | None = None


def get_provider() -> AIProvider:
    """
    Lazy singleton. Returns the module-level provider, initialising
    AnthropicProvider on first call. Tests replace this via
    app.dependency_overrides so AnthropicProvider is never instantiated
    during test runs.
    """
    global _provider

    if _provider is None:
        _provider = AnthropicProvider()

    return _provider


# ---------------------------------------------------------------------------
# Request / response models
# ---------------------------------------------------------------------------


class ChatMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str

    @field_validator("content")
    @classmethod
    def content_must_not_be_blank(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("message content must not be empty or blank")

        return value


class ChatRequest(BaseModel):
    messages: list[ChatMessage] = Field(
        min_length=1,
        max_length=MAX_CONTEXT_MESSAGES,
    )

    @model_validator(mode="after")
    def latest_message_must_be_user(self):
        if self.messages[-1].role != "user":
            raise ValueError("latest message must have role 'user'")

        return self


class ChatResponse(BaseModel):
    response: str
    request_id: str
    latency_ms: int


# ---------------------------------------------------------------------------
# Exception handlers
# ---------------------------------------------------------------------------


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    _,
    exc: RequestValidationError,
) -> JSONResponse:
    errors = [
        {
            "loc": error["loc"],
            "msg": error["msg"],
            "type": error["type"],
        }
        for error in exc.errors()
    ]

    return JSONResponse(
        status_code=422,
        content={
            "error": "invalid_request",
            "detail": errors,
        },
    )


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------


@app.post("/chat", response_model=ChatResponse)
async def chat(
    request: ChatRequest,
    provider: AIProvider = Depends(get_provider),
) -> ChatResponse:
    start_time = time.perf_counter()
    request_id = str(uuid.uuid4())
    provider_name = type(provider).__name__

    provider_messages = [
        {
            "role": message.role,
            "content": message.content,
        }
        for message in request.messages
    ]

    try:
        response_text = await provider.complete(provider_messages)

    except ProviderUnavailableError as exc:
        latency_ms = int(
            (time.perf_counter() - start_time) * 1000
        )

        logger.error(
            "chat request_id=%s status=error provider=%s "
            "error_type=%s http_status=503 latency_ms=%d",
            request_id,
            provider_name,
            type(exc).__name__,
            latency_ms,
        )

        raise HTTPException(
            status_code=503,
            detail="ai_provider_unavailable",
        ) from exc

    except Exception as exc:
        latency_ms = int(
            (time.perf_counter() - start_time) * 1000
        )

        logger.error(
            "chat request_id=%s status=error provider=%s "
            "error_type=%s http_status=500 latency_ms=%d",
            request_id,
            provider_name,
            type(exc).__name__,
            latency_ms,
        )

        raise HTTPException(
            status_code=500,
            detail="internal_error",
        ) from exc

    latency_ms = int(
        (time.perf_counter() - start_time) * 1000
    )

    logger.info(
        "chat request_id=%s status=ok provider=%s latency_ms=%d",
        request_id,
        provider_name,
        latency_ms,
    )

    return ChatResponse(
        response=response_text,
        request_id=request_id,
        latency_ms=latency_ms,
    )
