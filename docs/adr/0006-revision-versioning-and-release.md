# ADR-0006: CI-friendly versioning with `${revision}` and manually triggered releases

Status: Accepted

## Context

The project is published as a Maven library to Maven Central, so it needs
reproducible coordinates and a repeatable release process. Two heavier options
were weighed and rejected:

- **`maven-release-plugin`** — makes two back-commits, needs `<scm>` and CI push
  rights back into the repo; fragile in container CI.
- **`gitflow-maven-plugin`** — a full Git-Flow with release/hotfix branches; too
  much ceremony for a trunk-based workflow.

The branching model is trunk-based: `main` is the only long-lived branch and the
default; a maintenance branch is cut only if an older line ever needs a patch.

A tag-triggered release (`on: push: tags: ['v*']`) was the first design and was
replaced. A Git tag carries no branch: `git tag v0.2.0` typed while sitting on a
feature branch would have published unmerged code. Guarding that by checking the
tagged commit against `main` after checkout treats the symptom; removing the tag
trigger removes the failure mode.

## Decision

Use the CI-friendly `${revision}` approach with manually triggered releases, run
exclusively through GitHub Actions:

- The version lives as a single `<revision>` property in the root POM; every
  module references `${revision}`. The release never modifies the POM.
- Push to `main` deploys snapshots to the Maven Central Portal snapshot
  repository.
- A release is a manual `workflow_dispatch` run of `release.yml` on `main` with
  the version as an input; the workflow builds with `-Drevision=X.Y.Z deploy`.
  [Semantic Versioning](https://semver.org/) applies. The run is rejected off any
  other branch, on a non-SemVer version, and on a version that is already tagged.
- The tag `vX.Y.Z` and the GitHub release are created by the workflow **after** a
  successful upload, never before. Pushing a `v*` tag by hand triggers nothing.
- The `central-release` profile (`-Pcentral-release`, inactive by default) adds
  source/javadoc jars, GPG signing and the `central-publishing` plugin, and
  uploads a bundle to the Sonatype Central Portal with `autoPublish=false` — the
  bundle is validated, then publishing is confirmed manually.

The `central-publishing` plugin is the community fork
`io.github.mavenplugins:central-publishing-maven-plugin:1.3.0`, **not** the
official `org.sonatype.central:central-publishing-maven-plugin:0.11.0`: under
Maven 4 the official plugin stages the raw build POM (with an unresolved
`${revision}`) as the main POM, which the Portal validator rejects; the fork
stages the resolved consumer POM, so the uploaded main POM carries the
interpolated version and the validator accepts it.

This relies on Maven 4 (pinned via the `./mvnw` wrapper), whose consumer POM
carries the resolved `${revision}`, so no `flatten-maven-plugin` is needed.

## Consequences

- A single source of truth for the version; no release-plugin back-commits.
- Releasing is a deliberate act rather than a side effect of a git command: a
  stray tag publishes nothing.
- A failed run leaves no tag behind, so tags never point at a release that did
  not happen.
- The tag marks *uploaded and validated*, not *published* — the portal
  confirmation still follows by hand, so the two can briefly disagree.
- Releasing needs the GitHub UI or `gh workflow run`; it is no longer a pure git
  operation. Acceptable, since contributors never release.
- Maven 4 is currently a release candidate — a deliberate interim until GA.
- After each release the `<revision>` property is moved to the next development
  version by hand.
- The `${revision}` + `central-publishing` mechanism is proven end-to-end (a
  Portal-validated bundle was produced under the earlier tag trigger); the
  dispatch trigger itself is unproven until the first real release. The first
  `io.kogn.rdf` release is deferred by choice; consumers use `0.0.1-SNAPSHOT`
  until then.
