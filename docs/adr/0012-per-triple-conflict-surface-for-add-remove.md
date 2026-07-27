# ADR-0012: Per-triple conflict surface for `DatasetTx#add`/`#remove`

Status: Accepted

## Context

`DatasetTransactorRdf4j` opens its connection at `IsolationLevels.SERIALIZABLE`
so RDF4J can detect a lost optimistic-concurrency race: it tracks which
statement patterns a transaction read and fails the losing commit with a
`SailConflictException` if a concurrently committed transaction changed one of
them.

`DatasetTxRdf4j#add` and `#remove` computed their net-delta return value the
way `GraphStoreRdf4j` does — sampling `RepositoryConnection#size(context)`
before and after the mutation (ADR-0011). Under RDF4J's `ObservingSailDataset`,
`size(context)` is not a narrow read: `SailSourceConnection#sizeInternal`
delegates to `getStatements(null, null, null, ..., context)`, a wildcard scan,
so calling it registers an observation of the *entire* named graph, not of the
triples the caller is adding or removing. Under `SERIALIZABLE`, that whole-graph
observation conflicts with a concurrent commit of *any* change to that graph —
including one that shares no triple with the transaction's own write.

Two transactions each adding a disjoint set of triples to the same named graph
should not conflict at all: neither reads anything the other wrote. Measured
against this repository's classes and RDF4J's `MemoryStore`, 20 rounds each,
they did anyway:

- disjoint `tx.add`, same named graph: 20 of 20 runs — one commit lost the race
- control, disjoint `tx.add` to two different named graphs: 0 of 20
- per-triple `hasStatement` lookup instead of `size()` (the fix below): 0 of 20

`size()` is also not O(1) here — `sizeInternal` streams and counts the whole
context — so the old code ran two whole-graph scans per `add`/`remove` call in
addition to over-observing. Tracked in
[issue #64](https://github.com/kogn-io/rdf-core/issues/64).

`DatasetTx#contains` (ADR-0008) already showed the way out for a different
problem: `RepositoryConnection#hasStatement` with a fully concrete
subject/predicate/object/context looks the pattern up directly rather than
evaluating a query or scanning a context, so it registers only that one
pattern as observed.

## Decision

`DatasetTxRdf4j#add` and `#remove` compute their delta per triple instead of
by sampling `size(context)`:

- `add` — for each input triple, `hasStatement(s, p, o, false, context)`; if
  absent, `connection.add(...)` and count it, otherwise skip it uncounted (it
  was already present, matching the existing idempotent-duplicate contract).
- `remove` — the mirror: if present, `connection.remove(...)` and count it,
  otherwise skip it.

Both reads are on the connection the transaction already holds, so read-your-
writes still applies: a duplicate triple appearing twice in the same input
graph is added/removed, and counted, once.

This narrows the conflict surface of `add`/`remove` to exactly the triples
they touch. A concurrent transaction that added or removed a *different*
triple in the same named graph no longer conflicts; a concurrent transaction
touching the *same* triple still does, because both transactions then read
(via `hasStatement`) and write the same pattern — which is the correct
SERIALIZABLE outcome, not a regression.

`count(IRI)`, `count()` and `export` are deliberately left as whole-graph
reads. They are not a variant of the same bug: a caller who asks "how many
triples/which triples are in this graph" and then writes based on that answer
needs the transaction to conflict with any concurrent writer to that graph —
`if (tx.count(g) < limit) tx.add(...)` must lose the race if a concurrent
commit changed the graph's contents, or the limit check is meaningless. That
is the same optimistic-concurrency-guard role `contains` plays for a single
pattern (ADR-0008), just scoped to the whole graph. The price a caller pays
for it: a transaction that only reads via `count`/`export` can also lose a
race against a concurrent writer that touched none of the triples the caller
cared about; a pure read with no later write in the same transaction belongs
on `SparqlQuery`/`GraphStore` directly, outside `inTransaction`, where no such
guard is needed.

The alternative — narrow `count`/`export` to a set of per-triple/per-pattern
reads the way `add`/`remove` now work — was rejected: `count`/`export` are
inherently whole-graph operations (there is no smaller pattern to observe that
would still answer "how many" or "which"), and narrowing their conflict
surface would silently turn them into a weaker guard than callers using them
as one currently rely on.

## Consequences

- `DatasetTxRdf4j#add`/`#remove` no longer call `RepositoryConnection#size`;
  each mutated triple costs one `hasStatement` lookup instead of two
  whole-graph scans per call, which is also a net reduction in work for graphs
  with more than a couple of triples.
- Two concurrent `inTransaction` calls adding or removing disjoint triples in
  the same named graph now both commit; before this change they conflicted on
  effectively every run (20 of 20 measured).
- `count`/`export`, called through a `DatasetTx`, keep observing the whole
  named graph and therefore keep acting as an optimistic-concurrency guard on
  it — this is unchanged behavior, documented more explicitly on
  `DatasetTxRdf4j`/`DatasetTransactorRdf4j` and in `ARCHITECTURE.md` as part of
  this decision, not a follow-up fix.
- `GraphStoreRdf4j#add`/`#remove` (used outside a transaction) are unchanged:
  they still sample `size(context)`, but inside their own dedicated
  `SNAPSHOT` sub-transaction (ADR-0011), which is about delta *exactness*
  against a concurrent writer, not about conflict detection — there is no
  surrounding `SERIALIZABLE` transaction for a wildcard read to over-observe.
