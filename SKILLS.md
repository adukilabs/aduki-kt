# SKILLS.md - aduki-kt engineering guides

Condensed, Kotlin-flavoured rules. The originals are in `aduki/guide/skills/`
(`naming.md`, `design.md`, `rust.md`, `optimize.md`); on a difference the
originals win. Specs: `guide/README.md`; registry: `aduki/guide/spec.md`.
A PR that changes behaviour changes the owning spec and bumps its version.

## 1. Naming (naming.md)

- One word first: `created` not `createdAt`, `active` not `isActive`, `tenant` not `tenantId`, `type` not `entryType`.
- Drop qualifiers the scope supplies (`Center.link`, not `Center.linkAccount`; `Mailbox.fetch`, not `fetchMailbox`).
- Functions are one verb (`fetch`, `save`, `remove`, `send`, `verify`, `build`); a second word is a noun (`fetchAll`). Booleans are adjectives (`active()`).
- Variables name what the value is (`user`, not `fetchedUser`); no type suffixes (`List`, `Str`).
- Two words only when one loses meaning: `camelCase` in Kotlin and JSON, `snake_case` in databases.
- Files and folders: lowercase, one word, plural for collections, domain in the folder (`http/login.kt`), at most two nesting levels. Docs: only `README.md` and `SKILLS.md` are upper-case.
- Routes (client side): plural nouns, no verbs; actions under a sub-resource.

## 2. Design (design.md, applied to Kotlin)

- A module's public surface is what its package exposes; mark the rest `internal`. No cross-module reach into another module's internals.
- Imports are explicit, no wildcard, no renaming (`as`) except to settle a real collision.
- A function with more than three parameters takes a typed request class with `validate()`; callers do not pass long positional lists.
- Every HTTP reply is read through the one REST envelope reader (`{success, data}` or `{success:false, error}`); never parse a body ad hoc, never trust a 2xx without the required fields.
- Persistence stays in `store`; transport in `net`; `sdk` composes. Nothing in `net` or `sdk` touches ObjectBox directly.
- Secrets: tokens and keys live in the Keystore or `Guard` buffers, are wiped after use, and are never logged or stored in defaults.
- Shared contracts with other repositories are specs and event payloads, not shared code (the only shared code is the Rust `gate` crate, not used here).

## 3. Optimisation (optimize.md)

- Define the metric and the budget first; without a budget there is no optimisation work.
- Measure (profiler, your own benchmark; none is kept in the repository) before changing; macro first (batching, caching, query shape, off the main thread), micro last.
- Evidence in the PR: before and after numbers.
- Battery counts as a budget: batch network work, use jittered retries, no polling where a push exists.

## 4. Build machine (8 GB VPS)

- One shared Gradle user home and build cache per machine; `--no-daemon` and no incremental Kotlin compile on the VPS; do not run heavy builds in parallel.
- Do not bend the product for the build machine's limits; budgets are measured on target devices.
