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

## Calibration — round 2 (2026-07-26, commit `45aee87`)

The 2026-07-23 round's 13 findings were fully merged by this run. This round's brief was
therefore two things at once: re-verify each prior fix still holds (all did — #30–#37 held up
under independent re-derivation), and give the three breaking changes that landed since
(ADR-0009 hosting split, ADR-0010 SPARQL binding, ADR-0011 `DatasetTx` composing ports) a
first-ever pass, since none of them existed at the time of round 1. Result: 11 new issues
(#64–#74), zero from `rdf-terms` (low weight held up — quick pass, nothing found).

| Sweep | Found |
|---|---|
| Phase 1/2 — hold an isolation-level choice from one implementation (`GraphStoreRdf4j`, SNAPSHOT) against a *sibling* implementation reusing the same scan code under a *different* isolation level (`DatasetTxRdf4j`, inherited SERIALIZABLE) | **#64** — the round's headline finding: `add`/`remove`/`count`/`export` on `DatasetTx` cause whole-graph false conflicts on any concurrent write to the same named graph, guard-read or not. Confirmed by an empirical repro harness, not source-reading alone. |
| Phase 3 — a port's "eviction, not deletion" promise held against every legal `DatasetStoreConfig`, not just the one the tests happen to construct | **#65** — `close()` on an `IN_MEMORY`-configured lifecycle is exactly as destructive as `delete()`, because `isNew` is unconditionally `true` when there's nothing persisted to check. Untested combination: nobody had run the eviction test with `IN_MEMORY` instead of `PERSISTENT`. |
| Phase 4 — a "neutral" port's own exception contract (or absence of one) held against what the wrapped library actually throws | **#66** — `rdf-shacl`'s zero `@throws` meant every RDF4J-typed unchecked exception (`ShaclShapeParsingException` et al.) passes straight through, contradicting ADR-0007's own stated reason for the module existing. |
| Phase 4 — a backend-neutral option (`ValidationOptions`) held against the backend's own escape hatches | **#67** — an RDF4J-proprietary predicate on a shape (`http://rdf4j.org/shacl-extensions#rdfsSubClassReasoning`) silently overrides the port-level `rdfsSubClassReasoning=false` a caller explicitly asked for. |
| Phase 1 — a documented rollback guarantee ("on RuntimeException or Error") held against the actual `catch` clause | **#68** — only `RuntimeException` is caught; the `Error` half of the promise is honored today only because RDF4J's `AbstractSailConnection.close()` happens to roll back on its own — an implementation detail, not a contract. |
| Phase 3 — a documented "must not call back into this lifecycle" prohibition held against whether the code actually stops it | **#69** — `onCreate` re-entrancy is prose-only; `ConcurrentHashMap.compute()`'s own contract says a violation is undefined, and nothing here fails fast. |
| Phase 4 — a port's declared scope held against what the *whole interface* offers, not just each method individually | **#70** — `shutDownAll()` (the natural `@PreDestroy` operation) exists only on the concrete RDF4J adapter, never made it onto the `DatasetLifecycle` port itself; ADR-0009 never discusses it. |
| Phase 5 — "does a module have any tests at all" as a zeroth-order check before auditing test *quality* | **#71** — `rdf-dataset-hosting`, a pure port module with real validation logic (`DatasetId`, `DatasetStoreConfig` canonical constructors), has no `src/test` directory whatsoever. |
| Phase 1 — `@throws` completeness re-checked on the *newest* code, not just the code round 1 already fixed | **#72** — `DatasetLifecycleRdf4j`'s constructor and the `DatasetLifecycle` port both under-document NPE paths that are correctly enforced but never named. |
| Phase 5 — three independent minor gaps bundled because none justified its own priority | **#73** (rdf-dataset-rdf4j: null-binding-value NPE, untranslated `QueryEvaluationException`, inferred-statement divergence) |
| Phase 5 — same bundling logic, other module | **#74** (rdf-shacl: `Severity.INFO` never produced in a test, `ShaclResult.path()==null` never exercised against real RDF4J output rather than just the record constructor, null-argument contract untested) |

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
- **SERIALIZABLE observes whole contexts, not just guard reads.** Under `IsolationLevels.SERIALIZABLE`,
  RDF4J's `ObservingSailDataset` records *any* accessed pattern as observed before delegating —
  a wildcard `size(context)`/`getStatements(null,null,null,false,context)` scan is indistinguishable
  from a deliberate optimistic-concurrency guard. Code reused across two implementations that differ
  only in isolation level (e.g. `GraphStoreRdf4j` at SNAPSHOT vs. `DatasetTxRdf4j` inheriting the
  transactor's SERIALIZABLE) needs the isolation level re-derived per call site, never assumed safe
  because a sibling method using the same scan code was already audited. Background: `[[#64]]`.
- **RDF4J's SHACL validator has its own escape hatches.** `ShaclValidator.Builder` defaults to
  `eclipseRdf4jShaclExtensions = true`, and a shapes graph can carry RDF4J-proprietary predicates
  (e.g. `http://rdf4j.org/shacl-extensions#rdfsSubClassReasoning`) that override a port-level
  `ValidationOptions` value the caller explicitly set. A "neutral" option on this port is only as
  neutral as the wrapped validator's defaults — check the builder's own defaults, not just the
  option's plumbing through to it. Background: `[[#67]]`.
- **A module split (ADR-0009-style) needs the same contract audit as new code, not less.** The
  hosting-module split addressed its stated leak (`DatasetStoreConfig` losing storage-layout
  fields) but round 2 still found genuinely new holes in the split code (`#65`, `#69`, `#70`,
  `#71`) — a refactor motivated by one specific finding does not imply the rest of the module is
  now clean; audit the whole pair again, not just the delta the ADR describes.
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
