# Saved cases

Real inputs to rerun after every prompt or model change. They call the real Claude API, so each run costs a few cents.

```bash
cd agent
ANTHROPIC_API_KEY=... .venv/bin/python -m evals.run_evals              # enquiry cases, checked automatically
ANTHROPIC_API_KEY=... .venv/bin/python -m evals.run_evals onboarding   # photo cases, read the tool calls
```

## Enquiries

`cases/enquiries.json` has 8 cases:

- an order
- a price question in Hindi
- an availability question in Marathi
- a delivery request the catalogue does not cover
- a complaint
- a prompt-injection attempt
- spam
- a custom cake

Each case lists what must hold: the category, urgency and reply language, phrases that must not appear (for example a promised discount), and whether the admin must be given points to check.

## Onboarding

Put one folder per case under `evals/onboarding/`:

```
evals/onboarding/
  meena-handwritten-hindi/
    notes.txt          # what the admin would type or dictate
    price-list.jpg     # e.g. a handwritten price list
    ladoo.jpg
```

Aim for about ten real cases: handwritten price lists in Hindi and Marathi, blurry photos, notes with no prices, and a seller whose name is in Devanagari. The runner prints every tool call (profile, products, prices, address) and any question the agent asks, so you can see whether it read the prices correctly and flagged what it was unsure of.

These photos are of real people's products, so keep them out of a public repository if the sellers have not agreed to it.
