# ADR-0008: `DatasetTx#contains` as the conflict-protected guard read

Status: Proposed

## Context

`DatasetTransactorRdf4j` requests `SERIALIZABLE` isolation so an
optimistic-concurrency guard (read a condition, then write based on it) is
safe: RDF4J tracks the statement patterns a transaction read and fails the
losing commit with a `SailConflictException` if a concurrent transaction
changed one of them.

That guarantee has a gap, measured on RDF4J 6.0.0 + `MemoryStore`: a SPARQL
`ASK` guard whose IRIs are not yet known to the store — the "is this
brand-new resource already taken?" case — is not reliably conflict-protected.
In a two-thread race where both guards run before either write, both
transactions committed in a single-digit to low double-digit percentage of
1000 runs (6% and 12% measured on two machines; treat the rate as
timing-dependent, not a constant), leaving the duplicate the guard was meant
to prevent. The same race detects the conflict in 1000 of 1000 runs once the
guard's subject, predicate and graph IRIs are already present in the store, or
when the guard reads through `RepositoryConnection#hasStatement` instead of
SPARQL.

The cause is in RDF4J, not in this module or in the port: evaluating a SPARQL
statement pattern interns its constants into the store's own value registry,
and two threads interning the same unknown IRI concurrently can each end up
with their own instance, because the registry's duplicate recovery cannot
fire. Conflict detection then walks the statement list of the wrong instance,
finds it empty, and both commits pass. A guard that only *looks up* an
existing value never enters that path. Reproducible with plain RDF4J APIs, no
`io.kogn` code involved. Tracked in
[issue #23](https://github.com/kogn-io/rdf-core/issues/23).

Two ways to close the gap were on the table:

1. **Serialize writes in `DatasetTransactorRdf4j`** with a JVM-local lock, so
   the loser reads fresh state instead of racing at all.
2. **Add a non-SPARQL guard read to the port**, mapped to a backend lookup
   that does not go through query evaluation.

## Decision

Add `DatasetTx#contains(namedGraph, subject, predicate, object)` — `null` as
wildcard for any component, following the existing convention on
`ReadableGraph#stream(subject, predicate, object)` — and implement it in
`DatasetTxRdf4j` via `RepositoryConnection#hasStatement`. This states the
guard as a statement pattern rather than as a query, so the RDF4J adapter can
answer it from the backend's own pattern lookup instead of through query
evaluation — the path that loses the conflict. Callers doing first-insert
uniqueness must use `contains`, not `ask`, for the guard read; `DatasetTx` and
`DatasetTransactorRdf4j` document this.

The JVM-lock alternative was rejected. It only holds within one JVM against
one `Repository` instance — nothing against a remote store or a second
process — while changing the observable semantics (the loser reads fresh
state and its guard passes, instead of failing loudly with a conflict). A
guard that *looks* like it holds everywhere but silently doesn't is worse than
one that is honestly scoped, and `inTransaction`'s current contract — the
loser's commit fails, the caller can catch and retry — is worth keeping.

This is a **port addition**, not a repair of `ask`. A SPARQL `ASK` guard
remains a legitimate read; it is only the first-insert uniqueness use of it
that this backend does not protect, and that limit is not removed by this
decision — `contains` is offered beside it, not instead of a general fix.
Reporting the underlying gap to Eclipse RDF4J was considered and left out of
scope here: it does not change what this port can guarantee on the current
release, and is tracked separately.

## Consequences

- `DatasetTx` gains an abstract method; every implementation, including
  outside this repository, must add it. There are none besides
  `DatasetTxRdf4j` here. This lands in the still-unreleased `0.2.0-SNAPSHOT`,
  alongside the unrelated `ShaclResult` break from issue #20.
- The isolation guarantee documented on `DatasetTransactorRdf4j` is now split
  in two: `contains` is conflict-protected for first-time inserts as measured
  (1000 of 1000); a SPARQL `ASK` guard is not, and must not be used for that
  case.
- `select`/`ask`/`construct` are unchanged and remain the right choice for
  ordinary reads; `contains` is additive, scoped to the guard-read use case.
