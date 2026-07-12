# ADR-0006: CI-friendly versioning with `${revision}` and tag-triggered releases

Status: Accepted

## Context

The project is published as a Maven library, so it needs reproducible
coordinates and a repeatable release process. Two heavier options were weighed
and rejected:

- **`maven-release-plugin`** — makes two back-commits, needs `<scm>` and CI push
  rights back into the repo; fragile in container CI.
- **`gitflow-maven-plugin`** — a full Git-Flow with release/hotfix branches;
  too much ceremony for a trunk-near `develop` workflow.

## Decision

Use the CI-friendly `${revision}` approach with tag-triggered releases:

- The version lives as a single `<revision>` property in the root POM; every
  module references `${revision}`.
- Snapshots deploy from `develop`.
- A release is a Git tag `vX.Y.Z`; the release workflow derives the version from
  the tag and builds with `-Drevision=X.Y.Z deploy`. The POM itself is never
  modified. [Semantic Versioning](https://semver.org/) applies.
- Deployment to the package registry happens exclusively through CI.
- A separate `central-release` Maven profile (`-Pcentral-release`) adds
  source/javadoc jars, GPG signing and the Sonatype `central-publishing` plugin
  for a future Maven Central release. It is inactive by default, so everyday
  `verify` and the Forgejo snapshot/release deploys are unaffected. The Central
  release CI workflow is deferred until after the GitHub move (see
  [kogn-io/rdf#2](https://git.changinggraph.org/kogn-io/rdf/issues/2)).

This relies on Maven 4, whose native consumer-POM resolves `${revision}` at
deploy time, so no `flatten-maven-plugin` is needed. Maven comes from the pinned
`./mvnw` wrapper, so the build is repo-controlled.

## Consequences

- A single source of truth for the version; no release-plugin back-commits.
- A release is one traceable Git tag, decoupled from any CI-image rebuild.
- No `flatten-maven-plugin` thanks to the Maven 4 consumer POM.
- Maven 4 is currently a release candidate — a deliberate interim until GA.
- After each release the `<revision>` property is moved to the next development
  version by hand.
