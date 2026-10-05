import logging

import pytest
from fastapi.testclient import TestClient

from app.main import app, get_provider
from app.providers import AIProvider, MockProvider, ProviderUnavailableError


class FailingProvider(AIProvider):
    async def complete(self, messages: list[dict[str, str]]) -> str:
        raise ProviderUnavailableError("Simulated provider failure")


class RecordingProvider(AIProvider):
    def __init__(self) -> None:
        self.received_messages: list[dict[str, str]] | None = None

    async def complete(self, messages: list[dict[str, str]]) -> str:
        self.received_messages = messages
        return "Recorded response"


@pytest.fixture(autouse=True)
def use_mock_provider():
    app.dependency_overrides[get_provider] = lambda: MockProvider()
    yield
    app.dependency_overrides.clear()


client = TestClient(app)


def test_chat_valid_single_message_request():
    response = client.post(
        "/chat",
        json={
            "messages": [
                {
                    "role": "user",
                    "content": "Hello there",
                }
            ]
        },
    )

    assert response.status_code == 200

    body = response.json()
    assert body["response"] == "Mock response to: Hello there"
    assert isinstance(body["request_id"], str) and body["request_id"]
    assert isinstance(body["latency_ms"], int)
    assert body["latency_ms"] >= 0


def test_chat_passes_full_conversation_to_provider():
    provider = RecordingProvider()
    app.dependency_overrides[get_provider] = lambda: provider

    messages = [
        {
            "role": "user",
            "content": "Explain Kotlin coroutines",
        },
        {
            "role": "assistant",
            "content": "Coroutines let Kotlin perform asynchronous work.",
        },
        {
            "role": "user",
            "content": "Show me a simple example",
        },
    ]

    response = client.post(
        "/chat",
        json={"messages": messages},
    )

    assert response.status_code == 200
    assert provider.received_messages == messages


def test_chat_rejects_empty_messages_list():
    response = client.post(
        "/chat",
        json={"messages": []},
    )

    assert response.status_code == 422
    assert response.json()["error"] == "invalid_request"


def test_chat_rejects_blank_message_content():
    response = client.post(
        "/chat",
        json={
            "messages": [
                {
                    "role": "user",
                    "content": "   ",
                }
            ]
        },
    )

    assert response.status_code == 422
    assert response.json()["error"] == "invalid_request"


def test_chat_rejects_invalid_role():
    response = client.post(
        "/chat",
        json={
            "messages": [
                {
                    "role": "system",
                    "content": "Not allowed",
                }
            ]
        },
    )

    assert response.status_code == 422
    assert response.json()["error"] == "invalid_request"


def test_chat_requires_latest_message_to_be_user():
    response = client.post(
        "/chat",
        json={
            "messages": [
                {
                    "role": "user",
                    "content": "Hello",
                },
                {
                    "role": "assistant",
                    "content": "Hi",
                },
            ]
        },
    )

    assert response.status_code == 422
    assert response.json()["error"] == "invalid_request"


def test_chat_rejects_more_than_eleven_context_messages():
    messages = [
        {
            "role": "user" if index % 2 == 0 else "assistant",
            "content": f"message {index}",
        }
        for index in range(12)
    ]

    response = client.post(
        "/chat",
        json={"messages": messages},
    )

    assert response.status_code == 422
    assert response.json()["error"] == "invalid_request"


def test_chat_provider_failure_returns_503():
    app.dependency_overrides[get_provider] = lambda: FailingProvider()

    response = client.post(
        "/chat",
        json={
            "messages": [
                {
                    "role": "user",
                    "content": "Hello",
                }
            ]
        },
    )

    assert response.status_code == 503
    assert response.json()["detail"] == "ai_provider_unavailable"


def test_successful_request_logs_at_info(caplog):
    with caplog.at_level(logging.INFO, logger="app.main"):
        response = client.post(
            "/chat",
            json={
                "messages": [
                    {
                        "role": "user",
                        "content": "Hello there",
                    }
                ]
            },
        )

    assert response.status_code == 200

    info_records = [
        record
        for record in caplog.records
        if record.levelno == logging.INFO
        and record.name == "app.main"
    ]

    assert len(info_records) == 1

    message = info_records[0].getMessage()

    assert "status=ok" in message
    assert "latency_ms=" in message
    assert "provider=" in message


def test_provider_failure_logs_at_error(caplog):
    app.dependency_overrides[get_provider] = lambda: FailingProvider()

    with caplog.at_level(logging.ERROR, logger="app.main"):
        response = client.post(
            "/chat",
            json={
                "messages": [
                    {
                        "role": "user",
                        "content": "Hello",
                    }
                ]
            },
        )

    assert response.status_code == 503

    error_records = [
        record
        for record in caplog.records
        if record.levelno == logging.ERROR
        and record.name == "app.main"
    ]

    assert len(error_records) == 1

    message = error_records[0].getMessage()

    assert "status=error" in message
    assert "http_status=503" in message
    assert "error_type=" in message
