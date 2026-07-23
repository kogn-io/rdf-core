# Kogn RDF — Contributor Notes

Backend-agnostic RDF abstraction: a pure data model, dataset ports and a
swappable RDF4J backend. Published under Apache-2.0.

## Modules

| Directory | Artifact | Role |
|---|---|---|
| `rdf-terms` | `io.kogn.rdf:rdf-terms` | RDF data model (term interfaces, graph types, vocab). **Library-free** — no external dependencies. |
| `rdf-dataset` | `io.kogn.rdf:rdf-dataset` | Backend-agnostic dataset ports: `GraphStore`, `SparqlQuery` (read), `SparqlUpdate` (write), `DatasetTransactor`, `DatasetTx`, `DatasetLifecycle` (open-or-create/close/delete/list by opaque `DatasetId`, lease-protected; `acquire` returns a leased `DatasetHandle` — an access handle, not an RDF dataset). |
| `rdf-dataset-rdf4j` | `io.kogn.rdf:rdf-dataset-rdf4j` | RDF4J implementation of the dataset ports. |
| `rdf-shacl` | `io.kogn.rdf:rdf-shacl` | Backend-agnostic SHACL validation port: `ShaclValidation.validate(data, shapes, options)` over `ReadableGraph`, returning a neutral `ShaclReport`/`ShaclResult`/`ShaclMessage`/`Severity` (every `sh:message` survives with its language tag; the port picks no language). **Depends only on `rdf-terms`** — no rdf4j. |
| `rdf-shacl-rdf4j` | `io.kogn.rdf:rdf-shacl-rdf4j` | RDF4J implementation of the SHACL port (wraps `ShaclValidator`). **Store-independent** — does not depend on `rdf-dataset`/`-rdf4j`. |

Directory name = artifact id; the Java packages are `io.kogn.rdf.*`.

## Tech stack

- **Java 25** (`maven.compiler.release=25`)
- **Maven 4** via the checked-in wrapper (`./mvnw`) — no local Maven needed.
- Versions centralized via `${revision}` in the root `pom.xml`.

## Build & test

```bash
./mvnw verify          # build + test (rdf4j integration tests run too)
./mvnw -pl <module> test
```

## Branches & releases

`main` is the only long-lived branch and the default — feature branches fork off
it, pull requests target it. There is no `develop`; a maintenance branch is cut
only if an older line ever needs a patch.

A **ruleset enforces this**, it is not a habit you can skip: `main` takes no
direct pushes, and there is no bypass — the rule binds admins as well. Every
change lands through a pull request on which CodeQL must have reported. No
approving review is required (single maintainer), so a green pull request can be
merged by its own author. Only `error`-level alerts and security alerts of `high`
or worse block the merge; `note`-level findings do not — they surface in the
Security tab instead of standing in the way, so quality findings still need
someone to look. Releases are unaffected: `release.yml` pushes the `vX.Y.Z` tag
only, never a commit on `main`.

Pull requests land by **rebase** — `gh pr merge <nr> --rebase`. GitHub allows all
three strategies on this repository, so the convention is not enforced and has to
be followed by hand: `main` stays linear, and a pull request keeps its individual
commits rather than collapsing into one. Squashing would fold a review round
("fix the finding") back into the commit it corrected, which is exactly the
history worth keeping. Write commits that are worth reading on `main`, then.

Deployment runs **exclusively through CI** (GitHub Actions), never locally:

- **Snapshot** — every push to `main` deploys the current `${revision}` (a
  `*-SNAPSHOT`, see the root POM for the exact value) to the Maven Central
  Portal snapshot repository.
- **Release** — start the *Release to Maven Central* workflow by hand on `main`
  (Actions tab, or `gh workflow run release.yml -f version=X.Y.Z --ref main`);
  the version goes in without a leading `v`. It builds with `-Drevision=X.Y.Z
  -Pcentral-release`, GPG-signs, uploads the bundle, and only then tags the
  commit `vX.Y.Z` and cuts a GitHub release — so a failed run leaves no tag
  behind. Pushing a `v*` tag by hand triggers nothing.

The POM sets `autoPublish=false`: Central validates the bundle, and the final
"Publish" is confirmed by hand in the portal. The tag therefore marks *uploaded*,
not *published*. Afterwards bump `<revision>` in the root POM to the next
`-SNAPSHOT`.

## Conventions

- **Conventional Commits** (`type(scope): subject`), **Semantic Versioning**.
- `record` for value objects (Commons-RDF-oriented term types).
- `rdf-terms` stays **library-free** — do not add dependencies there.
- Follow existing patterns, don't guess — look it up in the code.
- Tests: JUnit 5 + AssertJ. Test domain logic; bug fix = failing-test-first.
- **ALWAYS read a file before editing** (the build formats via spotless automatically).
- **License header**: every `.java` carries the SPDX header at the top of the file
  (`// SPDX-License-Identifier: Apache-2.0` + `// Copyright <year> Fred Hauschel`).
  The source is `java-license-header.txt`; spotless (`licenseHeader`) writes/enforces
  it — `spotless:apply` adds missing ones, `spotless:check` (in `verify`) is the
  CI gate. Exception: `package-info.java` (deliberately skipped by spotless).
- **Javadoc gate**: the public/protected API must be fully documented.
  `maven-javadoc-plugin` runs in `verify` with `doclint=all` + `failOnWarnings` —
  missing comments/`@param`/`@return`/`@throws` or unresolvable `{@link}` fail the
  build; every package carries a `package-info.java`. Private members are exempt
  (default `show=protected`). The release javadoc jar (`-Pcentral-release`)
  inherits the same config (no more `doclint=none`).
