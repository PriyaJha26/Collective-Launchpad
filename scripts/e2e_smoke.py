"""End-to-end smoke test: onboarding -> publish -> enquiry -> approve -> reply.

Runs against a live deployment through its public pages, like an admin and a customer would.

  Local (run.sh):   python scripts/e2e_smoke.py --base http://localhost:8080 --local
  Server:           python scripts/e2e_smoke.py --base https://ourvillagecrafts.in

Needs: httpx and pillow (both in agent/.venv). Login from ADMIN_EMAIL / ADMIN_PASSWORD.
It creates a real seller called "Smoke Test <time>"; hide or delete it afterwards on a live site.
"""

import argparse
import html as html_lib
import io
import os
import re
import sys
import time
from urllib.parse import urlparse

import httpx
from PIL import Image, ImageDraw

CSRF = re.compile(r'name="_csrf"\s+value="([^"]+)"')


def csrf(html: str) -> str:
    m = CSRF.search(html)
    if not m:
        sys.exit("No CSRF token on the page; is this the right URL?")
    return m.group(1)


def photo(color, label) -> bytes:
    img = Image.new("RGB", (1200, 900), color)
    ImageDraw.Draw(img).text((40, 40), label, fill=(255, 255, 255))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=80)
    return buf.getvalue()


def step(msg):
    print(f"\n== {msg}")


def wait_for(fetch, done, what, timeout=240):
    start = time.time()
    while time.time() - start < timeout:
        html = fetch()
        if done(html):
            return html
        time.sleep(3)
    sys.exit(f"Timed out waiting for {what}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="Main site URL, e.g. https://ourvillagecrafts.in")
    ap.add_argument("--local", action="store_true", help="Shops are <slug>.localhost on the same port")
    ap.add_argument("--email", default=os.environ.get("ADMIN_EMAIL", "admin@localhost"))
    ap.add_argument("--password", default=os.environ.get("ADMIN_PASSWORD"))
    args = ap.parse_args()
    if not args.password:
        sys.exit("Set ADMIN_PASSWORD or pass --password")
    base = args.base.rstrip("/")
    admin = httpx.Client(base_url=base, follow_redirects=True, timeout=60)

    step("Log in")
    page = admin.get("/login").text
    r = admin.post("/login", data={"email": args.email, "password": args.password, "_csrf": csrf(page)})
    if "/admin" not in str(r.url):
        sys.exit("Login failed")
    print("logged in")

    step("Add a seller with two photos and notes")
    page = admin.get("/admin/sellers/new").text
    name = f"Smoke Test {time.strftime('%H%M%S')}"
    files = [
        ("photos", ("ladoo.jpg", photo((190, 120, 40), "Besan ladoo 300/kg"), "image/jpeg")),
        ("photos", ("cake.jpg", photo((90, 50, 30), "Chocolate cake"), "image/jpeg")),
    ]
    data = {"name": name, "language": "hi", "whatsapp": "",
            "notes": "She makes besan ladoo, 300 rupees per kg, and chocolate cakes to order. Lives in Wardha."}
    r = admin.post(f"/admin/sellers/new?_csrf={csrf(page)}", data=data, files=files)
    m = re.search(r"/admin/onboarding/(\d+)", str(r.url))
    if not m:
        sys.exit(f"Onboarding did not start (ended at {r.url})")
    session_url = f"/admin/onboarding/{m.group(1)}"
    print("session", session_url)

    for turn in range(1, 6):
        html = wait_for(lambda: admin.get(session_url).text, lambda h: "is reading the photos" not in h, "the assistant")
        if "did not go through" in html:
            sys.exit("Agent turn failed: " + re.sub(r"<[^>]+>", " ", html.split("did not go through", 1)[1])[:300])
        if "ready for your review" in html:
            print(f"draft submitted after {turn} turn(s)")
            break
        q = re.search(r'class="question[^"]*"[^>]*>(.*?)</p>', html, re.S)
        print("assistant asks:", q.group(1).strip() if q else "?")
        answer = "The chocolate cake is 450 rupees for 500 g." if turn == 1 else \
            "Please use your best judgement, mark anything uncertain, and submit the draft."
        r = admin.post(f"{session_url}/answer?_csrf={csrf(html)}", data={"answer": answer})
    else:
        sys.exit("The assistant did not submit the draft")

    seller_url = re.search(r'href="(/admin/sellers/\d+)"', html).group(1)

    step("Publish")
    html = admin.get(seller_url).text
    admin.post(f"{seller_url}/publish", data={"_csrf": csrf(html)})
    html = wait_for(lambda: admin.get(seller_url).text, lambda h: "Check OK" in h or "Problem" in h, "the live check", 120)
    slug = re.search(r'id="slug" name="slug"[^>]*value="([^"]+)"', html) or re.search(r'name="slug"[^>]*value="([^"]+)"', html)
    slug = slug.group(1)
    print("published as", slug, "| live check:", "OK" if "Check OK" in html else "PROBLEM")

    step("A customer opens the shop and sends a message")
    parsed = urlparse(base)
    if args.local:
        shop = httpx.Client(base_url=base, headers={"Host": f"{slug}.localhost:{parsed.port}"}, timeout=30)
    else:
        shop = httpx.Client(base_url=f"{parsed.scheme}://{slug}.{parsed.hostname}", timeout=30)
    page = shop.get("/")
    print("shop page", page.status_code)
    if page.status_code != 200:
        sys.exit("Shop page is not live")
    r = shop.post("/contact", data={"name": "Ravi", "email": "ravi@example.com",
                                    "message": "Can I order 2 kg besan ladoo for Saturday?"})
    print("contact form", r.status_code, r.headers.get("location"))

    step("Admin approves the drafted reply")
    inbox = wait_for(lambda: admin.get("/admin/inbox").text,
                     lambda h: "Ravi" in h and "drafting a reply" not in h, "the reply draft")
    eid = re.search(r'id="enquiry-(\d+)"', inbox).group(1)
    section = inbox.split(f'id="enquiry-{eid}"', 1)[1]
    draft = re.search(r'<textarea name="reply"[^>]*>(.*?)</textarea>', section, re.S)
    body = html_lib.unescape(draft.group(1).strip() if draft else "") or "Thank you, we will confirm soon."
    print("draft:", body[:160].replace("\n", " "), "…")
    admin.post(f"/admin/enquiries/{eid}/send", data={"reply": body, "_csrf": csrf(inbox)})
    done = admin.get("/admin/inbox?show=done").text
    print("reply sent" if f'id="enquiry-{eid}"' in done and "Reply sent" in done else "REPLY NOT SENT")
    print("\nAll steps passed.")


if __name__ == "__main__":
    main()
