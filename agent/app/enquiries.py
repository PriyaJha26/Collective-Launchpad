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
    LANGUAGE_NAMES,
    TRANSLATE_SCHEMA,
    TRANSLATE_TO_SCHEMA,
    ClassifyRequest,
    ClassifyResult,
    DraftReplyRequest,
    DraftReplyResult,
    TranslateRequest,
    TranslateResult,
    TranslateToRaw,
    TranslateToRequest,
    TranslateToResult,
)

log = logging.getLogger("launchpad.enquiries")

_PROMPTS = resources.files("app").joinpath("prompts")
CLASSIFY_PROMPT = _PROMPTS.joinpath("classify.md").read_text(encoding="utf-8")
DRAFT_PROMPT = _PROMPTS.joinpath("draft_reply.md").read_text(encoding="utf-8")
TRANSLATE_PROMPT = _PROMPTS.joinpath("translate.md").read_text(encoding="utf-8")
TRANSLATE_TO_PROMPT = _PROMPTS.joinpath("translate_to.md").read_text(encoding="utf-8")


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


_TOPICS = {
    "question": "a question",
    "discount": "asking for a discount",
    "custom": "a custom / made-to-order request",
    "order": "about an order",
}


def translate(req: TranslateRequest, gateway: Gateway, model: str) -> TranslateResult:
    parts = "\n".join(f'<field name="{k}">\n{v}\n</field>' for k, v in req.fields.items() if v and v.strip())
    result = _structured(
        gateway, "translate", TranslateResult,
        model=model,
        max_tokens=4000,
        system=TRANSLATE_PROMPT,
        messages=[{"role": "user", "content": f"Translate each field into English.\n\n{parts}"}],
        output_config={"effort": "low", "format": {"type": "json_schema", "schema": TRANSLATE_SCHEMA}},
    )
    # Keep only fields that were asked for.
    result.translations = [t for t in result.translations if t.field in req.fields]
    return result


def translate_to(req: TranslateToRequest, gateway: Gateway, model: str) -> TranslateToResult:
    """Translates every field into every target language. Fields or languages Claude leaves out
    fall back to the original text, so callers always get a complete answer."""
    fields = {k: v for k, v in req.fields.items() if v and v.strip()}
    targets = list(dict.fromkeys(t for t in req.targets if t in LANGUAGE_NAMES))
    if not fields:
        return TranslateToResult(source_language="", results={t: dict(req.fields) for t in targets})
    parts = "\n".join(f'<field name="{k}">\n{v}\n</field>' for k, v in fields.items())
    wanted = ", ".join(f"{LANGUAGE_NAMES[t]} ({t})" for t in targets)
    raw = _structured(
        gateway, "translate_to", TranslateToRaw,
        model=model,
        max_tokens=16000,
        system=TRANSLATE_TO_PROMPT,
        messages=[{"role": "user", "content": f"Target languages: {wanted}\n\n{parts}"}],
        output_config={"effort": "low", "format": {"type": "json_schema", "schema": TRANSLATE_TO_SCHEMA}},
    )
    results = {t: dict(req.fields) for t in targets}
    for item in raw.translations:
        if item.language in results and item.field in fields and item.text.strip():
            results[item.language][item.field] = item.text
    return TranslateToResult(source_language=raw.source_language, results=results)


def draft_reply(req: DraftReplyRequest, gateway: Gateway, model: str) -> DraftReplyResult:
    lines = []
    for item in req.catalogue:
        price = f"₹{item.price_rupees:g} {item.unit}".strip() if item.price_rupees is not None else "price not listed"
        stock = "" if item.available else " (currently unavailable)"
        lines.append(f"- {item.name}: {price}{stock}. {item.description}".rstrip())
    catalogue = "\n".join(lines) or "(no products listed)"
    s = req.seller
    reply_language = LANGUAGE_NAMES.get(req.reply_language, "the language the customer wrote in")
    text = (
        f"Reply language: {reply_language}\n"
        f"Group: {req.group_name}\n"
        f"Seller: {s.name}, shop \"{s.shop_name}\"{', ' + s.location if s.location else ''}\n"
        f"About the shop: {s.bio or '-'}\n\n"
        f"Catalogue:\n{catalogue}\n\n"
        f"Category (automatic, may be wrong): {req.enquiry.category or 'unknown'}\n"
        f"Customer chose on the form: {_TOPICS.get(req.enquiry.topic or '', 'not given')}\n"
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
