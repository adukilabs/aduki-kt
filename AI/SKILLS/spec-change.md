# Skill: change SDK behaviour that a spec owns

The contracts live in other repos (`aduki/guide/spec.md` is the registry; see `AI/CONTEXT.md`). Sign-in, tokens, DPoP, OIDC: ADK-AUTH-001 (`id`); Account Center: ADK-AUTH-002; rights push: ADK-AUTH-003; REST envelope: ADK-NET-001.

1. Read the owning spec section before coding; cite `ADK-xxx-nnn section N` in the KDoc and the commit.
2. If the SDK needs a behaviour the spec does not state, change the spec first (a PR in the owning repo; do not edit the `aduki` repo from here) or record the question in `AI/PROGRESS.md`.
3. Contract tests replay the server's own fixtures (`net/src/test/resources/fixtures/`, copies of the server repo's fixtures): when the server fixtures change, copy them again and fix the parsers.
4. Update `guide/progress.md` (facts) and, if the item set changed, `guide/plan.md`; bump their `Version` and changelog.
5. Update the public page in `docs/` in the same PR.

Verify: `./gradlew --no-daemon test`, then a live run (`live-tier.md`) when the server side is available.
