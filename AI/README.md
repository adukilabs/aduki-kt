# AI/ : context for the AI working on aduki-kt

You are working on the Aduki Kotlin SDK (`aduki-kt`, GitHub `adukilabs/aduki-kt`) on the hosting server. You have no memory of earlier sessions: everything you need is in this folder, `guide/` and the code.

## What this repo is

A client SDK for the Aduki platform, written as plain Kotlin/JVM Gradle modules (`kotlin("jvm")`, no Android Gradle plugin yet):

| Module | Holds |
|---|---|
| `core` | models, errors (`AdukiException`), config (`Endpoints`, `Options`), retry (`Jitter`, `Circuit`), memory (`wipe`) |
| `crypto` | `keystore.Provider`, `cipher.Envelope` (AES-256-GCM), `sanitizer.Guard`, `tls.Pinning`, `dpop.Key` and `Software` |
| `store` | ObjectBox entities, `box.Factory`, `queries.Batch` |
| `net` | `http`: `Id`, `Login`, `Center`, `Mail`, `Scheduling`, `Whoami`, `Events` (rights stream), `Dpop`, `Oidc`, `Envelope` (REST envelope reader), `Scheme` (Authorization header) |
| `sync` | mailbox, contact and schedule engines; outbox `Manager` and `Worker`; `http.HttpMailboxTransport`, `http.HttpDispatcher`; `reconcile` |
| `state` | repositories exposing `StateFlow` (mail, contact, appointment, session) |
| `sdk` | the `Aduki` facade, `Mail`, `Contacts`, `Sync`, `Scheduling`, `Lifecycle` |

Packages are `pro.aduki.<module>`; Maven group `pro.aduki`, version in the root `build.gradle.kts` (`release`).

## Where it sits in the platform

It is a client of: Aduki ID (`https://id.aduki.pro/v1`: sessions, tokens, Account Center, OIDC), Aduki Mail (`https://mail.aduki.pro/v1` REST and `/jmap/eventsource`) (no gRPC: the `grpc.` host was dropped, D-HOST-5). Other products (store, files, space, chat) have no client here. The contracts it implements are specs in the `aduki` repo (`aduki/guide/spec.md` is the registry); see `CONTEXT.md`.

## Repo map

- `docs/`: public mdBook (`docs.aduki.pro/kt`). Never put internal material there.
- `guide/`: internal plans and notes. Read `guide/README.md` first, then `guide/progress.md` and `guide/plan.md`.
- `AI/`: this folder. `AI/PROGRESS.md` is the work list.
- `SKILLS.md`: naming, design and optimisation rules (condensed from the platform skills).
- `net/src/test/resources/fixtures/`: copies of the server's response fixtures; contract tests replay them.
- `.github/workflows/`: `book.yml` (mdBook build and Pages deploy), `publish.yml` (tag build: test, GitHub Packages, Maven Central upload).

## How to run it

`AI/SKILLS/build-and-test.md` (JDK 17, `./gradlew --no-daemon test`), `AI/SKILLS/live-tier.md` (against a real server), `AI/SKILLS/release.md`.

## Never do

- Never print, log or commit secrets (tokens, API keys, `SIGNING_KEY`, Sonatype credentials, test-account passwords). Env var names only.
- Never force-push `main`. Work on a branch, open a PR with `gh pr create`; the PR body ends with the line `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Commits use the owner's existing git identity (do not change git config). Strip AI trailers: no `Co-Authored-By` lines in commit messages.
- One Gradle build at a time, `--no-daemon`, on a small box; never beside a cargo build. Watch disk (`df -h`) and RAM (`free -m`); the Gradle cache and ObjectBox native libraries are large.
- Do not claim anything Android works unless it ran on a device or emulator (see `AI/PROGRESS.md`).
- Do not add benchmark numbers, StrongBox claims or "encrypted database" claims to docs: none is backed by code.
- Do not edit the `aduki` repo from here; specs there are changed in their own PRs.
