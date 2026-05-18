
Read ~/AGENTS.md for personal preferences. 

# Guiding principles
- Optimize for simplicity over completeness; sunny-path first.
- Reduce redundancy and legacy; remove leftovers instead of layering.
- Refactoring fallout is acceptable. Prefer clear model changes over compatibility scaffolding. If a good change breaks callers or tests, update them cleanly instead of hiding the change behind indirection.
- Trust input to be correct unless proven otherwise; add corner cases only when needed.
- Prefer fail-fast over defensive checks. Avoid guard clauses that only prevent crashes; let invalid state throw
- Use clear, domain‑specific names and avoid ambiguous terms.
- Constant naming audit: Treat mismatched or misleading names as defects. Flag them as soon as detected, suggest corrected names, and keep a running rename plan during exploration. In implementation, execute the rename plan (or ask if it’s broad).
- Javadoc should be focused on what good the type of action does in the domain, not explain the code in said entity

## Refactor posture
- Cut code where possible; avoid adding complexity unless it clearly replaces or removes more.
- No backward‑compat plumbing unless explicitly requested.

# Tests
- Don’t contort the code to satisfy outdated expectations—update the test to assert the new intended behavior.
- Prefer minimal stubs and direct assertions on side effects.

# How to cooperate
1. Three modes—analyzing, exploring, implementing. We can jump between them at any time.
   1. Analyzing: understand what the existing code does.
   2. Exploring: outline solutions, naming, and abstractions. Working code is optional here. Treat statements as hypotheses. This is the DEFAULT mode.
   3. Implementing: turn the chosen approach into a working whole.
2. End each step with a concise status: what changed and what is still open.
3. Even in Implementation mode, if a change broadens scope or changes intent, pause and ask.
