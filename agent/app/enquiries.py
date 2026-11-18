"""Enquiry classification (Claude Haiku 5.5) and reply drafting (Claude Sonnet 5.5).

No tools here: the enquiry is untrusted text, so the worst it can do is produce a bad draft,
which the admin sees before anything is sent.
"""

import json
import logging
from importlib import resources

from pydantic import BaseModel, ValidationError

from app.claude import FALLBACK_BETA, AIError, Gateway, log_usage, text_of
from app.schemas import (
    CLASSIFY_SCHEMA,
    DRAFT_SCHEMA,
    ClassifyRequest,
    ClassifyResult,
    DraftReplyRequest,
    DraftReplyResult,
)

log = logging.getLogger("launchpad.enquiries")

_PROMPTS = resources.files("app").joinpath("prompts")
CLASSIFY_PROMPT = _PROMPTS.joinpath("classify.md").read_text(encoding="utf-8")
DRAFT_PROMPT = _PROMPTS.joinpath("draft_reply.md").read_text(encoding="utf-8")


def _structured(gateway: Gateway, label: str, model_cls: type[BaseModel], attempts: int = 2, **request) -> BaseModel:
    """Call Claude for JSON, validate it with Pydantic, retry once on an unusable answer."""
    last_problem = ""
    for attempt in range(1, attempts + 1):
        response = gateway.create(**request)
        log_usage(f"{label} attempt={attempt}", response)
        if response.stop_reason == "refusal":
            last_problem = "Claude declined this request"
            continue
        if response.stop_reason == "max_tokens":
            last_problem = "the answer was cut off"
            continue
        try:
            return model_cls.model_validate_json(text_of(response))
        except (ValidationError, json.JSONDecodeError, ValueError) as e:
            last_problem = f"invalid answer ({e.__class__.__name__})"
            log.warning("%s: %s", label, e)
    raise AIError(f"Could not get a usable answer: {last_problem}.")


def classify(req: ClassifyRequest, gateway: Gateway, model: str) -> ClassifyResult:
    text = f"Customer name: {req.customer_name or 'not given'}\n<enquiry>\n{req.message}\n</enquiry>"
    return _structured(
        gateway, "classify", ClassifyResult,
        model=model,
        max_tokens=2000,
        system=CLASSIFY_PROMPT,
        messages=[{"role": "user", "content": text}],
        output_config={"effort": "low", "format": {"type": "json_schema", "schema": CLASSIFY_SCHEMA}},
    )


def draft_reply(req: DraftReplyRequest, gateway: Gateway, model: str) -> DraftReplyResult:
    lines = []
    for item in req.catalogue:
        price = f"₹{item.price_rupees:g} {item.unit}".strip() if item.price_rupees is not None else "price not listed"
        stock = "" if item.available else " (currently unavailable)"
        lines.append(f"- {item.name}: {price}{stock}. {item.description}".rstrip())
    catalogue = "\n".join(lines) or "(no products listed)"
    s = req.seller
    text = (
        f"Group: {req.group_name}\n"
        f"Seller: {s.name}, shop \"{s.shop_name}\"{', ' + s.location if s.location else ''}\n"
        f"About the shop: {s.bio or '-'}\n\n"
        f"Catalogue:\n{catalogue}\n\n"
        f"Category (automatic, may be wrong): {req.enquiry.category or 'unknown'}\n"
        f"Customer name (as typed on the form): {req.enquiry.customer_name or 'not given'}\n"
        f"<enquiry>\n{req.enquiry.message}\n</enquiry>"
    )
    return _structured(
        gateway, "draft_reply", DraftReplyResult,
        model=model,
        max_tokens=8000,
        system=DRAFT_PROMPT,
        messages=[{"role": "user", "content": text}],
        output_config={"effort": "low", "format": {"type": "json_schema", "schema": DRAFT_SCHEMA}},
        betas=[FALLBACK_BETA],
        fallbacks="default",
    )
