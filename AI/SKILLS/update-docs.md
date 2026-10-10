# Skill: update the documentation

Three places, three audiences:

| Where | For | Rule |
|---|---|---|
| `docs/` | public developers (docs.aduki.pro/kt) | usage only; no plans, no phases, no internal hosts, no unverified claims |
| `guide/` | the team | plans, design intent, progress; banner on design notes says they are intent |
| `AI/` | the AI on the server | what remains and what is unverified |

Steps:

1. Change the code and the page that describes it in the same PR. Check the signature against the source, not against the old page (many old claims were wrong: `Envelope` is an `object`, the database is not encrypted, `Circuit` is unused).
2. No performance numbers, "zero-copy", StrongBox or encryption claims unless a test or measurement in the repo backs them.
3. Add new pages to `docs/SUMMARY.md`; links are relative (the book is served under `/kt/`).
4. Build: `mdbook build docs` (mdBook only; do not `cargo install` it on a small box: use the prebuilt binary or the CI). Output goes to `docs/book/` (gitignored, never commit).
5. If something is true but internal (owner decisions, unverified lists), it goes to `guide/` or `AI/PROGRESS.md`.

Verify: the build prints no warnings about missing files; `grep -rniE "strongbox|benchmark|zero-copy|hermes" docs` finds nothing unintended.
