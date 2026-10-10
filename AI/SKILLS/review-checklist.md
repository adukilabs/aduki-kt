# Skill: review checklist for a PR to aduki-kt

- Secrets: no tokens, keys, passwords or real addresses in code, tests, fixtures, logs or docs; no default credentials in tests (live tests read env and skip).
- Auth: refresh tokens are single use; a refused or spent refresh is dropped, never reused; renewal is serialized (`Id`); a 401 retries once and never loops; API keys are not renewed.
- Every HTTP reply is read through the REST envelope reader (`net.http.Envelope`) or `Login`'s own envelope helper; no ad hoc body parsing; a 2xx is not trusted without its required fields.
- Layering: persistence only in `store`, transport only in `net`, `sdk` composes; no cross-module reach into internals; explicit imports.
- Naming and design rules in `SKILLS.md` (one-word names, `camelCase`, more than three parameters take a request class).
- Tokens and keys in memory are wiped when no longer needed; nothing sensitive is logged.
- Tests: a MockWebServer or fixture test per route/behaviour; no sleeps that flake; Android-only code is not claimed verified.
- Docs: public page updated, no unverified claims (`update-docs.md`); `guide/progress.md` updated if facts changed.
- Git: owner's identity, no `Co-Authored-By`, PR body ends with the Claude Code link line, never force-push `main`.
