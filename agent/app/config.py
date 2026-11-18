"""Settings, read once from the environment."""

import os
from dataclasses import dataclass


def _has_claude_credentials() -> bool:
    return bool(os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN"))


@dataclass(frozen=True)
class Settings:
    # "live" calls Claude; "mock" uses scripted answers (offline demos and tests).
    # "auto" (default) is live when Claude credentials are present.
    mode: str
    onboarding_model: str
    draft_model: str
    classify_model: str
    # Base URL of the Java web service's internal API, e.g. http://web:8080
    web_internal_url: str
    # Shared token sent in X-Internal-Token on every call between web and agent, both directions.
    internal_token: str
    max_tool_rounds: int


def load() -> Settings:
    mode = os.environ.get("AGENT_MODE", "auto").lower()
    if mode == "auto":
        mode = "live" if _has_claude_credentials() else "mock"
    return Settings(
        mode=mode,
        onboarding_model=os.environ.get("ONBOARDING_MODEL", "claude-sonnet-5-5"),
        draft_model=os.environ.get("DRAFT_MODEL", "claude-sonnet-5-5"),
        classify_model=os.environ.get("CLASSIFY_MODEL", "claude-haiku-5-5"),
        web_internal_url=os.environ.get("WEB_INTERNAL_URL", "http://localhost:8080").rstrip("/"),
        internal_token=os.environ.get("INTERNAL_TOKEN", ""),
        max_tool_rounds=int(os.environ.get("MAX_TOOL_ROUNDS", "12")),
    )


settings = load()
