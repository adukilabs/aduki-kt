# Skill: release and publish

`publish.yml` runs on a `v*` tag, a created release or manual dispatch: JDK 17, `gradle test`, publish to GitHub Packages, build `bundle.zip` (all modules), upload it to Maven Central's publisher API.

1. Prerequisites (owner): namespace `pro.aduki` verified on Maven Central (K0 in `AI/PROGRESS.md`); repository secrets `SIGNING_KEY`, `SIGNING_PASSWORD`, `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`. Never print them.
2. Set the version: `val release = "x.y.z"` in the root `build.gradle.kts` (a `-Pversion=` Gradle property overrides it). Update version strings in `README.md` and `docs/start/install.md`.
3. Local dry run, no upload: `./gradlew --no-daemon test bundle` (the `bundle` task publishes every module to `build/repo` and zips it to `build/bundle.zip`). Unsigned unless `SIGNING_KEY` is set.
4. Tag and push: `git tag vX.Y.Z && git push origin vX.Y.Z` (a tag, never a force-push of `main`).
5. Watch the workflow: `gh run watch`. The Central step prints a deployment id and polls the status.

Known issue: the workflow installs Gradle 8.7 and calls `gradle`, while the wrapper pins 9.6.0. Prefer `./gradlew` (see `AI/PROGRESS.md` item 1).

Verify: the version resolves from a clean project (`pro.aduki:sdk:X.Y.Z`) and appears at `https://central.sonatype.com/artifact/pro.aduki/sdk`.
