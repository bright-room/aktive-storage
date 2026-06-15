# Releasing

aktive-storage uses a **tag-driven, gated** release flow. Releases are the only published channel (no SNAPSHOTs), and the git tag is the single source of truth for the released version.

## Channels

- **Release** — published to Maven Central on tag push (`v*`). This is the only channel.
- There is **no SNAPSHOT** channel.

## How to cut a release

1. Ensure the commit you want to release is on `main` and its CI run has succeeded.
2. Run the **`on-workflow-dispatch`** workflow (Actions → on-workflow-dispatch → Run workflow) with `version` set to `vX.Y.Z` (e.g. `v0.1.0`).
3. That workflow:
   - validates the version format and that the tag does not already exist,
   - **refuses to create the tag unless the target commit's CI is green** (lint / test / integration-test / package),
   - creates a signed tag via the GitHub API using the `CHLOE_CHAN` GitHub App.
4. The tag push triggers **`on-tag-push`**, which:
   - creates a GitHub Release with auto-generated notes,
   - publishes all modules + the BOM to Maven Central via nmcp (`publishingType=AUTOMATIC` — released automatically once the Central Portal validates the deployment).

## Versioning

aktive-storage follows semantic versioning with pre-1.0 semantics:

- `0.x` does not guarantee API stability. A minor bump (`0.MINOR.0`) may include breaking changes; a patch bump (`0.0.PATCH`) is backward-compatible fixes only. The public API stabilizes at `1.0`.
- The public ABI is guarded in CI by [binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator) (`apiCheck`), which fails on **unintended** breaks.
- For an **intentional** API change: run `./gradlew apiDump` to update the `api/*.api` baselines, commit them, and release a **minor** bump.

The released version comes entirely from the tag (`-Pversion=<tag without the leading v>`). `gradle.properties` holds a neutral build default (`0.0.0`) used only for local builds.

## Why tags are created by a GitHub App

The repository's tag ruleset allows only the `CHLOE_CHAN` GitHub App to create tags and requires every tag to be signed. Tags created through the GitHub API are signed by GitHub's web-flow key (verified), which satisfies the rule. This is why releases are cut via `on-workflow-dispatch` (which mints an App token) rather than by pushing a tag locally.
