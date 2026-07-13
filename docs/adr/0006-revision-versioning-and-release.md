# ADR-0006: CI-friendly versioning with `${revision}` and tag-triggered releases

Status: Accepted

## Context

The project is published as a Maven library to Maven Central, so it needs
reproducible coordinates and a repeatable release process. Two heavier options
were weighed and rejected:

- **`maven-release-plugin`** — makes two back-commits, needs `<scm>` and CI push
  rights back into the repo; fragile in container CI.
- **`gitflow-maven-plugin`** — a full Git-Flow with release/hotfix branches; too
  much ceremony for a trunk-near `develop` workflow.

## Decision

Use the CI-friendly `${revision}` approach with tag-triggered releases, run
exclusively through GitHub Actions:

- The version lives as a single `<revision>` property in the root POM; every
  module references `${revision}`. The release never modifies the POM.
- Push to `develop` deploys snapshots to the Maven Central Portal snapshot
  repository.
- A release is a Git tag `vX.Y.Z`; the release workflow derives the version from
  the tag and builds with `-Drevision=X.Y.Z deploy`.
  [Semantic Versioning](https://semver.org/) applies.
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
- A release is one traceable Git tag, decoupled from any CI-image rebuild.
- Maven 4 is currently a release candidate — a deliberate interim until GA.
- After each release the `<revision>` property is moved to the next development
  version by hand.
- The mechanism is proven end-to-end (a `vX.Y.Z` tag produces a Portal-validated
  bundle). The first `io.kogn.rdf` release is deferred by choice; consumers use
  `0.0.1-SNAPSHOT` until then.
