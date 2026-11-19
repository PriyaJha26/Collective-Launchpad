"""Keep the 11 translated message bundles in step with the English one.

  python scripts/translate_messages.py --check            # report missing/extra keys and placeholder mismatches
  python scripts/translate_messages.py                    # translate missing keys through the agent, then rewrite bundles
  python scripts/translate_messages.py --only hi,ta       # just some languages
  python scripts/translate_messages.py --retranslate common.save,nav.cart   # redo these keys everywhere

Translating needs the agent running (AGENT_URL, default http://localhost:8000) and INTERNAL_TOKEN.
Existing translations are kept; only missing keys are sent. Each bundle is rewritten in the English
key order, with the English section comments, so diffs stay readable.
Needs: httpx (in agent/.venv).
"""

import argparse
import os
import re
import sys
from pathlib import Path

I18N = Path(__file__).resolve().parent.parent / "web/src/main/resources/i18n"
LANGUAGES = {
    "hi": "हिन्दी", "mr": "मराठी", "ta": "தமிழ்", "te": "తెలుగు", "kn": "ಕನ್ನಡ", "ml": "മലയാളം",
    "bn": "বাংলা", "gu": "ગુજરાતી", "pa": "ਪੰਜਾਬੀ", "or": "ଓଡ଼ିଆ", "ur": "اردو",
}
PLACEHOLDER = re.compile(r"\{(\d+)[,}]")
BATCH = 20


def read(path: Path) -> list[tuple[str, str]]:
    """Lines of a bundle as ("#", comment) or (key, value) pairs, in file order."""
    out = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#"):
            out.append(("#", line))
            continue
        if line.endswith("\\"):
            sys.exit(f"{path.name}: line continuations are not supported: {line[:60]}")
        key, _, value = line.partition("=")
        out.append((key.strip(), value.strip()))
    return out


def entries(path: Path) -> dict[str, str]:
    return {k: v for k, v in read(path) if k != "#"} if path.exists() else {}


def placeholders(text: str) -> list[str]:
    """Argument numbers used; a translation may format them differently (e.g. without a choice)."""
    return sorted(set(PLACEHOLDER.findall(text)))


def clean(text: str) -> str:
    # A plain ' starts a quoted section in MessageFormat; translations use the typographic one.
    return text.replace("\n", " ").replace("'", "\u2019").strip()


def problems(english: dict[str, str], bundle: dict[str, str]) -> list[str]:
    out = [f"missing {k}" for k in english if k not in bundle]
    out += [f"extra {k}" for k in bundle if k not in english]
    out += [f"placeholders differ in {k}" for k, v in bundle.items()
            if k in english and placeholders(v) != placeholders(english[k])]
    out += [f"plain apostrophe in {k}" for k, v in bundle.items() if "'" in v]
    return out


def write(lang: str, english_lines: list[tuple[str, str]], bundle: dict[str, str]) -> None:
    lines = [f"# {LANGUAGES[lang]}"]
    for key, value in english_lines:
        if key == "#":
            if value.startswith("# ---"):
                lines += ["", value]
        elif key in bundle:
            lines.append(f"{key}={bundle[key]}")
    (I18N / f"messages_{lang}.properties").write_text("\n".join(lines) + "\n", encoding="utf-8")


def translate(texts: dict[str, str], lang: str, client) -> dict[str, str]:
    out = {}
    items = list(texts.items())
    for i in range(0, len(items), BATCH):
        batch = dict(items[i:i + BATCH])
        r = client.post("/translate/to", json={"fields": batch, "targets": [lang]})
        r.raise_for_status()
        got = r.json()["results"].get(lang, {})
        for key, english in batch.items():
            text = clean(got.get(key, ""))
            if text and placeholders(text) == placeholders(english):
                out[key] = text
            else:
                print(f"  {lang}: skipped {key} (empty or placeholders changed)")
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="only report problems; exit 1 if any")
    ap.add_argument("--only", default="", help="comma-separated language codes")
    ap.add_argument("--retranslate", default="", help="comma-separated keys to translate again")
    args = ap.parse_args()

    english_lines = read(I18N / "messages.properties")
    english = {k: v for k, v in english_lines if k != "#"}
    langs = [l for l in args.only.split(",") if l] or list(LANGUAGES)
    redo = {k for k in args.retranslate.split(",") if k}

    if args.check:
        bad = False
        for lang in langs:
            found = problems(english, entries(I18N / f"messages_{lang}.properties"))
            for p in found:
                print(f"{lang}: {p}")
            bad |= bool(found)
        sys.exit(1 if bad else 0)

    import httpx

    token = os.environ.get("INTERNAL_TOKEN") or sys.exit("Set INTERNAL_TOKEN (same as the agent's).")
    with httpx.Client(base_url=os.environ.get("AGENT_URL", "http://localhost:8000"),
                      headers={"X-Internal-Token": token}, timeout=180) as client:
        for lang in langs:
            bundle = {k: v for k, v in entries(I18N / f"messages_{lang}.properties").items()
                      if k in english and k not in redo}
            todo = {k: v for k, v in english.items() if k not in bundle}
            if todo:
                print(f"{lang}: translating {len(todo)} keys")
                bundle.update(translate(todo, lang, client))
            write(lang, english_lines, bundle)
            left = problems(english, bundle)
            print(f"{lang}: {'ok' if not left else f'{len(left)} problems left'}")


if __name__ == "__main__":
    main()
