"""Onboarding tools: definitions Claude sees, and how each call is carried out.

Every data change goes through the Java web service's internal API. The seller id comes from
the Java request that started the turn and is attached here, never taken from the model, so
Claude cannot touch another seller. Java also refuses changes to sellers that are published.
"""

import json
import logging

import httpx

log = logging.getLogger("launchpad.tools")


def _nullable(schema: dict) -> dict:
    return {"anyOf": [schema, {"type": "null"}]}


_PRODUCT_FIELDS = ["name", "description", "price", "unit", "photo"]

TOOLS: list[dict] = [
    {
        "name": "set_shop_profile",
        "description": (
            "Set or replace the shop's profile: shop name, a short bio and the location. Call it once you know "
            "enough; call again to correct it. List in unsure_fields any value you guessed or could not read clearly."
        ),
        "strict": True,
        "input_schema": {
            "type": "object",
            "properties": {
                "shop_name": {"type": "string", "description": "Short, warm shop name, e.g. \"Meena's Kitchen\"."},
                "bio": {"type": "string", "description": "2 to 4 sentences in first person, as the seller."},
                "location": _nullable({"type": "string", "description": "Village / town and district, if known."}),
                "unsure_fields": {"type": "array", "items": {"type": "string", "enum": ["shop_name", "bio", "location"]}},
            },
            "required": ["shop_name", "bio", "location", "unsure_fields"],
            "additionalProperties": False,
        },
    },
    {
        "name": "add_product",
        "description": (
            "Add one product to the draft shop. Returns the new product_id. Call it once per distinct product; "
            "you may add several products in one step. Use price_rupees null when no price was given."
        ),
        "strict": True,
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {"type": "string"},
                "description": {"type": "string", "description": "One or two plain sentences a customer would find useful."},
                "price_rupees": _nullable({"type": "number"}),
                "unit": {"type": "string", "description": "What the price is for: 'each', 'per kg', 'per 500 g jar', 'set of 6'."},
                "photo_id": _nullable({"type": "string", "description": "Id of the photo that shows this product, exactly as given."}),
                "unsure_fields": {"type": "array", "items": {"type": "string", "enum": _PRODUCT_FIELDS}},
            },
            "required": ["name", "description", "price_rupees", "unit", "photo_id", "unsure_fields"],
            "additionalProperties": False,
        },
    },
    {
        "name": "update_product",
        "description": "Change a product you added earlier. Fields set to null are left unchanged.",
        "strict": True,
        "input_schema": {
            "type": "object",
            "properties": {
                "product_id": {"type": "integer"},
                "name": _nullable({"type": "string"}),
                "description": _nullable({"type": "string"}),
                "price_rupees": _nullable({"type": "number"}),
                "unit": _nullable({"type": "string"}),
                "photo_id": _nullable({"type": "string"}),
                "is_available": _nullable({"type": "boolean"}),
                "unsure_fields": _nullable({"type": "array", "items": {"type": "string", "enum": _PRODUCT_FIELDS}}),
            },
            "required": ["product_id", "name", "description", "price_rupees", "unit", "photo_id", "is_available", "unsure_fields"],
            "additionalProperties": False,
        },
    },
    {
        "name": "propose_shop_address",
        "description": (
            "Reserve the shop's web address (the part before the group's domain). Use lowercase Latin letters, digits "
            "and hyphens, 2 to 30 characters, starting with a letter, usually the seller's first name transliterated "
            "(मीना -> meena). Returns ok, or the reason it is not allowed plus free alternatives to choose from."
        ),
        "strict": True,
        "input_schema": {
            "type": "object",
            "properties": {"slug": {"type": "string"}},
            "required": ["slug"],
            "additionalProperties": False,
        },
    },
    {
        "name": "ask_seller",
        "description": (
            "Ask the seller one question through the group admin, when something essential is missing or unreadable. "
            "Your turn ends here; the answer arrives in the next user message. Ask in short, simple English."
        ),
        "strict": True,
        "input_schema": {
            "type": "object",
            "properties": {"question": {"type": "string"}},
            "required": ["question"],
            "additionalProperties": False,
        },
    },
    {
        "name": "submit_for_review",
        "description": (
            "Hand the finished draft to the group admin for review. Needs a profile, a shop address and at least one "
            "product. The admin checks everything and publishes; you cannot publish."
        ),
        "strict": True,
        "input_schema": {
            "type": "object",
            "properties": {
                "note_for_admin": {"type": "string", "description": "What the admin should double-check, in a few short lines."},
            },
            "required": ["note_for_admin"],
            "additionalProperties": False,
        },
    },
]

TOOL_NAMES = {t["name"] for t in TOOLS}
TURN_ENDING_TOOLS = {"ask_seller", "submit_for_review"}


def resolve_name(name: str) -> str | None:
    """Accept a tool name that differs only in letter case."""
    if name in TOOL_NAMES:
        return name
    matches = [t for t in TOOL_NAMES if t.lower() == name.lower()]
    return matches[0] if len(matches) == 1 else None


class WebTools:
    """Carries out tool calls for one seller by calling the Java internal API."""

    def __init__(self, base_url: str, token: str, seller_id: int, http: httpx.Client | None = None):
        self.seller_id = seller_id
        self._base = f"{base_url}/internal/agent/sellers/{seller_id}"
        self._http = http or httpx.Client(timeout=20.0)
        self._headers = {"X-Internal-Token": token}

    def execute(self, name: str, tool_input: dict) -> tuple[str, bool]:
        """Returns (tool_result content, is_error)."""
        resolved = resolve_name(name)
        if resolved is None:
            return f"Unknown tool '{name}'. Available tools: {', '.join(sorted(TOOL_NAMES))}.", True
        try:
            if resolved == "set_shop_profile":
                return self._call("POST", "/profile", tool_input)
            if resolved == "add_product":
                return self._call("POST", "/products", tool_input)
            if resolved == "update_product":
                body = {k: v for k, v in tool_input.items() if k != "product_id"}
                return self._call("PATCH", f"/products/{int(tool_input['product_id'])}", body)
            if resolved == "propose_shop_address":
                return self._call("POST", "/address", tool_input)
            if resolved == "submit_for_review":
                return self._call("POST", "/submit", tool_input)
        except (KeyError, ValueError, TypeError) as e:
            return f"Invalid input for {resolved}: {e}", True
        return f"Tool '{resolved}' is not handled here.", True

    def _call(self, method: str, path: str, body: dict) -> tuple[str, bool]:
        try:
            res = self._http.request(method, self._base + path, json=body, headers=self._headers)
        except httpx.HTTPError as e:
            log.warning("internal API unreachable: %s", e)
            return "The shop database is not reachable right now. Try again later in this conversation.", True
        if res.status_code >= 500:
            log.warning("internal API %s %s -> %s", method, path, res.status_code)
            return "The shop database had an internal error.", True
        try:
            payload = res.json()
        except json.JSONDecodeError:
            payload = {"error": res.text[:300]}
        if res.status_code >= 400:
            # Keep extra fields (e.g. free alternatives for a taken address) so Claude can use them.
            if set(payload) == {"error"}:
                return str(payload["error"]), True
            return json.dumps(payload, ensure_ascii=False), True
        return json.dumps(payload, ensure_ascii=False), False
