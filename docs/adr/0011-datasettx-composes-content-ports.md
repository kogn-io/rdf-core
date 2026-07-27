# ADR-0011: `DatasetTx` composes `GraphStore`, `SparqlQuery` and `SparqlUpdate`

Status: Accepted — amended by
[ADR-0012](0012-per-triple-conflict-surface-for-add-remove.md): inside a
transaction the delta is no longer computed by before/after `size(context)`
sampling but per triple.

## Context

`DatasetTx` re-declared what `GraphStore`, `SparqlQuery` and `SparqlUpdate`
already declare — `add`, `remove`, `clear`, `update`, `select`, `ask`,
`construct` — with near-identical Javadoc, and it did so **incompletely**:
`export(IRI)` and `count(IRI)` / `count()` had no transactional counterpart.
The gap was a real capability hole, not just duplication: a unit of work
could not count or export a named graph without leaving the transaction
(losing atomicity and read-your-writes) or reimplementing counting via
`select` with a `COUNT(*)` aggregate. Every future port method also had to be
added in two places, kept in sync, and documented twice. Tracked in
[issue #39](https://github.com/kogn-io/rdf-core/issues/39).

The issue itself named the blocker to closing this properly: `GraphStore.add`
/ `#remove` return `long` (the net delta), while `DatasetTx.add` / `#remove`
returned `void` — so `DatasetTx` could not simply `extends GraphStore`
without also resolving that mismatch, and the delta itself needed a settled
exactness contract first. That contract is now in place
(`GraphStoreRdf4j`'s delta is measured atomically against a `SNAPSHOT`
transaction, not estimated — see the `count`/`add`/`remove` exactness
documentation on `GraphStore`), so the blocker no longer applies.

## Decision

`DatasetTx` now extends `GraphStore`, `SparqlQuery` and `SparqlUpdate`
directly instead of re-declaring their methods:

```java
public interface DatasetTx extends GraphStore, SparqlQuery, SparqlUpdate {
  boolean contains(IRI namedGraph, BlankNodeOrIRI subject, IRI predicate, RDFTerm object);
}
```

`contains` (ADR-0008) remains the one operation genuinely specific to
`DatasetTx` — it has no non-transactional equivalent and stays hand-declared.
Every other operation, including `export` and `count`, is now inherited: a
`DatasetTx` implementation must supply them, and `DatasetTxRdf4j` does so
against the shared `RepositoryConnection` the transactor hands it — a few
lines each, mirroring `GraphStoreRdf4j`'s implementation, since both work
against the same RDF4J connection API.

`GraphStore.add` / `#remove`, called through a `DatasetTx`, now return the
same net-delta `long` they return on `GraphStore` — computed the same way
(`RepositoryConnection#size(context)` sampled before and after the mutation),
except that no extra sub-transaction is opened for it: the calls already run
inside the transaction `DatasetTransactorRdf4j` opened, so before/after
sampling on the shared connection is already consistent.

`GraphStore`, `SparqlQuery` and `SparqlUpdate`, used directly rather than
through a `DatasetTx`, each treat a single call as its own implicit,
single-operation transaction — no atomicity across two calls. Composing them
into `DatasetTx` is what turns a sequence of those same operations into one
atomic unit of work, scoped to a single `DatasetTransactor#inTransaction`
call; that distinction, not a difference in what each operation does, is the
entire reason `DatasetTx` exists alongside the other three ports rather than
replacing them.

The alternative — keep the flat, duplicated declaration and add `export` /
`count` to it by hand a third time — was rejected: it repeats the exact
maintenance cost the issue reported (Javadoc kept in sync in two places per
method) and does nothing to prevent the next new port method from reopening
the same gap.

## Consequences

- `DatasetTx` gains `export(IRI)`, `count(IRI)` and `count()` as inherited
  abstract methods; every implementation, including outside this repository,
  must add them. There is exactly one implementor in this repository,
  `DatasetTxRdf4j`.
- `DatasetTx.add` / `#remove` change from `void` to `long`, a breaking
  signature change for the same implementor. No call site in this repository
  uses the previous `void` return value (every call is a bare statement
  expression), so this is a source-compatible recompile here, but it is
  still a binary- and source-breaking change for any external implementor.
  This lands in the still-unreleased `0.2.0-SNAPSHOT`, alongside the other
  breaking changes already queued for that release window.
- Documentation for `add`/`remove`/`clear`/`select`/`ask`/`construct`/`update`
  now lives once, on `GraphStore`/`SparqlQuery`/`SparqlUpdate`; `DatasetTx`'s
  own Javadoc covers only what it adds — the transactional unit-of-work
  contract and `contains`.
- No other type in this repository implements `DatasetTx` directly (the
  hosting adapter's handle-bound delegates wrap `GraphStore`, `SparqlQuery`
  and `SparqlUpdate` individually, and its `HandleBoundDatasetTransactor`
  forwards straight to the underlying `DatasetTransactorRdf4j`, so it never
  needed a `DatasetTx`-shaped wrapper of its own); this decision does not
  touch `rdf-dataset-hosting-rdf4j`.
