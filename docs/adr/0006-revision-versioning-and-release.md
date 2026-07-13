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

## Update 2026-07-12 — GitHub move done

The host-coupled work deferred above is complete. The project moved to
[github.com/kogn-io/rdf-core](https://github.com/kogn-io/rdf-core). CI is now
GitHub Actions (`.github/workflows/`): push to `develop` deploys snapshots to the
Maven Central Portal snapshot repository, and a `vX.Y.Z` tag runs the
`central-release` profile to publish to Maven Central. The Forgejo workflows and
the Forgejo package registry are retired; POM `<url>`/`<scm>` point at the GitHub
repo and `distributionManagement` targets the Central snapshot repo.

## Update 2026-07-13 — first release deferred; the "no flatten" claim does not hold on rc-5

The "no `flatten-maven-plugin` needed" consequence assumed Maven 4's consumer POM
resolves `${revision}` in the *deployed* POM. On the pinned `4.0.0-rc-5` it does not:
the consumer POM keeps `${revision}` (and the `<revision>` property) in
`dependencyManagement`, and the Sonatype Central **Portal validator** rejects the
unresolved placeholder ("dependency management dependencies to SNAPSHOT versions not
allowed" / "version cannot be a SNAPSHOT"). The consumer-side interpolation fix
[apache/maven#12303](https://github.com/apache/maven/issues/12303) merged *after* rc-5
and is in no released Maven; even a post-fix Maven `4.0.0-SNAPSHOT` keeps the placeholder
*in the deployed file*, so it does not satisfy the Portal either.

Consequence: the first `io.kogn.rdf:*` release (`0.0.1`) is **deferred**; consumers stay
on `0.0.1-SNAPSHOT` (the Central snapshot repo has no strict validator). The tag-triggered
mechanism and the `central-release` profile are otherwise proven end-to-end up to the
Portal upload — two real bugs were fixed on `develop` while getting there:

- removed `maven.deploy.skip=true` from the profile — it silently skipped the Central
  publish too (`central-publishing:publish`'s `skip` defaults to `${maven.deploy.skip}`);
- bound `central-publishing:publish` explicitly to the `deploy` phase and unbound
  `maven-deploy-plugin:default-deploy` — under Maven 4 the `extensions=true` auto-binding
  does not fire, so the deploy phase ran no goal (yet the build went green).

Candidate fix when revisited (untested): Maven 4's native `-Dmaven.consumer.pom.flatten=true`
(flattens `dependencyManagement` in the consumer POM), preferred over reintroducing
`flatten-maven-plugin`. Revisit once a Maven 4 release ships apache/maven#12303. Tracked in
[kogn-io/rdf#6](https://git.changinggraph.org/kogn-io/rdf/issues/6).

## Update 2026-07-13 (later) — root cause found: the publish plugin, not `${revision}`

The deferral above blamed `${revision}` in the consumer POM. A focused reproduction
(`maven4-revision-consumerpom-mre`) disproved that. The real blocker is the official
`org.sonatype.central:central-publishing-maven-plugin:0.11.0`, which under Maven 4 bundles
the *raw build POM* as the main `X-1.0.0.pom` (its `<version>` still `${revision}`, its
`<revision>` property the default `0.0.1-SNAPSHOT`) — the source of every Portal error.

Fix: switch to the community fork
`io.github.mavenplugins:central-publishing-maven-plugin:1.3.0`, which stages the resolved
consumer POM. Confirmed by a real Portal validation upload (`autoPublish=false`, then
dropped): no SNAPSHOT / coordinates / dependencyManagement / file-path errors remain — only
the standard metadata fields (URL/license/SCM/developers). `${revision}` in
`dependencyManagement` does NOT block (the validator resolves it against the property once
the main POM is resolved); `-Dmaven.consumer.pom.flatten` and `flatten-maven-plugin` do
nothing. Next: verify the plugin swap on the real repo, then cut the release.
