# ADR-0013: `DatasetExport` is a standalone, non-transactional port

Status: Accepted

## Context

The dataset ports had no way to turn a dataset into bytes.
`GraphStore#export(IRI)` reads like one but serializes nothing: it returns an
in-memory `ReadableGraph` snapshot of a single named graph. Writing a dump —
Turtle, TriG, N-Triples, N-Quads — to a file, a socket or an HTTP response was
only reachable by dropping down to the backend's own `Repository`, which is
exactly what these ports exist to avoid. Tracked in
[issue #89](https://github.com/kogn-io/rdf-core/issues/89).

Two questions had to be settled with it. Where does serialization sit relative
to the transactional unit of work (ADR-0011 folded `GraphStore`, `SparqlQuery`
and `SparqlUpdate` into `DatasetTx` precisely because leaving `export`/`count`
out of it was a capability hole)? And does the whole-dataset overload's
"quad-capable formats only" rule get enforced once or once per backend?

## Decision

A new port `DatasetExport` in `rdf-dataset`, a sibling of `SparqlQuery` and
`SparqlUpdate`, with a neutral `RdfFormat` enum and an `RdfExportException`
following `MalformedSparqlException`'s neutral-wrapper pattern:

```java
void export(OutputStream out, RdfFormat format);                  // whole dataset, quad-capable formats only
void export(OutputStream out, RdfFormat format, IRI namedGraph);  // one named graph, any format
```

**It is not composed into `DatasetTx`, unlike the three ports ADR-0011 folded
in.** Those three were folded in because a unit of work that could not count or
export a graph without leaving the transaction had a real hole. Export is a
different shape of operation:

- Its duration is set by the *caller's sink*, not by the store. A dump streams
  for as long as a file, socket or response body takes to swallow it. Inside
  `inTransaction`, that would hold a `SERIALIZABLE` transaction — and the
  backend connection behind it — open for an unbounded, caller-paced span.
- Its conflict surface is the *entire store*. Under `SERIALIZABLE` every
  statement pattern read is an observation (ADR-0012), so a whole-dataset export
  inside a transaction would conflict with any concurrent commit anywhere. And
  unlike `count`/`export(IRI)`, which ADR-0012 deliberately keeps whole-graph
  *because callers use them as guards*, a serialized dump cannot serve as a
  guard at all: its result goes to a stream, not into a decision the same
  transaction then acts on. It would pay the full guard price for no guard.
- The transactional need it might serve is already covered. A caller who needs
  a snapshot that is atomic with its other work takes it inside the transaction
  with `DatasetTx#export(IRI)` and serializes the returned graph afterwards.

This follows [ADR-0007](0007-standalone-shacl-validation-port.md)'s line: a
read-only capability that is not part of the write path stays a standalone,
non-transactional port.

**Quad-capability is enforced on `RdfFormat`, not per backend.** The rule that
the whole-dataset overload rejects a triple-only format cannot be expressed in
the type system, so it needs a runtime check. It lives as
`RdfFormat#requireQuadCapable()` next to the `isQuadCapable()` predicate — on
the value being validated, in the port module — so every backend rejects the
same inputs with the same message, and the rule is unit-tested without any
backend present.

**`rdf-dataset-hosting`'s `DatasetHandle` does not expose the new port yet.**
Adding an abstract method to a published interface is a source- and
binary-breaking change for anyone implementing it, and issue #89 is explicitly
scoped as purely additive. The consequence is named below rather than papered
over with a `default` method that throws.

## Consequences

- Consumers holding a backend `Repository` can dump a dataset or a single named
  graph through neutral types; `rdf-dataset-rdf4j` streams it via
  `RepositoryConnection#export` into a `Rio` writer — no SPARQL, no intermediate
  `Model`, so a dataset larger than memory can still be exported.
- The adapter gains runtime dependencies on RDF4J's four writer modules
  (`rdf4j-rio-turtle`/`-ntriples`/`-trig`/`-nquads`), resolved by `Rio` through
  its `ServiceLoader` registry. Adding a format to `RdfFormat` therefore means
  adding a writer dependency, and the exhaustive `switch` in
  `DatasetExportRdf4j` fails to compile until the mapping is supplied.
- A whole-dataset export is **not** guaranteed atomic against concurrent
  writers as a matter of port contract; each implementation gets whatever its
  store gives a single read (`SNAPSHOT_READ` for the `MemoryStore` /
  `NativeStore` configurations shipped here). A transactionally consistent
  *whole-dataset* dump is not offered by any port. If a consumer ever needs one,
  `DatasetTx` can inherit `DatasetExport` later — that is an additive change on
  the port and a few lines in `DatasetTxRdf4j`, so nothing here forecloses it.
- **A hosted dataset cannot be exported through its `DatasetHandle`.**
  `DatasetLifecycleRdf4j` never hands out its `Repository`, so for hosting
  consumers the capability is currently unreachable. That gap is real and stays
  open until `DatasetHandle#datasetExport()` is added in a release window that
  accepts a breaking interface change.
- Export reflects asserted statements only (`includeInferred=false`), matching
  `GraphStore#export(IRI)`; a reasoning-capable Sail's derived statements would
  not appear in a dump. No store shipped here infers, so the two views coincide
  today.
