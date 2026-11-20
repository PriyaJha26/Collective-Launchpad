# Collective Launchpad

One domain shared by many tiny sellers. A self-help group or cooperative owns one domain, and each member gets a shop page on their own subdomain (`meena.ourvillagecrafts.in`). A literate group member, the admin, onboards sellers and answers customers on their behalf, with Claude doing the reading, structuring and drafting.

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

## Who uses it

| Role | Where | Can do |
|---|---|---|
| **Superadmin** (runs the site) | `/admin` | See everything. Add sellers for people who can't use a phone well (Claude builds the shop from photos), give them a login, approve self-registered shops, publish or hide shops, see all orders and each shop's earnings, manage users and reset passwords, answer join requests and messages. |
| **Seller** (one shop) | `/seller` | Add, edit or remove products (price, stock, description, photo). See and answer customer messages (Claude drafts the reply). Accept, decline and complete orders, confirm payment, see earnings. Set the UPI ID customers pay to. Set up the shop with the Claude assistant. |
| **Customer** | public site, `/account` | Browse every shop and product without logging in. Ask a seller a question, ask for a discount or request a custom order. Log in to order, pay the seller (cash on delivery or UPI) and see orders and replies. Any customer can also open a shop. |

Everyone logs in with a **mobile number and password**; the superadmin can also use an email. Customers and sellers can also use **"Continue with Google"** (see [Sign in with Google](#sign-in-with-google)). Sellers can **register themselves** (`/register/seller`); their shop stays hidden until the superadmin approves it. People who need help can use the **"Sell with us" intake form** (`/join`).

**Payments go straight to the seller.** There is no payment gateway: at checkout the customer picks cash on delivery / pickup, or UPI to the seller's own UPI ID (a `upi://` link opens their UPI app with the amount filled in). The seller marks the order as paid when the money arrives; only paid orders count as earnings.

**Ordering happens on the main domain.** Shop pages stay on their subdomains (`meena.<domain>`), but "Buy", cart, checkout and accounts are on the main domain, so one login works everywhere and the session cookie never reaches shop subdomains.

## How the AI parts work

1. **Onboarding agent** (Claude Sonnet 5.5): reads product photos, handwritten price lists and dictated notes, and fills in the draft shop through tools (`set_shop_profile`, `add_product`, `update_product`, `propose_shop_address`, `ask_seller`, `submit_for_review`). It flags values it is unsure of and asks one question when something essential is missing. It cannot publish.
2. **Enquiries:** Claude Haiku 5.5 classifies each message; Claude Sonnet 5.5 drafts a reply that quotes only catalogue prices, plus points the seller should check. Nothing is sent until the seller (or superadmin) approves it. Logged-in customers read replies in their account; others get them by email.

### Website texts

The page texts live in `web/src/main/resources/i18n/messages.properties`. Texts with `{0}` arguments are MessageFormat patterns.

## Run it locally

Needs Java 21+, Maven, Python 3.11+ and PostgreSQL running locally (14+; production uses 16).

```bash
ADMIN_PASSWORD=choose-a-password ./run.sh
```

- Group site and shopping: http://localhost:8080/
- Superadmin: http://localhost:8080/admin, logged in as `admin@localhost` with the password above
- Seller dashboard: http://localhost:8080/seller (register at `/register/seller`, or the superadmin creates a login)
- Shops: http://sample.localhost:8080/ (browsers resolve `*.localhost` themselves)

`run.sh` creates the `launchpad` database if it is missing, a Python virtualenv for the agent, and a sample shop. Without a Claude key, the agent runs in **mock mode**. Mock answers are scripted and marked `[MOCK]`, but they go through the real tool loop and the real Java endpoints. To use Claude, put `ANTHROPIC_API_KEY=...` in `.env.local`. Email is simulated until SMTP is configured; simulated emails are listed on the dashboard.

## Sign in with Google

Optional. The "Continue with Google" button on the login and register pages only appears when `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` are set (in `.env.local` locally, `.env` on the server).

1. In [Google Cloud Console](https://console.cloud.google.com/), create or pick a project.
2. **APIs & Services > OAuth consent screen**: choose *External*, fill in the app name, support email and the authorised domain (your `DOMAIN`). The `openid`, `email` and `profile` scopes are enough; they need no Google review. While the app is in *Testing*, add the Google accounts that may sign in as test users, or publish it.
3. **APIs & Services > Credentials > Create credentials > OAuth client ID**, type *Web application*. Add these authorised redirect URIs:
   - `http://localhost:8080/login/oauth2/code/google` (local)
   - `https://<DOMAIN>/login/oauth2/code/google` (server)
4. Copy the client ID and secret into `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` and restart.

How accounts are matched: a Google account that signed in before goes to the same account. Otherwise, an account with the same email (Google must have verified it) is linked to it, so a seller can add their Gmail under "My details" and then use Google. Anyone else gets a new customer account, which can open a shop later as usual. The superadmin cannot sign in with Google. People who only use Google have no password; they can set one on their account page.

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

The web tests cover hostname routing, the certificate check, escaping, CSRF, login and the three roles (each reaches only its own pages and data), registration, seller self-signup and approval, product management, cart and checkout (stock is never oversold and comes back when an order is declined or cancelled), payment confirmation and earnings, in-app replies, login throttling, Google sign-in (linking, new accounts, the superadmin refusal, setting a first password), the contact forms, upload checks, the internal token, and the rule that the agent can only change drafts.

## Security notes

- **Secrets** live only in `.env`, which is git-ignored. `docker-compose.yml` gives each container only its own variables.
- **Service-to-service:** a shared token in `X-Internal-Token`, in both directions.
- **Agent scope:** the seller id is attached by the server, never by Claude. The internal API rejects changes to published shops. The agent has no tool to publish, send email or spend money.
- **Logins:** Spring Security form login with three roles, bcrypt, server-side sessions, CSRF tokens on every logged-in form, and a lockout after 10 wrong passwords from one IP in 15 minutes. The session cookie is `Secure`, `HttpOnly`, `SameSite=Lax` and host-only, so shop subdomains never receive it.
- **Data scoping:** a seller only ever reads or changes their own shop, products, orders and messages; a customer only their own orders and messages. Anything else shows "not found".
- **Public forms** (shop contact form, "Contact us", "Sell with us"): no login to protect, so instead of a CSRF token they are guarded by validation, length limits, a honeypot field and a per-IP rate limit.
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
