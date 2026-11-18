import json

import httpx
from anthropic.types.beta import BetaMessage

from app import onboarding
from app.claude import AIError, dump_content
from app.mock import MockGateway
from app.schemas import OnboardingTurnRequest
from app.tools import TOOLS, WebTools


class FakeTools:
    """Plays the Java internal API."""

    def __init__(self, taken=()):
        self.calls = []
        self.taken = set(taken)
        self.next_id = 100

    def execute(self, name, tool_input):
        self.calls.append((name, tool_input))
        if name == "add_product":
            self.next_id += 1
            return json.dumps({"ok": True, "product_id": self.next_id}), False
        if name == "propose_shop_address":
            if tool_input["slug"] in self.taken:
                return json.dumps({"ok": False, "reason": "taken", "alternatives": ["meena2"]}), True
            return json.dumps({"ok": True, "slug": tool_input["slug"]}), False
        return json.dumps({"ok": True}), False


class ScriptedGateway:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []

    def create(self, **kw):
        self.requests.append(kw)
        r = self.responses.pop(0)
        if isinstance(r, Exception):
            raise r
        return r


def msg(content, stop="tool_use"):
    return BetaMessage.model_validate({
        "id": "msg_x", "type": "message", "role": "assistant", "model": "claude-sonnet-5-5",
        "content": content, "stop_reason": stop, "stop_sequence": None,
        "usage": {"input_tokens": 1, "output_tokens": 1},
    })


def tool_use(i, name, inp):
    return {"type": "tool_use", "id": f"tu_{i}", "name": name, "input": inp}


FIRST_INPUT = [
    {"type": "text", "text": "Seller's name: Meena Devi\nLanguage: Hindi\nNotes: cakes and ladoos"},
    {"type": "text", "text": "Photo id: p1.jpg"},
]


def assert_valid_conversation(messages):
    """Roles alternate, and every tool_use is answered at the start of the next user message."""
    assert messages[0]["role"] == "user"
    for prev, cur in zip(messages, messages[1:]):
        assert prev["role"] != cur["role"], "consecutive messages with the same role"
    for i, m in enumerate(messages):
        if m["role"] != "assistant":
            continue
        ids = [b["id"] for b in m["content"] if b["type"] == "tool_use"]
        if not ids:
            continue
        if i + 1 == len(messages):
            continue  # still waiting for results
        nxt = messages[i + 1]["content"]
        lead = [b["tool_use_id"] for b in nxt[: len(ids)] if b.get("type") == "tool_result"]
        assert sorted(lead) == sorted(ids)
        assert not any(b.get("type") == "text" and b["text"] for b in nxt if b.get("type") == "tool_result")


def run(history, user_input, gateway, tools=None, max_rounds=12):
    req = OnboardingTurnRequest(session_id=1, seller_id=7, history=history, input=user_input)
    return onboarding.run_turn(req, gateway, tools or FakeTools(), "claude-sonnet-5-5", max_rounds)


def test_full_mock_conversation_with_follow_up_question():
    tools = FakeTools()
    gw = MockGateway()

    first = run([], FIRST_INPUT, gw, tools)
    assert first.status == "needs_input"
    assert "price" in first.question.lower()
    assert [n for n, _ in tools.calls] == ["set_shop_profile", "propose_shop_address", "add_product", "add_product"]
    # The pending results answer the ask_seller call; the admin's words are NOT inside them.
    assert len(first.pending_tool_results) == 1
    assert first.pending_tool_results[0]["content"] == onboarding.ASK_RESULT

    history = [{"role": "user", "content": FIRST_INPUT}] + first.appended
    second_input = first.pending_tool_results + [{"type": "text", "text": "The cake is 450 rupees"}]
    second = run(history, second_input, gw, tools)
    assert second.status == "submitted"
    update = [inp for n, inp in tools.calls if n == "update_product"][0]
    assert update["price_rupees"] == 450.0
    assert second.note_for_admin

    full = history + [{"role": "user", "content": second_input}] + second.appended
    assert_valid_conversation(full)


def test_taken_address_is_retried_with_alternative():
    tools = FakeTools(taken={"meena"})
    out = run([], FIRST_INPUT, MockGateway(), tools)
    slugs = [inp["slug"] for n, inp in tools.calls if n == "propose_shop_address"]
    assert slugs == ["meena", "meena2"]
    assert out.status == "needs_input"


def test_request_shape_is_stable_and_uses_thinking_binding():
    gw = ScriptedGateway(msg([{"type": "text", "text": "What do you sell?"}], stop="end_turn"))
    run([], FIRST_INPUT, gw)
    r = gw.requests[0]
    assert r["tools"] is TOOLS
    assert r["system"] == onboarding.SYSTEM_PROMPT
    assert r["thinking"]["block_binding"]["prefix_mismatch_behavior"] == "drop_block"
    assert "tool_choice" not in r  # forced tool choice is rejected on this model
    assert r["fallbacks"] == "default"


def test_text_only_answer_becomes_question():
    gw = ScriptedGateway(msg([{"type": "text", "text": "What do you sell?"}], stop="end_turn"))
    out = run([], FIRST_INPUT, gw)
    assert out.status == "needs_input" and out.question == "What do you sell?"
    assert out.pending_tool_results == []


def test_error_on_first_call_records_nothing():
    out = run([], FIRST_INPUT, ScriptedGateway(AIError("busy")))
    assert out.status == "failed" and out.appended == [] and out.error == "busy"


def test_error_after_tool_round_keeps_progress():
    gw = ScriptedGateway(
        msg([tool_use(1, "add_product", {"name": "Ladoo", "description": "", "price_rupees": 300, "unit": "per kg", "photo_id": None, "unsure_fields": []})]),
        AIError("busy"),
    )
    out = run([], FIRST_INPUT, gw)
    assert out.status == "needs_input" and out.error == "busy"
    assert [m["role"] for m in out.appended] == ["assistant"]
    assert [b["tool_use_id"] for b in out.pending_tool_results] == ["tu_1"]
    full = [{"role": "user", "content": FIRST_INPUT}] + out.appended + [
        {"role": "user", "content": out.pending_tool_results + [{"type": "text", "text": "continue"}]}]
    assert_valid_conversation(full)


def test_refusal_is_not_recorded():
    out = run([], FIRST_INPUT, ScriptedGateway(msg([], stop="refusal")))
    assert out.status == "failed" and out.appended == []


def test_round_cap():
    responses = [msg([tool_use(i, "propose_shop_address", {"slug": f"s{i}a"})]) for i in range(3)]
    out = run([], FIRST_INPUT, ScriptedGateway(*responses), max_rounds=3)
    assert out.status == "needs_input" and out.rounds == 3
    assert len(out.pending_tool_results) == 1
    assert [m["role"] for m in out.appended] == ["assistant", "user", "assistant", "user", "assistant"]


def test_failed_submit_does_not_end_turn():
    class RejectSubmit(FakeTools):
        def execute(self, name, tool_input):
            if name == "submit_for_review":
                return "Add at least one product first.", True
            return super().execute(name, tool_input)

    gw = ScriptedGateway(
        msg([tool_use(1, "submit_for_review", {"note_for_admin": "x"})]),
        msg([{"type": "text", "text": "What products do you make?"}], stop="end_turn"),
    )
    out = run([], FIRST_INPUT, gw, RejectSubmit())
    assert out.status == "needs_input"
    sent_results = gw.requests[1]["messages"][-1]["content"]
    assert sent_results[0]["is_error"] is True


def test_thinking_blocks_survive_serialization():
    m = msg([
        {"type": "thinking", "thinking": "", "signature": "sig123"},
        tool_use(1, "ask_seller", {"question": "Price?"}),
    ])
    dumped = dump_content(m)
    assert dumped[0] == {"type": "thinking", "thinking": "", "signature": "sig123"}
    assert dumped[1]["input"] == {"question": "Price?"}


def test_tool_schemas_are_strict():
    def walk(s):
        if "anyOf" in s:
            for sub in s["anyOf"]:
                walk(sub)
        if s.get("type") == "object":
            assert s["additionalProperties"] is False
            assert set(s["required"]) == set(s["properties"])
            for v in s["properties"].values():
                walk(v)
        if s.get("type") == "array":
            walk(s["items"])

    for t in TOOLS:
        assert t["strict"] is True
        walk(t["input_schema"])


def test_web_tools_scope_seller_and_map_errors():
    seen = []

    def handler(request: httpx.Request):
        seen.append((request.method, request.url.path, request.headers.get("X-Internal-Token")))
        if request.url.path.endswith("/address"):
            return httpx.Response(409, json={"error": "taken", "alternatives": ["meena2"]})
        if request.url.path.endswith("/submit"):
            return httpx.Response(400, json={"error": "Add at least one product first."})
        return httpx.Response(200, json={"ok": True, "product_id": 5})

    tools = WebTools("http://web:8080", "tok", 42, http=httpx.Client(transport=httpx.MockTransport(handler)))
    content, err = tools.execute("ADD_PRODUCT", {"name": "x"})
    assert not err and json.loads(content)["product_id"] == 5
    content, err = tools.execute("propose_shop_address", {"slug": "meena"})
    assert err and json.loads(content)["alternatives"] == ["meena2"]
    content, err = tools.execute("submit_for_review", {"note_for_admin": ""})
    assert err and content == "Add at least one product first."
    content, err = tools.execute("update_product", {"product_id": 9, "price_rupees": 10})
    assert not err
    content, err = tools.execute("delete_everything", {})
    assert err
    assert seen == [
        ("POST", "/internal/agent/sellers/42/products", "tok"),
        ("POST", "/internal/agent/sellers/42/address", "tok"),
        ("POST", "/internal/agent/sellers/42/submit", "tok"),
        ("PATCH", "/internal/agent/sellers/42/products/9", "tok"),
    ]
