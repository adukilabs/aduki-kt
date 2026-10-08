---
Spec-ID: ADK-KT-001
Title: aduki-kt Next index
Category: KT
Status: Draft
Version: 1
Depends-on: ADK-META-001, ADK-AUTH-001, ADK-AUTH-002
---

# aduki-kt `Next/`

`aduki-kt` is the Kotlin (JVM, Android-targeted) client SDK of the Aduki platform:
sign-in at Aduki ID, Account Center, offline-first mail, contacts and scheduling
sync. It owns no server specs. Backend and plans only: no UI content.

## 1. Files

| File | Holds |
|---|---|
| `progress.md` | facts: what exists per module, what is missing (ADK-KT-002) |
| `plan.md` | work items with exit criteria (ADK-KT-003) |

## 2. Where the specs live

The contracts the SDK implements are owned elsewhere and cited by Spec-ID:

| Need | Spec |
|---|---|
| sign-in, tokens, device key, DPoP | ADK-AUTH-001 (`id`) |
| Account Center, link, switcher, unlock | ADK-AUTH-002 (`id`) |
| permission bits, `rights` push | ADK-AUTH-003 (`id`) |
| registry, plan, phases P2, P4, P9 | ADK-META-001 and `plan.md` in `aduki/Next/` |
| naming, design, optimisation rules | `SKILLS.md` here; originals in `aduki/Next/skills/` |

A change of SDK behaviour changes the owning spec in the same pull request.
The mdBook in `docs/` and the notes in `guide/` are user and design docs of the
SDK itself; they are not specs and are not moved.

## 3. Changelog

- v1: created with the platform `Next/` rewrite.
