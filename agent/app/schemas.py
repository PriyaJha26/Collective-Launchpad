"""Request and response models for the endpoints the Java web service calls."""

from typing import Literal

from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Onboarding
# ---------------------------------------------------------------------------


class OnboardingTurnRequest(BaseModel):
    session_id: int
    seller_id: int
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


CLASSIFY_SCHEMA = {
    "type": "object",
    "properties": {
        "category": {"type": "string", "enum": list(Category.__args__)},
        "urgency": {"type": "string", "enum": ["low", "normal", "high"]},
    },
    "required": ["category", "urgency"],
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


class DraftReplyResult(BaseModel):
    reply: str
    check_points: list[str]


DRAFT_SCHEMA = {
    "type": "object",
    "properties": {
        "reply": {"type": "string", "description": "The reply to send, in English."},
        "check_points": {
            "type": "array",
            "items": {"type": "string"},
            "description": "Short points to confirm before sending, in English. Empty if none.",
        },
    },
    "required": ["reply", "check_points"],
    "additionalProperties": False,
}
