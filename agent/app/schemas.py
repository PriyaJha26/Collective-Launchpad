"""Request and response models for the endpoints the Java web service calls."""

from typing import Literal

from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Onboarding
# ---------------------------------------------------------------------------


class OnboardingTurnRequest(BaseModel):
    session_id: int
    seller_id: int
    language: str = ""
    # Earlier messages exactly as returned by previous turns (images expanded to base64 by Java).
    history: list[dict] = Field(default_factory=list)
    # Content blocks of the new user message: first any pending tool_result blocks, then the
    # admin's text and new photos.
    input: list[dict]


class OnboardingTurnResponse(BaseModel):
    # needs_input: the admin must answer `question` (or say "continue").
    # submitted:   the agent called submit_for_review; the draft is ready for the admin.
    # failed:      nothing happened this turn; resend the same input to retry.
    status: Literal["needs_input", "submitted", "failed"]
    # Messages to append to the stored history AFTER the input user message, in order.
    appended: list[dict] = Field(default_factory=list)
    # tool_result blocks that must open the next user message.
    pending_tool_results: list[dict] = Field(default_factory=list)
    question: str | None = None
    note_for_admin: str | None = None
    error: str | None = None
    rounds: int = 0


# ---------------------------------------------------------------------------
# Enquiries
# ---------------------------------------------------------------------------

Category = Literal["order", "price_question", "availability", "custom_request", "delivery", "complaint", "spam", "other"]


class ClassifyRequest(BaseModel):
    customer_name: str = ""
    message: str


class ClassifyResult(BaseModel):
    category: Category
    urgency: Literal["low", "normal", "high"]
    language: str = Field(description="ISO 639-1 code of the language the customer wrote in")


CLASSIFY_SCHEMA = {
    "type": "object",
    "properties": {
        "category": {"type": "string", "enum": list(Category.__args__)},
        "urgency": {"type": "string", "enum": ["low", "normal", "high"]},
        "language": {"type": "string", "description": "ISO 639-1 code of the customer's language, e.g. en, hi, mr"},
    },
    "required": ["category", "urgency", "language"],
    "additionalProperties": False,
}


class CatalogueItem(BaseModel):
    name: str
    description: str = ""
    price_rupees: float | None = None
    unit: str = ""
    available: bool = True


class SellerBrief(BaseModel):
    name: str
    shop_name: str
    bio: str = ""
    location: str = ""


class EnquiryBrief(BaseModel):
    customer_name: str = ""
    message: str
    category: str | None = None
    # What the customer picked on the form: question, discount, custom, order
    topic: str | None = None


class DraftReplyRequest(BaseModel):
    group_name: str
    seller: SellerBrief
    catalogue: list[CatalogueItem] = Field(default_factory=list)
    enquiry: EnquiryBrief
    # The seller reads and edits the draft in their own language; it is translated for the customer on sending.
    reply_language: str = ""


class DraftReplyResult(BaseModel):
    reply: str
    check_points: list[str]


DRAFT_SCHEMA = {
    "type": "object",
    "properties": {
        "reply": {"type": "string", "description": "The reply to send, in the requested reply language."},
        "check_points": {
            "type": "array",
            "items": {"type": "string"},
            "description": "Short points to confirm before sending, in the reply language. Empty if none.",
        },
    },
    "required": ["reply", "check_points"],
    "additionalProperties": False,
}


# ---------------------------------------------------------------------------
# Translation (join requests written in a local language -> English for the superadmin)
# ---------------------------------------------------------------------------


class TranslateRequest(BaseModel):
    fields: dict[str, str]


class FieldTranslation(BaseModel):
    field: str
    english: str


class TranslateResult(BaseModel):
    source_language: str
    translations: list[FieldTranslation]


TRANSLATE_SCHEMA = {
    "type": "object",
    "properties": {
        "source_language": {"type": "string", "description": "ISO 639-1 code of the main language of the input, e.g. hi, ta"},
        "translations": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"field": {"type": "string"}, "english": {"type": "string"}},
                "required": ["field", "english"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["source_language", "translations"],
    "additionalProperties": False,
}


# ---------------------------------------------------------------------------
# Translation into the reader's language (messages, replies, shop and product texts)
# ---------------------------------------------------------------------------

LANGUAGE_NAMES = {
    "en": "English", "hi": "Hindi", "mr": "Marathi", "ta": "Tamil", "te": "Telugu", "kn": "Kannada",
    "ml": "Malayalam", "bn": "Bengali", "gu": "Gujarati", "pa": "Punjabi", "or": "Odia", "ur": "Urdu",
}


class TranslateToRequest(BaseModel):
    fields: dict[str, str]
    targets: list[str]


class TranslateToResult(BaseModel):
    source_language: str
    # {target language: {field: text}}
    results: dict[str, dict[str, str]]


class _TranslatedField(BaseModel):
    language: str
    field: str
    text: str


class TranslateToRaw(BaseModel):
    source_language: str
    translations: list[_TranslatedField]


TRANSLATE_TO_SCHEMA = {
    "type": "object",
    "properties": {
        "source_language": {"type": "string", "description": "ISO 639-1 code of the main language of the input, e.g. hi, ta, en"},
        "translations": {
            "type": "array",
            "description": "One item per (target language, field) pair.",
            "items": {
                "type": "object",
                "properties": {
                    "language": {"type": "string", "enum": list(LANGUAGE_NAMES)},
                    "field": {"type": "string"},
                    "text": {"type": "string"},
                },
                "required": ["language", "field", "text"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["source_language", "translations"],
    "additionalProperties": False,
}
