---
Spec-ID: ADK-KT-001
Title: aduki-kt guide index
Category: KT
Status: Draft
Version: 2
Depends-on: ADK-META-001, ADK-AUTH-001, ADK-AUTH-002
---

# aduki-kt `guide/`

`aduki-kt` is the Kotlin (JVM, Android-targeted) client SDK of the Aduki platform:
sign-in at Aduki ID, Account Center, offline-first mail, contacts and scheduling
sync. It owns no server specs. Backend and plans only: no UI content.

## 1. Files and reading order

Internal guide: plans, design notes and progress. The public manual is the
mdBook in `../docs/` (published at `docs.aduki.pro/kt`); the context for an AI
on the hosting server is `../AI/`.

| Order | File | Holds |
|---|---|---|
| 1 | `progress.md` | facts: what exists per module, what is missing, merge state (ADK-KT-002) |
| 2 | `plan.md` | work items K0 to K7 with exit criteria (ADK-KT-003) |
| 3 | `structure.md` | module and package layout, naming conventions |
| 4 | `design.md`, `state.md`, `network.md`, `database.md`, `security.md`, `scheduling.md` | original design notes per subsystem. Intent, not verified behaviour: where they differ from the code, `progress.md` section 3.3 and the code win |
| 5 | `performance.md` | optimisation ideas and targets; the numbers in it are unmeasured (no benchmark harness exists) |
| 6 | `design-plan.md` | the original phased implementation plan of the 0.1/0.2 era (history; `plan.md` is the current plan) |

Moved here from `Next/` in the docs restructure (`git mv`, history kept):
`Next/README.md` is this file, `Next/plan.md` is `plan.md`, `Next/progress.md` is `progress.md`.
The old `guide/plan.md` became `design-plan.md` (name collision; the spec
registry points at `guide/plan.md` for ADK-KT-003).

## 2. Where the specs live

The contracts the SDK implements are owned elsewhere and cited by Spec-ID:

| Need | Spec |
|---|---|
| sign-in, tokens, device key, DPoP | ADK-AUTH-001 (`id`) |
| Account Center, link, switcher, unlock | ADK-AUTH-002 (`id`) |
| permission bits, `rights` push | ADK-AUTH-003 (`id`) |
| registry, plan, phases P2, P4, P9 | ADK-META-001 and `plan.md` in `aduki/guide/` |
| naming, design, optimisation rules | `../SKILLS.md`; originals in `aduki/guide/skills/` |

A change of SDK behaviour changes the owning spec in the same pull request.
The mdBook in `../docs/` is the user documentation of the SDK itself; it is not a spec.

## 3. Changelog

- v2: `Next/` moved to `guide/`; file index and reading order.
- v1: created with the platform `Next/` rewrite.
