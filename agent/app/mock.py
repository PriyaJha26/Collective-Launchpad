"""Scripted stand-in for Claude, used when AGENT_MODE=mock (no API key) and in tests.

It returns real SDK message objects and drives the real tool loop, so the Java tool endpoints,
the follow-up question flow and history storage are all exercised offline. Output is marked
[MOCK] so nobody mistakes it for Claude's work.
"""

import itertools
import json
import re
from typing import Any

from anthropic.types.beta import BetaMessage

_ids = itertools.count(1)


def _message(content: list[dict], stop_reason: str, model: str) -> BetaMessage:
    return BetaMessage.model_validate({
        "id": f"msg_mock_{next(_ids)}",
        "type": "message",
        "role": "assistant",
        "model": model,
        "content": content,
        "stop_reason": stop_reason,
        "stop_sequence": None,
        "usage": {"input_tokens": 0, "output_tokens": 0},
    })


def _tool_use(name: str, tool_input: dict) -> dict:
    return {"type": "tool_use", "id": f"toolu_mock_{next(_ids)}", "name": name, "input": tool_input}


def _all_text(messages: list[dict]) -> str:
    parts = []
    for m in messages:
        content = m["content"]
        if isinstance(content, str):
            parts.append(content)
            continue
        for b in content:
            if b.get("type") == "text":
                parts.append(b["text"])
    return "\n".join(parts)


def _tool_calls(messages: list[dict]) -> list[dict]:
    return [b for m in messages if m["role"] == "assistant" for b in m["content"] if b.get("type") == "tool_use"]


def _tool_results(messages: list[dict]) -> dict[str, dict]:
    out = {}
    for m in messages:
        if m["role"] == "user" and isinstance(m["content"], list):
            for b in m["content"]:
                if b.get("type") == "tool_result":
                    out[b["tool_use_id"]] = b
    return out


class MockGateway:
    def create(self, **kw: Any) -> BetaMessage:
        model = kw.get("model", "mock")
        if kw.get("tools"):
            return self._onboarding(kw["messages"], model)
        schema = kw.get("output_config", {}).get("format", {}).get("schema", {})
        if "category" in schema.get("properties", {}):
            return self._classify(kw["messages"], model)
        return self._draft(kw["messages"], model)

    # ---- onboarding: profile + address + products, one question, then submit ----

    def _onboarding(self, messages: list[dict], model: str) -> BetaMessage:
        calls = _tool_calls(messages)
        called = {c["name"] for c in calls}
        text = _all_text(messages)

        if "set_shop_profile" not in called:
            name = (re.search(r"Seller's name:\s*(.+)", text) or [None, "Seller"])[1].strip()
            first = name.split()[0]
            photos = re.findall(r"Photo id:\s*(\S+)", text)
            slug = re.sub(r"[^a-z0-9]", "", first.lower()) or "seller"
            if not slug[0].isalpha():
                slug = "shop" + slug
            content = [
                {"type": "text", "text": "[MOCK] Setting up the shop from the notes and photos."},
                _tool_use("set_shop_profile", {
                    "shop_name": f"{first}'s Kitchen",
                    "bio": f"[MOCK] I am {first}. I make fresh sweets and snacks at home and sell them to families in my village.",
                    "location": None,
                    "unsure_fields": ["location"],
                }),
                _tool_use("propose_shop_address", {"slug": slug}),
                _tool_use("add_product", {
                    "name": "Besan ladoo", "description": "[MOCK] Gram-flour ladoos made in ghee.",
                    "price_rupees": 300, "unit": "per kg", "photo_id": photos[0] if photos else None, "unsure_fields": [],
                }),
                _tool_use("add_product", {
                    "name": "Chocolate cake", "description": "[MOCK] Soft chocolate cake for birthdays.",
                    "price_rupees": None, "unit": "per 500 g", "photo_id": photos[1] if len(photos) > 1 else None,
                    "unsure_fields": ["price"],
                }),
            ]
            return _message(content, "tool_use", model)

        results = _tool_results(messages)
        address_calls = [c for c in calls if c["name"] == "propose_shop_address"]
        last_address = results.get(address_calls[-1]["id"], {}) if address_calls else {}
        if last_address.get("is_error"):
            try:
                alternatives = json.loads(last_address["content"]).get("alternatives", [])
            except (ValueError, TypeError, AttributeError):
                alternatives = re.findall(r"[a-z][a-z0-9-]+\d", str(last_address.get("content", "")))
            slug = alternatives[0] if alternatives else f"shop{next(_ids)}"
            return _message([_tool_use("propose_shop_address", {"slug": slug})], "tool_use", model)

        if "ask_seller" not in called:
            return _message([_tool_use("ask_seller", {"question": "[MOCK] What is the price of the chocolate cake?"})], "tool_use", model)

        if "submit_for_review" not in called:
            cake_id = None
            for c in calls:
                if c["name"] == "add_product" and c["input"]["name"] == "Chocolate cake":
                    try:
                        cake_id = json.loads(results[c["id"]]["content"])["product_id"]
                    except (KeyError, ValueError, TypeError):
                        pass
            answer = text.rsplit("\n", 1)[-1]
            price = re.search(r"\d+", answer)
            content = []
            if cake_id is not None:
                content.append(_tool_use("update_product", {
                    "product_id": cake_id, "name": None, "description": None,
                    "price_rupees": float(price.group()) if price else 450.0, "unit": None, "photo_id": None,
                    "is_available": None, "unsure_fields": [] if price else ["price"],
                }))
            content.append(_tool_use("submit_for_review", {"note_for_admin": "[MOCK] Please check the location and prices."}))
            return _message(content, "tool_use", model)

        return _message([{"type": "text", "text": "[MOCK] The draft is with the admin for review."}], "end_turn", model)

    # ---- enquiries ----

    def _classify(self, messages: list[dict], model: str) -> BetaMessage:
        msg = _all_text(messages).lower()

        def has(*words: str) -> bool:
            return any(re.search(rf"(?<!\w){re.escape(w)}(?!\w)", msg) for w in words)

        if has("refund", "broken", "bad", "complaint", "stale"):
            category, urgency = "complaint", "high"
        elif has("order", "buy", "want", "need"):
            category, urgency = "order", "normal"
        elif has("price", "cost", "how much"):
            category, urgency = "price_question", "normal"
        elif has("deliver", "delivery", "ship"):
            category, urgency = "delivery", "normal"
        else:
            category, urgency = "other", "low"
        body = json.dumps({"category": category, "urgency": urgency, "language": "en"})
        return _message([{"type": "text", "text": body}], "end_turn", model)

    def _draft(self, messages: list[dict], model: str) -> BetaMessage:
        text = _all_text(messages)
        customer = (re.search(r"Customer name \(as typed on the form\):\s*(.+)", text) or [None, ""])[1].strip()
        seller = re.search(r"Seller:\s*([^,]+), shop \"([^\"]+)\"", text)
        seller_name, shop = (seller.group(1), seller.group(2)) if seller else ("The seller", "our shop")
        greeting = customer if customer and customer != "not given" else "there"
        body = json.dumps({
            "reply": (
                f"Hello {greeting},\n\nThank you for your message to {shop}. "
                "I have noted your request and will confirm the details with you very soon.\n\n"
                f"Warm regards,\n{seller_name}\n{shop}"
            ),
            "check_points": ["[MOCK] This is a placeholder draft. Check the details with the seller."],
        })
        return _message([{"type": "text", "text": body}], "end_turn", model)
