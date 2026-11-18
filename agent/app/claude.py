"""Thin wrapper around the Anthropic client: error mapping, content serialization, image preparation.

Everything that talks to Claude goes through a `Gateway`, so tests and the offline mock mode
can swap in a scripted fake without touching the agent logic.
"""

import base64
import io
import logging
from typing import Any, Protocol

import anthropic
from anthropic.types.beta import BetaMessage
from PIL import Image, ImageOps

log = logging.getLogger("launchpad.claude")

# Server-side refusal fallback: on a policy decline the API re-runs the request on a fallback
# model inside the same call (Claude Sonnet 5.5 -> Claude Sonnet 5 for the categories it covers).
FALLBACK_BETA = "server-side-fallback-2026-07-01"
# Lets us ask the API to drop (not reject) a thinking block whose history no longer matches.
# Our histories are append-only, so this is a safety net, not the normal path.
THINKING_BINDING_BETA = "thinking-binding-controls-2026-08-01"

MAX_IMAGE_SIDE = 1568


class AIError(Exception):
    """Claude could not produce a usable answer. The message is safe to show the admin."""


class Gateway(Protocol):
    def create(self, **kwargs: Any) -> BetaMessage: ...


class LiveGateway:
    def __init__(self) -> None:
        # Resolves ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN / an `ant auth login` profile.
        self._client = anthropic.Anthropic(max_retries=3)

    def create(self, **kwargs: Any) -> BetaMessage:
        try:
            return self._client.beta.messages.create(**kwargs)
        except anthropic.AuthenticationError as e:
            raise AIError("Claude API credentials are missing or invalid.") from e
        except anthropic.PermissionDeniedError as e:
            raise AIError("This API key is not allowed to use the model.") from e
        except anthropic.NotFoundError as e:
            raise AIError(f"Model not found: {kwargs.get('model')}.") from e
        except anthropic.RateLimitError as e:
            raise AIError("Claude is busy right now (rate limited). Please try again in a minute.") from e
        except anthropic.BadRequestError as e:
            log.warning("Claude rejected the request: %s", e.message)
            raise AIError(f"Claude rejected the request: {e.message}") from e
        except anthropic.APIStatusError as e:
            raise AIError(f"Claude API error {e.status_code}. Please retry.") from e
        except anthropic.APIConnectionError as e:
            raise AIError("Could not reach the Claude API.") from e


def dump_content(message: BetaMessage) -> list[dict]:
    """Serialize an assistant turn so it can be stored and sent back later unchanged.

    Thinking blocks (even with empty text), tool_use blocks and fallback blocks are all kept:
    the history must be replayed exactly as the API returned it.
    """
    return [block.to_dict(mode="json", exclude_none=True) for block in message.content]


def text_of(message: BetaMessage) -> str:
    return "\n".join(b.text for b in message.content if b.type == "text").strip()


def log_usage(label: str, message: BetaMessage) -> None:
    u = message.usage
    log.info(
        "%s model=%s stop=%s in=%s out=%s cache_read=%s cache_write=%s",
        label, message.model, message.stop_reason, u.input_tokens, u.output_tokens,
        getattr(u, "cache_read_input_tokens", None), getattr(u, "cache_creation_input_tokens", None),
    )


# ---------------------------------------------------------------------------
# Images
# ---------------------------------------------------------------------------

def _prepare_image_block(block: dict) -> dict:
    """Downscale oversized photos. Deterministic: the same input bytes always give the same
    output bytes, which keeps earlier turns byte-identical across requests (prompt cache and
    thinking-block binding both depend on that)."""
    source = block.get("source") or {}
    if source.get("type") != "base64":
        return block
    raw = base64.b64decode(source["data"])
    img = Image.open(io.BytesIO(raw))
    if max(img.size) <= MAX_IMAGE_SIDE and source.get("media_type") in ("image/jpeg", "image/png", "image/webp"):
        return block
    img = ImageOps.exif_transpose(img).convert("RGB")
    img.thumbnail((MAX_IMAGE_SIDE, MAX_IMAGE_SIDE))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=85)
    return {
        **block,
        "source": {"type": "base64", "media_type": "image/jpeg", "data": base64.standard_b64encode(buf.getvalue()).decode()},
    }


def prepare_content(content: list[dict]) -> list[dict]:
    return [_prepare_image_block(b) if b.get("type") == "image" else b for b in content]


def prepare_messages(messages: list[dict]) -> list[dict]:
    out = []
    for m in messages:
        content = m.get("content")
        out.append({**m, "content": prepare_content(content)} if isinstance(content, list) else m)
    return out
