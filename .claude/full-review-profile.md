# Full-review profile — kognio-rdf

Project calibration for the `/full-review` skill. The skill carries the method; this file
carries what is specific to this codebase. Read it before starting the audit.

## Module weighting

| Module | Weight | Why |
|---|---|---|
| `rdf-dataset` | **highest** | pure contract; every promise here binds all future backends |
| `rdf-dataset-rdf4j` | **highest** | the only code that has to keep those promises |
| `rdf-dataset-hosting` | **highest** | port module with real validation logic and lifecycle contracts |
| `rdf-dataset-hosting-rdf4j` | **highest** | owns stores and leases; the round-2 lifecycle findings all lived here |
| `rdf-cid` | high | its own promise ("same content -> same identifier") is a correctness property, not just a contract; the only backend-free module with a third-party dependency footprint at all (ADR-0014); wrong output is silent (a wrong CID looks exactly like a right one) |
| `rdf-shacl` / `rdf-shacl-rdf4j` | medium | smaller surface, value types, no concurrency |
| `rdf-terms` | low | library-free data model, no I/O, no state |

At ~6k LOC the whole tree fits in one pass. Do not sample.

## Review-Kadenz

| Modul | Pfad | Priorität | Letzter Review-Commit | Datum |
|---|---|---|---|---|
| rdf-dataset | `rdf-dataset` | 1 | `2853056` | 2026-10-03 |
| rdf-dataset-rdf4j | `rdf-dataset-rdf4j` | 1 | `2853056` | 2026-10-03 |
| rdf-dataset-hosting | `rdf-dataset-hosting` | 1 | `2853056` | 2026-10-03 |
| rdf-dataset-hosting-rdf4j | `rdf-dataset-hosting-rdf4j` | 1 | `2853056` | 2026-10-03 |
| rdf-shacl | `rdf-shacl` | 2 | `2853056` | 2026-10-03 |
| rdf-shacl-rdf4j | `rdf-shacl-rdf4j` | 2 | `2853056` | 2026-10-03 |
| rdf-terms | `rdf-terms` | 3 | `2853056` | 2026-10-03 |

The 2026-10-03 stamp (round 4) is a from-scratch audit of all modules except `rdf-cid`
(excluded; it follows the #142 state and has no row yet) plus an independent re-check of the
earlier fixes. Before that, the 2026-08-06 stamp covered the delta since `45aee87` in the four dataset modules (the
DatasetExport vertical plus the round-2 fix commits) and an independent re-derivation of the
#64/#68/#73 fixes — not a from-scratch re-audit of the unchanged remainder.

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

## Calibration — round 3 (2026-08-06, commit `357a557`)

Scoped run: the DatasetExport vertical (new in v0.3.0) plus re-derivation of the #64/#68/#73
fixes. All three prior fixes held. New findings, per sweep:

| Sweep | Found |
|---|---|
| Phase 1.4 / Phase 6 — a whole-dataset claim ("every statement tagged with the graph it belongs to") held against a state the model officially excludes but a sibling port can create | **#98** — the headline: `SparqlUpdate` `INSERT DATA` without a `GRAPH` clause puts statements into the default graph; `conn.export` writes them untagged (TriG unnamed block, N-Quads triple line), and no port can reach them individually. The named-graphs-only invariant is caller-maintained but documented nowhere as a caller obligation. |
| Phase 1.1 — the catch clause held against the *full* set of unchecked exceptions the wrapped calls can raise, verified at the source jars | **#99** — `UnsupportedRDFormatException extends RuntimeException` directly (not `RDF4JException`), so `DatasetExportRdf4j`'s `catch (RepositoryException \| RDFHandlerException)` misses it; sail iterations can likewise rethrow foreign `RuntimeException`s unwrapped. |
| Phase 5 — "which documented sentence has no failing test" | **#100** — "Namespaces travel along" (trivially testable, untested), `includeInferred=false` (needs an inferencer sail), N-Quads named-graph round-trip (only TriG is parse-back-tested), error/stream-hygiene tests only exercise the 2-arg overload. |

Verification lesson that paid off: reading the RDF4J *source jars* (not just javap) settled
`includeInferred`, the `endRDF()` flush chain, and the exception hierarchy in one pass —
`./mvnw dependency:sources` first, then `unzip -p` on `~/.m2/.../*-sources.jar`.

## Calibration — round 4 (2026-10-03, commit `2853056`)

From-scratch audit of every module except `rdf-cid`, one reviewer per module pair, each
finding list re-verified by a fresh adversarial verifier with its own repro. 27 findings
(4 P1, 8 P2, 15 P3; 23 confirmed, 4 plausible, 0 refuted), collected in issue #147. Re-check of
the earlier fixes: all hold on their original path, but six fix families recurred on a
sibling site (#112, #68, #72, #98, #67, #85 -> R2-1, R2-2, R2-6, R1-3, R3-2/R3-6, R4-1).

| Sweep | Found |
|---|---|
| Phase 3 — a fixed invariant (#112 "never hand out or delete an intact dataset") attacked through a *different* code path than the one fixed (create rollback instead of delete) | **R2-1** — `File.list() == null` read as "new", then rollback/marker/next `acquire` deletes an intact dataset. |
| Phase 1/3 — the #68 lesson ("catch clause vs. `Error`") applied to the sister site | **R2-2** — `createAndSeed` catches only `RuntimeException`. |
| Phase 3/1.4 — ask a *returned* object (`ReadableGraph`, `BindingSet`) with the methods of its own interface | **R1-1** — an exported graph does not find its own triples. Earlier rounds only read port methods against their Javadoc. |
| Phase 1.2 — isolation promise with an *overlapping* instead of disjoint scenario | **R1-2** — same triple written concurrently double-counts the delta (1000 runs without barrier: 95-99 % deviation). |
| Phase 1.4/6 — a fixed Javadoc paragraph checked for symmetry against the neighbouring case (insert fixed, delete not) | **R1-3** — #98 documented `INSERT`, not `DELETE`/`count()`. |
| Phase 4 — what does the backend accept that the port model cannot represent | **R1-4** — RDF 1.2 triple terms. |
| Phase 4 — builder fields *and* short-circuits in the backend (`readShapes`, `validateInternal`), not only the flag the last issue named | **R3-2** (`sh:shapesGraph`), **R3-3** (1000-result limit), **R3-6** (DASH). |
| Malformed-input sweep (new) — feed deliberately broken structures to ports that interpret foreign graph content (non-list, open list, cycle) | **R3-1** — heap exhaustion in `ShaclAstLists.toList`. Only found this way. |
| Value-rendering sweep (new) — every `Value::stringValue` at a port boundary against blank-node and literal cases | **R3-4**, **R3-5**. |
| Phase 4 — "second implementation against the same contract": ~50 single calls across both factories, input -> result/exception per backend side by side (one build run) | **R4-1**, **R4-3**; also surfaced **R4-2** (`ntriplesString`, "reads as verified"). |
| Phase 1.1 — `@throws` re-checked on the *newest* code | **R2-6** — `clearUnfinishedDelete` again without NPE documentation (#72 pattern). |
| Phase 5 — "does a documented sentence have a failing test" | **R4-4**, **R3-9**, plus the missing overlapping-triple test behind R1-2. |

Empty or thin: Phase 1.1 for the SPARQL paths (#31/#73/6e2bb67 clean); Phase 2 commit path of
the transactor (conflicts only in prepare/commit, verified in source); Phase 2 isolation for
the hosting modules (they open no transactions of their own); nesting/ThreadLocal;
`rdf-terms` in isolation (nothing grave — its value lay in the counterparts).

New traps and rules:

- **Existence/empty checks that precede a destructive branch** (`File.list() == null`,
  `Files.exists() == false` also mean "cannot be determined"): hold each against the I/O error
  case (repro: `chmod -wx` on the dataset directory, as non-root).
- **Fix-family recurrence:** after every fixed finding, look for the same error class at the
  sister sites (#67 -> other backend flags, #68 -> other `catch` clauses, #72 -> new methods,
  #85 -> private converter copies, #98 -> the opposite operation).
- **Surefire output with `-q` goes to `target/surefire-reports/*.txt|xml`**, not to stdout;
  grep there. Probe output with line breaks is cut up by grep — escape in the probe.
- **SHACL/malformed-list repros: run with `-DargLine=-Xmx256m` and a hard timeout**
  (`assertTimeoutPreemptively`, `-Dsurefire.timeout`). RDF4J loops endlessly until the heap is
  gone; without a limit the surefire JVM took 14 GB RSS and >7 min.
- **Build without install:** `./mvnw -o -pl <module> -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`
  builds in the reactor; never `mvn install` in parallel work.
- **Deterministic races instead of `@RepeatedTest`:** a blocking hook (`onCreate`) plus a
  thread-state check (`BLOCKED`) proves a race without sleeps (R2-3); a barrier in the input
  graph's `stream()` makes an overlap deterministic.
- **Javadoc measures against 6.0.0, the POM is on 6.1.0** (`DatasetTransactorRdf4j`): the
  #23/#52 rates were not re-measured in round 4.
- **RDF4J source jars** (`~/.m2/.../6.1.0/*-sources.jar`, `unzip -p`) settled `SailUpdateExecutor`,
  `MemorySailStore`, `ShaclAstLists`, `readShapes` in one pass each.
- **Verifier lesson:** reviewer claims about "changes every run" or "silently wrong value"
  need their own repro — R3-4 and R4-3/A1 were each partly refuted that way.

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
