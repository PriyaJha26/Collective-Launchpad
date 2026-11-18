# Collective Launchpad

One domain shared by many tiny sellers. A self-help group or cooperative owns one domain, and each member gets a shop page on her own subdomain (`meena.ourvillagecrafts.in`). A literate group member, the admin, onboards sellers and answers customers on their behalf, with Claude doing the reading, structuring and drafting.

```
                 Internet ── 80/443 ──▶ Caddy (HTTPS for <domain> and *.<domain>, on-demand certificates)
                                          │
                       private Docker network, no public ports below
                                          │
  Titan SMTP ◀── web (Java 21, Spring Boot) ◀──── JSON ────▶ agent (Python, FastAPI) ──▶ Claude API
                   │            │
              PostgreSQL     uploads volume
```

| | `web/` (Java) | `agent/` (Python) |
|---|---|---|
| Owns | All data, pages, login, email, publishing | All Claude calls, prompts, tool definitions |
| Talks to | PostgreSQL, SMTP, the agent | Claude API, the web service's internal API |
| Secrets | DB password, SMTP password | Claude API key |

Only Java touches the database, and only Python talks to Claude. The agent is stateless: Java stores each onboarding conversation and sends it back every turn.

## What it does

1. **Add a seller.** The admin uploads photos of products and the price list (which may be handwritten) and types or dictates notes in the seller's language.
2. **The onboarding agent** (Claude Sonnet 5.5) reads them and fills in the draft through tools: `set_shop_profile`, `add_product`, `update_product`, `propose_shop_address`, `ask_seller` and `submit_for_review`. When something is missing it asks one question; the admin's answer starts the next turn. Values it is unsure of are flagged.
3. **Review and publish.** The admin sees flagged values highlighted, corrects them, previews and publishes. Publishing is always a human action. The web service then requests the shop's URL itself, which makes Caddy fetch the certificate and confirms the page is live.
4. **Enquiries.** Every shop has a contact form. Each enquiry is saved and a copy is emailed to the group mailbox. Claude Haiku 5.5 classifies it, and Claude Sonnet 5.5 drafts a reply using only catalogue prices, with points the admin should check. The admin edits, approves or discards the draft, and an approved reply goes out from the group mailbox.

## Run it locally

Needs Java 21+, Maven, Python 3.11+ and PostgreSQL running locally (14+; production uses 16).

```bash
ADMIN_PASSWORD=choose-a-password ./run.sh
```

- Group site: http://localhost:8080/
- Admin: http://localhost:8080/admin, logged in as `admin@localhost` with the password above
- Shops: http://sample.localhost:8080/ (browsers resolve `*.localhost` themselves)

`run.sh` creates the `launchpad` database if it is missing, a Python virtualenv for the agent, and a sample shop. Without a Claude key, the agent runs in **mock mode**. Mock answers are scripted and marked `[MOCK]`, but they go through the real tool loop and the real Java endpoints. To use Claude, put `ANTHROPIC_API_KEY=...` in `.env.local`. Email is simulated until SMTP is configured; simulated emails are listed on the dashboard.

## Deploy on the GoDaddy Docker server

1. DNS at GoDaddy: `A` records for `@` and `*`, both pointing at the server's IP. Leave the Titan mail records alone.
2. Create the Titan mailbox `hello@<domain>`.
3. Copy `.env.example` to `.env` and fill it in. Use `openssl rand -hex 32` for `INTERNAL_TOKEN` and `DB_PASSWORD`. While testing, set `ACME_CA` to Let's Encrypt staging.
4. Run `docker compose up -d --build`.
5. Run the end-to-end check from your laptop:
   `ADMIN_EMAIL=... ADMIN_PASSWORD=... agent/.venv/bin/python scripts/e2e_smoke.py --base https://<domain>`
   It creates a seller called "Smoke Test …": hide it afterwards.

Only Caddy publishes ports. `/internal/*` is blocked at Caddy and reachable only between containers.

## Tests

```bash
cd agent && .venv/bin/pytest -q                  # agent: tool loop, history rules, schemas, token check
cd web && mvn test                               # web: Testcontainers PostgreSQL when Docker is available
cd web && TEST_DB_URL=jdbc:postgresql://localhost:5432/launchpad_test mvn test   # ...or a local test database
cd agent && .venv/bin/python -m evals.run_evals  # saved real cases against Claude (costs a few cents)
```

The web tests cover hostname routing, the certificate check, escaping, CSRF, login, the contact form (validation, honeypot, rate limit), upload checks, the internal token, and the rule that the agent can only change drafts.

## Security notes

- **Secrets** live only in `.env`, which is git-ignored. `docker-compose.yml` gives each container only its own variables.
- **Service-to-service:** a shared token in `X-Internal-Token`, in both directions.
- **Agent scope:** the seller id is attached by the server, never by Claude. The internal API rejects changes to published shops. The agent has no tool to publish, send email or spend money.
- **Admin:** Spring Security form login, bcrypt, server-side sessions, and CSRF tokens on every admin form. The session cookie is `Secure`, `HttpOnly`, `SameSite=Lax` and host-only, so shop subdomains never receive it.
- **Public contact form:** it has no session to protect, so instead of a CSRF token it is guarded by validation, length limits, a honeypot field and a per-IP rate limit.
- **Uploads:** images only, checked from the file's first bytes, 10 MB each, stored under random names.
- **Headers:** Content Security Policy with no inline scripts or styles, HSTS including subdomains, and `X-Frame-Options: DENY`.

## How the onboarding conversation is stored

Claude's thinking blocks are bound to the exact conversation that produced them, so the stored history is append-only:

- Assistant turns are stored exactly as returned.
- The system prompt and tool list never change.
- Photos are stored as references and expanded to the same bytes every turn.

A turn ends when Claude calls `ask_seller` or `submit_for_review`, finishes without a tool call, or hits the 12-round cap. The results of its last tool calls are kept and placed at the start of the next message, followed by the admin's answer as a separate text block.

## Open items

| Item | Status |
|---|---|
| Titan SMTP host | Configurable. Try `smtp.titan.email:465`; if the login is rejected, try `smtpout.secureserver.net`. |
| Browser speech recognition for Hindi and Marathi | Uses Chrome's Web Speech API (`hi-IN`, `mr-IN`). If unsupported, the page says so and typing works. Check on the demo phone or laptop. |
| Let's Encrypt limits | One certificate per shop on its first visit. Use `ACME_CA` staging while testing. |
| Model identifiers | `claude-sonnet-5-5` and `claude-haiku-5-5`; override with `ONBOARDING_MODEL`, `DRAFT_MODEL`, `CLASSIFY_MODEL`. |
| Hosting path | This needs the Docker server, not the Node.js hosting path. |
