"""Collective Launchpad agent service. Only the Java web service calls it; it is not exposed publicly."""

import hmac
import logging

from fastapi import Depends, FastAPI, Header, HTTPException

from app import enquiries, onboarding
from app.claude import AIError, Gateway, LiveGateway
from app.config import settings
from app.mock import MockGateway
from app.schemas import (
    ClassifyRequest,
    ClassifyResult,
    DraftReplyRequest,
    DraftReplyResult,
    OnboardingTurnRequest,
    OnboardingTurnResponse,
    LANGUAGE_NAMES,
    TranslateRequest,
    TranslateResult,
    TranslateToRequest,
    TranslateToResult,
)
from app.tools import WebTools

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("launchpad.agent")

app = FastAPI(title="Collective Launchpad agent", docs_url=None, redoc_url=None)
_gateway: Gateway = LiveGateway() if settings.mode == "live" else MockGateway()
log.info("agent mode=%s onboarding=%s draft=%s classify=%s web=%s",
         settings.mode, settings.onboarding_model, settings.draft_model, settings.classify_model, settings.web_internal_url)
if not settings.internal_token:
    log.warning("INTERNAL_TOKEN is not set: every request will be rejected.")


def gateway() -> Gateway:
    return _gateway


def require_token(x_internal_token: str | None = Header(default=None)) -> None:
    if not settings.internal_token or not x_internal_token or not hmac.compare_digest(x_internal_token, settings.internal_token):
        raise HTTPException(status_code=401, detail="missing or wrong internal token")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "mode": settings.mode,
        "models": {
            "onboarding": settings.onboarding_model,
            "draft": settings.draft_model,
            "classify": settings.classify_model,
        },
    }


@app.post("/onboarding/turn", response_model=OnboardingTurnResponse, dependencies=[Depends(require_token)])
def onboarding_turn(req: OnboardingTurnRequest, gw: Gateway = Depends(gateway)) -> OnboardingTurnResponse:
    if not req.input:
        raise HTTPException(status_code=400, detail="input is empty")
    tools = WebTools(settings.web_internal_url, settings.internal_token, req.seller_id)
    return onboarding.run_turn(req, gw, tools, settings.onboarding_model, settings.max_tool_rounds)


@app.post("/enquiries/classify", response_model=ClassifyResult, dependencies=[Depends(require_token)])
def classify(req: ClassifyRequest, gw: Gateway = Depends(gateway)) -> ClassifyResult:
    try:
        return enquiries.classify(req, gw, settings.classify_model)
    except AIError as e:
        raise HTTPException(status_code=502, detail=str(e)) from e


@app.post("/enquiries/draft-reply", response_model=DraftReplyResult, dependencies=[Depends(require_token)])
def draft_reply(req: DraftReplyRequest, gw: Gateway = Depends(gateway)) -> DraftReplyResult:
    try:
        return enquiries.draft_reply(req, gw, settings.draft_model)
    except AIError as e:
        raise HTTPException(status_code=502, detail=str(e)) from e


@app.post("/translate", response_model=TranslateResult, dependencies=[Depends(require_token)])
def translate(req: TranslateRequest, gw: Gateway = Depends(gateway)) -> TranslateResult:
    if not req.fields or len(req.fields) > 20 or sum(len(v) for v in req.fields.values()) > 20000:
        raise HTTPException(status_code=400, detail="send 1-20 fields, up to 20,000 characters in total")
    try:
        return enquiries.translate(req, gw, settings.classify_model)
    except AIError as e:
        raise HTTPException(status_code=502, detail=str(e)) from e


@app.post("/translate/to", response_model=TranslateToResult, dependencies=[Depends(require_token)])
def translate_to(req: TranslateToRequest, gw: Gateway = Depends(gateway)) -> TranslateToResult:
    if not req.fields or len(req.fields) > 20 or sum(len(v) for v in req.fields.values()) > 20000:
        raise HTTPException(status_code=400, detail="send 1-20 fields, up to 20,000 characters in total")
    if not req.targets or len(req.targets) > len(LANGUAGE_NAMES) or any(t not in LANGUAGE_NAMES for t in req.targets):
        raise HTTPException(status_code=400, detail=f"targets must be 1-{len(LANGUAGE_NAMES)} of: {', '.join(LANGUAGE_NAMES)}")
    try:
        return enquiries.translate_to(req, gw, settings.classify_model)
    except AIError as e:
        raise HTTPException(status_code=502, detail=str(e)) from e
