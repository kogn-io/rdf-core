# Full-review profile — kogn-rdf

Project calibration for the `/full-review` skill. The skill carries the method; this file
carries what is specific to this codebase. Read it before starting the audit.

## Module weighting

| Module | Weight | Why |
|---|---|---|
| `rdf-dataset` | **highest** | pure contract; every promise here binds all future backends |
| `rdf-dataset-rdf4j` | **highest** | the only code that has to keep those promises |
| `rdf-shacl` / `rdf-shacl-rdf4j` | medium | smaller surface, value types, no concurrency |
| `rdf-terms` | low | library-free data model, no I/O, no state |

At ~6k LOC the whole tree fits in one pass. Do not sample.

## Calibration — what each sweep has already found here

The audit of 2026-07-23 produced 13 issues (#30–#42) on a codebase that had been through
several PR reviews. Every finding below came from the sweep named next to it; a sweep that
cannot point at one on a later run is worth questioning.

| Sweep | Found |
|---|---|
| Phase 1.1 — every `@throws` against the real type | **#31** — eight port methods document `IllegalArgumentException`; RDF4J throws `MalformedQueryException` (`extends RDF4JException`), confirmed by unpacking `rdf4j-query-6.0.0.jar` |
| Phase 1.2 — isolation/atomicity claims against the real `begin(...)` | **#32** — `GraphStore.add/remove` promise a delta "measured atomically … concurrent writers cannot distort"; `GraphStoreRdf4j` uses a bare `conn.begin()` and runs at `SNAPSHOT_READ` |
| Phase 1.3 — unenforced "implementations must not" | **#34** — nested transactions forbidden in the Javadoc, never checked in code |
| Phase 1.4 — runtime adjectives, also against each other | **#36** (`count()` documented as an estimate while the `add()` delta must be exact — both are `conn.size()`), **#37** (`DatasetTx.select` documented lazy, implemented eager) |
| Phase 2 — neutral failure type on the commit path | **#30** — the conflict guarantee named no catchable type, so acting on it meant importing `RepositoryException` |
| Phase 3 — failure paths of a resource owner | **#33** (a failed `deleteStorageOnDisk` leaves a shut-down store in the cache; the next `acquire` hands out a dead handle), **#35** (leases protect nothing — the handle accessors return shared, lease-blind port objects) |
| Phase 4 — hold each promise against a second backend | **#40** (`DatasetTransactor` requires commit-time conflict detection, which the SPARQL protocol cannot provide, and no capability model lets an adapter say so), **#41** (`DatasetLifecycle` carries hosting concerns, not RDF ones) |

## Project-specific traps

- **Concurrency claims need repetition, not a run.** The conflict-detection gap behind #23
  reproduced at 6% on one machine and 12% on another. A single green run proves nothing here;
  use `@RepeatedTest` with hundreds of iterations and treat the rate as timing-dependent.
  Background: memory entry `flaky-rate-needs-1000-runs`.
- **Read ADR-0008 before reasoning about guard reads.** `DatasetTx#contains` exists because a
  SPARQL `ASK` guard is not conflict-protected for first-time inserts under `SERIALIZABLE` —
  the cause is value interning inside RDF4J, not anything in this code. Do not re-derive it.
- **RDF4J store defaults.** `MemoryStore` and `NativeStore` both default to `SNAPSHOT_READ`.
  Any `begin()` without an explicit `IsolationLevels` argument inherits that, and
  `SNAPSHOT_READ` only guarantees that a *single* query result is internally consistent — two
  successive reads in one transaction are not covered.
- **The documentation reads as verified but is not.** Four findings of the 2026-07-23 audit are
  the same pattern: precise, measured-sounding Javadoc that the implementation does not deliver.
  Treat a contract sentence in `rdf-dataset` as a claim to check, never as an established fact.
  Background: memory entry `port-javadoc-outruns-the-implementation`.
- **Group API-breaking fixes.** `0.2.0-SNAPSHOT` already carries unreleased breaking changes.
  Anything from an audit that changes the public API belongs in the same release window, or
  consumers break twice. Background: memory entry `shacl-message-break-consumer-migration-open`.

## Where the decisions live

- ADRs: `docs/adr/` (in-place, `README.md` is the index)
- Conventions and release model: `CLAUDE.md`
- Architecture: `ARCHITECTURE.md`
- Issue tracker: GitHub, `kogn-io/rdf-core` — use `gh`, not the Forgejo tooling; the repository
  moved in 2026-07. Labels: `bug` / `enhancement` / `documentation` / `question` plus
  `priority: high|medium|low`.
