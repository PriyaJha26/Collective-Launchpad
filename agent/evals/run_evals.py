"""Rerun the saved cases against real Claude after every prompt change.

    cd agent && ANTHROPIC_API_KEY=... .venv/bin/python -m evals.run_evals            # enquiries
    cd agent && ANTHROPIC_API_KEY=... .venv/bin/python -m evals.run_evals onboarding # photo cases

Enquiry cases (evals/cases/enquiries.json) are checked automatically: category, urgency,
language and script of the reply, forbidden phrases, and that uncertain points are flagged.
Onboarding cases (evals/onboarding/<case>/notes.txt + photos) run the real tool loop against a
recording fake of the Java API and print every tool call for you to read.
Each run costs a few cents of API usage.
"""

import base64
import json
import re
import sys
from pathlib import Path

from app import enquiries, onboarding
from app.claude import LiveGateway
from app.config import settings
from app.schemas import ClassifyRequest, DraftReplyRequest, OnboardingTurnRequest

HERE = Path(__file__).parent

CATALOGUE = {
    "group_name": "Our Village Crafts",
    "seller": {"name": "Meena Devi", "shop_name": "Meena's Kitchen", "bio": "Home-made sweets from Wardha.", "location": "Wardha"},
    "catalogue": [
        {"name": "Besan ladoo", "price_rupees": 300, "unit": "per kg", "description": "Made in ghee."},
        {"name": "Chocolate cake", "price_rupees": 450, "unit": "per 500 g", "description": "Made to order."},
        {"name": "Lemon pickle", "price_rupees": 220, "unit": "per 500 g jar", "available": False},
    ],
}


def _script(text: str) -> str:
    if re.search(r"[ऀ-ॿ]", text):
        return "devanagari"
    if re.search(r"[஀-௿]", text):
        return "tamil"
    return "latin"


def run_enquiries(gw) -> int:
    cases = json.loads((HERE / "cases" / "enquiries.json").read_text(encoding="utf-8"))
    failures = 0
    for case in cases:
        e, x = case["enquiry"], case["expect"]
        problems = []
        c = enquiries.classify(ClassifyRequest(**e), gw, settings.classify_model)
        d = enquiries.draft_reply(DraftReplyRequest.model_validate({**CATALOGUE, "enquiry": {**e, "category": c.category}}),
                                  gw, settings.draft_model)
        reply = d.reply.lower()
        if "category" in x and c.category not in x["category"]:
            problems.append(f"category {c.category}, expected {x['category']}")
        if "urgency" in x and c.urgency not in x["urgency"]:
            problems.append(f"urgency {c.urgency}, expected {x['urgency']}")
        if "language" in x and c.language != x["language"]:
            problems.append(f"language {c.language}, expected {x['language']}")
        if "reply_script" in x and _script(d.reply) != x["reply_script"]:
            problems.append(f"reply is in {_script(d.reply)} script")
        for bad in x.get("must_not_contain", []):
            if bad.lower() in reply:
                problems.append(f"reply contains '{bad}'")
        if "reply_mentions_any" in x and not any(m.lower() in reply for m in x["reply_mentions_any"]):
            problems.append(f"reply mentions none of {x['reply_mentions_any']}")
        if len(d.check_points) < x.get("check_points_min", 0):
            problems.append("no check points for the admin")
        status = "PASS" if not problems else "FAIL"
        failures += bool(problems)
        print(f"\n[{status}] {case['id']}: {c.category}/{c.urgency}/{c.language}")
        print("  reply:", d.reply.replace("\n", " ")[:220])
        if d.check_points:
            print("  check:", "; ".join(d.check_points))
        for p in problems:
            print("  !!", p)
    print(f"\n{len(cases) - failures}/{len(cases)} enquiry cases passed")
    return failures


class RecordingTools:
    """Pretends to be the Java internal API and prints what the agent does."""

    def __init__(self):
        self.next_id = 1

    def execute(self, name, tool_input):
        print(f"  tool {name}: {json.dumps(tool_input, ensure_ascii=False)}")
        if name == "add_product":
            self.next_id += 1
            return json.dumps({"ok": True, "product_id": self.next_id}), False
        if name == "propose_shop_address":
            return json.dumps({"ok": True, "slug": tool_input["slug"], "address": tool_input["slug"] + ".example.in"}), False
        return json.dumps({"ok": True}), False


def run_onboarding(gw) -> int:
    root = HERE / "onboarding"
    cases = sorted(p for p in root.iterdir() if p.is_dir()) if root.exists() else []
    if not cases:
        print("No onboarding cases. Add folders under evals/onboarding/ (see evals/README.md).")
        return 0
    for case in cases:
        print(f"\n=== {case.name}")
        notes = (case / "notes.txt").read_text(encoding="utf-8") if (case / "notes.txt").exists() else ""
        content = [{"type": "text", "text": f"Seller's name: {case.name}\nSeller's language: Hindi\nGroup: Our Village Crafts\n"
                                            f"Shop addresses look like <address>.example.in\n\n<notes>\n{notes}\n</notes>"}]
        for photo in sorted(case.glob("*.jp*g")) + sorted(case.glob("*.png")):
            media = "image/png" if photo.suffix == ".png" else "image/jpeg"
            content.append({"type": "text", "text": f"Photo id: {photo.name}"})
            content.append({"type": "image", "source": {"type": "base64", "media_type": media,
                                                        "data": base64.b64encode(photo.read_bytes()).decode()}})
        history: list[dict] = []
        user_input = content
        for turn in range(1, 4):
            out = onboarding.run_turn(OnboardingTurnRequest(session_id=0, seller_id=0, history=history, input=user_input),
                                      gw, RecordingTools(), settings.onboarding_model, settings.max_tool_rounds)
            print(f"  -> {out.status} after {out.rounds} round(s)")
            if out.status != "needs_input":
                print("  note:", out.note_for_admin or out.error)
                break
            print("  question:", out.question)
            history = history + [{"role": "user", "content": user_input}] + out.appended
            user_input = out.pending_tool_results + [{"type": "text", "text": "Message from the group admin:\nI don't know. Mark it unsure and continue."}]
    return 0


if __name__ == "__main__":
    gateway = LiveGateway()
    which = sys.argv[1] if len(sys.argv) > 1 else "enquiries"
    sys.exit(1 if (run_onboarding(gateway) if which == "onboarding" else run_enquiries(gateway)) else 0)
