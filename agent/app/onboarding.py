"""The onboarding agent: one call to `run_turn` is one admin turn.

The service is stateless. Java stores the conversation and sends it back each turn; we return
only what to append. Rules that keep the stored history valid (append-only, so the prompt cache
and thinking-block binding both hold):

- Assistant turns are stored exactly as the API returned them (`dump_content`).
- The `system` prompt and `tools` never change between requests.
- A turn ends when Claude asks the admin something, submits the draft, finishes without a tool
  call, hits the round cap, or a later API call fails. The tool results of the last tool batch
  are then returned as `pending_tool_results`; Java puts them at the start of the next user
  message, followed by the admin's words as a separate text block (never inside a tool_result).
"""

import logging
from importlib import resources

from app.claude import (
    FALLBACK_BETA,
    THINKING_BINDING_BETA,
    AIError,
    Gateway,
    dump_content,
    log_usage,
    prepare_content,
    prepare_messages,
    text_of,
)
from app.schemas import OnboardingTurnRequest, OnboardingTurnResponse
from app.tools import TOOLS, WebTools, resolve_name

log = logging.getLogger("launchpad.onboarding")

SYSTEM_PROMPT = resources.files("app").joinpath("prompts/onboarding.md").read_text(encoding="utf-8")

ASK_RESULT = "Question passed to the group admin. Their answer follows in the next user message."


def _request(model: str, messages: list[dict]) -> dict:
    return dict(
        model=model,
        max_tokens=16000,
        system=SYSTEM_PROMPT,
        tools=TOOLS,
        messages=messages,
        thinking={"type": "adaptive", "block_binding": {"prefix_mismatch_behavior": "drop_block"}},
        output_config={"effort": "medium"},  # multistep tool use
        cache_control={"type": "ephemeral"},  # caches system + tools + the conversation so far
        betas=[FALLBACK_BETA, THINKING_BINDING_BETA],
        fallbacks="default",
    )


def _tool_result(tool_use_id: str, content: str, is_error: bool = False) -> dict:
    block = {"type": "tool_result", "tool_use_id": tool_use_id, "content": content}
    if is_error:
        block["is_error"] = True
    return block


def run_turn(req: OnboardingTurnRequest, gateway: Gateway, tools: WebTools, model: str, max_rounds: int) -> OnboardingTurnResponse:
    base = prepare_messages(req.history) + [{"role": "user", "content": prepare_content(req.input)}]
    appended: list[dict] = []
    pending: list[dict] = []  # tool_result blocks not yet sent

    def stop(status: str, *, question: str | None = None, note: str | None = None, error: str | None = None, rounds: int = 0):
        return OnboardingTurnResponse(
            status=status, appended=appended, pending_tool_results=pending,
            question=question, note_for_admin=note, error=error, rounds=rounds,
        )

    for round_no in range(1, max_rounds + 1):
        messages = base + appended + ([{"role": "user", "content": pending}] if pending else [])
        try:
            response = gateway.create(**_request(model, messages))
        except AIError as e:
            return _interrupted(stop, str(e), round_no, appended)
        log_usage(f"onboarding session={req.session_id} round={round_no}", response)

        if response.stop_reason == "refusal":
            return _interrupted(stop, "Claude declined to continue with this input.", round_no, appended)
        if response.stop_reason == "max_tokens":
            return _interrupted(stop, "Claude's answer was cut off.", round_no, appended)

        # The call succeeded: the results we sent are now part of the history, then the reply.
        if pending:
            appended.append({"role": "user", "content": pending})
            pending = []
        appended.append({"role": "assistant", "content": dump_content(response)})

        tool_uses = [b for b in response.content if b.type == "tool_use"]
        if not tool_uses:
            text = text_of(response) or "Is there anything else to add before I finish the draft?"
            return stop("needs_input", question=text, rounds=round_no)

        results: list[dict] = []
        question = note = None
        submitted = False
        for tu in tool_uses:
            name = resolve_name(tu.name) or tu.name
            if name == "ask_seller":
                question = str(tu.input.get("question", "")).strip() or "Could you tell me more about the products?"
                results.append(_tool_result(tu.id, ASK_RESULT))
                continue
            content, is_error = tools.execute(tu.name, dict(tu.input))
            log.info("tool %s error=%s", name, is_error)
            results.append(_tool_result(tu.id, content, is_error))
            if name == "submit_for_review" and not is_error:
                submitted = True
                note = str(tu.input.get("note_for_admin", "")).strip()

        pending = results
        if question is not None:
            return stop("needs_input", question=question, rounds=round_no)
        if submitted:
            return stop("submitted", note=note, rounds=round_no)

    return stop(
        "needs_input",
        question="I need a few more steps to finish this shop. Reply \"continue\" (and add anything you like).",
        rounds=max_rounds,
    )


def _interrupted(stop, reason: str, round_no: int, appended: list[dict]):
    if not appended:
        # Nothing was recorded this turn; Java keeps the input and can resend it.
        return stop("failed", error=reason, rounds=round_no)
    # Earlier rounds succeeded and their tool calls already changed the draft: keep them.
    return stop(
        "needs_input",
        question=f"I was interrupted ({reason}). Reply \"continue\" to carry on.",
        error=reason,
        rounds=round_no,
    )
