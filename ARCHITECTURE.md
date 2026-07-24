# Architecture

A lean, outward-facing overview of how Kogn RDF is put together. For the
reasoning behind individual decisions see the [Architecture Decision
Records](docs/adr/).

## What it is

Kogn RDF is a backend-agnostic RDF layer built on a pure data model, with two
independent port families above it — dataset access and SHACL validation — each
with an RDF4J backend implementing it. Nothing above the RDF4J modules is tied to
a particular store: the ports are the contract, RDF4J is one adapter behind them.

## Building blocks

```
rdf-terms  ◄──────  rdf-dataset  ◄──────────  rdf-dataset-rdf4j
data model          content ports             content adapter
(no dependencies)   (contracts)               (wraps a Repository)
    ▲                    ▲                          ▲
    │                    │                          │
    │            rdf-dataset-hosting  ◄──  rdf-dataset-hosting-rdf4j
    │            hosting port               hosting adapter
    │            (registry, leases)         (builds the store,
    │                                        composes the wrappers)
    │
    └──────────  rdf-shacl    ◄──────  rdf-shacl-rdf4j
                 validation port       RDF4J adapter
                 (no rdf4j)           (wraps ShaclValidator)
```

Dependencies point left only: `rdf-dataset` depends on `rdf-terms`;
`rdf-dataset-rdf4j` depends on both. Nothing depends on the content adapter —
callers program against the ports.

Hosting is a third, optional arm above the content ports. `rdf-dataset-hosting`
depends on `rdf-dataset` (for the four content-port types a handle exposes) and
`rdf-terms`; its RDF4J adapter `rdf-dataset-hosting-rdf4j` depends on
`rdf-dataset-hosting` **and** on `rdf-dataset-rdf4j` — it builds the backing
store and composes the content-adapter wrappers behind a leased handle. A
consumer that wires an adapter for a store this library does not host stays on
`rdf-dataset` alone and never sees the hosting vocabulary (ADR-0009).

The port families are siblings, not layers: `rdf-shacl` depends on
`rdf-terms` alone and knows nothing about datasets, so validation is usable
without a store and a store is usable without validation. Wiring the two
together — validating on the dataset write path — is not done here today; ADR-0007
explains why validation stands alone, and issue #2 tracks whether an optional
write-path variant should join it.

| Module | Artifact | Role |
|---|---|---|
| `rdf-terms` | `io.kogn.rdf:rdf-terms` | The RDF data model: term interfaces (`IRI`, `BlankNode`, `Literal`, `RDFTerm`), the graph family (`Triple`, `ReadableGraph`, `Graph`, `NamedGraph`, `RDFList`), the `RDF` factory and standard-vocabulary constants. Deliberately dependency-free. |
| `rdf-dataset` | `io.kogn.rdf:rdf-dataset` | Technology-neutral dataset content ports: `GraphStore`, `SparqlQuery`, `SparqlUpdate`, `DatasetTransactor`/`DatasetTx` (and `BindingSet`). Interfaces only — no backend, and no presumption that the library hosts the store. |
| `rdf-dataset-rdf4j` | `io.kogn.rdf:rdf-dataset-rdf4j` | RDF4J implementation of the content ports — store-agnostic wrappers over a caller-supplied `Repository`. RDF4J types never appear in public signatures. |
| `rdf-dataset-hosting` | `io.kogn.rdf:rdf-dataset-hosting` | Multi-tenant dataset hosting port: `DatasetLifecycle` with `DatasetHandle`, `DatasetId`, `DatasetStoreConfig`. Depends on `rdf-dataset` for the content-port types a handle exposes. Interfaces only — no backend. |
| `rdf-dataset-hosting-rdf4j` | `io.kogn.rdf:rdf-dataset-hosting-rdf4j` | RDF4J implementation of the hosting port. Builds and owns `MemoryStore`/`NativeStore` repositories and composes the `rdf-dataset-rdf4j` wrappers behind leased handles. |
| `rdf-shacl` | `io.kogn.rdf:rdf-shacl` | Technology-neutral SHACL validation port: `ShaclValidation.validate(data, shapes, options)` over `ReadableGraph`, returning `ShaclReport`/`ShaclResult`/`ShaclMessage`/`Severity` plus `ValidationOptions`. Interfaces and value objects only — no backend, and no dependency on the dataset ports. |
| `rdf-shacl-rdf4j` | `io.kogn.rdf:rdf-shacl-rdf4j` | RDF4J implementation of the SHACL port, wrapping `ShaclValidator`. Store-independent: it does not depend on `rdf-dataset` or its adapter. |

(Directory name = artifact id; the Java packages are `io.kogn.rdf.*`.)

## The data model (`rdf-terms`)

A pure-Java representation of the RDF abstract syntax, modelled after the
[Apache Commons RDF](https://commons.apache.org/proper/commons-rdf/) API but
without depending on it or any framework. Terms are value types
(`record`-based); the `RDF` interface is the factory entry point and extends the
minimal `IRIFactory` so callers that only mint IRIs need nothing more
([ADR-0001](docs/adr/0001-irifactory-minimal-interface.md)). Keeping this module
free of dependencies is a deliberate design goal
([ADR-0002](docs/adr/0002-terms-dependency-free-data-model.md)).

## The dataset ports (`rdf-dataset`)

Small, single-purpose ports that cover what a dataset consumer needs, split by
concern:

- **`GraphStore`** — named-graph-addressed `add`/`remove`/`clear`/`export`/`count`.
- **`SparqlQuery`** — non-transactional `SELECT`/`CONSTRUCT`/`ASK`
  (`DESCRIBE` is not supported).
- **`SparqlUpdate`** — SPARQL 1.1 Update.
- **`DatasetTransactor`** / **`DatasetTx`** — an atomic, all-or-nothing
  unit-of-work (`inTransaction(work)`; roll back on any exception). Besides the
  SPARQL operations, `DatasetTx` carries `contains(graph, s, p, o)` with `null`
  as wildcard: a guard read stated as a statement pattern rather than as a query,
  so a backend can answer it from its own pattern lookup instead of through query
  evaluation — the longer path, and the one on which a backend can lose the
  conflict. Optimistic-concurrency guards belong here, not in `ask` — see the
  "Limits" notes on `DatasetTransactorRdf4j`. The loser of such a race fails at
  commit with the port's neutral `ConcurrencyConflictException`, so a caller can
  catch and retry without naming a backend exception type.

Hosting a pool of datasets — open-or-create / close / delete / list, addressed
by an opaque id — is deliberately not one of these ports; it is a separate
concern in `rdf-dataset-hosting` (see below).

`SELECT` results are rows of `BindingSet`, which lives in this module rather than
in the data model
([ADR-0003](docs/adr/0003-bindingset-in-dataset-layer.md)).

### Named graphs only — not an RDF 1.1 dataset

A "dataset" here is a store of *named graphs*, nothing more. It is intentionally
**not** an [RDF 1.1 dataset](https://www.w3.org/TR/rdf11-concepts/#section-dataset):
there is no unnamed default graph, graph names are always IRIs (never blank
nodes), and a context-less SPARQL read ranges over the *union* of all named
graphs rather than a default graph. The default graph is left out until a
consumer needs it.

## The RDF4J adapter (`rdf-dataset-rdf4j`)

Implements the content ports on top of an RDF4J `Repository`, without leaking
RDF4J types to callers ([ADR-0005](docs/adr/0005-rdf4j-backend-for-dataset-ports.md)):

- `GraphStoreRdf4j` / `SparqlQueryRdf4j` / `SparqlUpdateRdf4j` /
  `DatasetTransactorRdf4j` (with the package-private `DatasetTxRdf4j`) each wrap
  a *caller-supplied* `Repository` and are store-agnostic — hand them a
  `Repository` from anywhere and they work. They do not build a store; assembling
  one is the hosting adapter's job (below).
- Foreign term implementations are accepted throughout via `RDF4JConverters`,
  so the adapter is a genuine portability layer rather than an RDF4J-only island
  ([ADR-0004](docs/adr/0004-converter-based-interop.md)).

## Dataset hosting (`rdf-dataset-hosting`, `rdf-dataset-hosting-rdf4j`)

Owning a *pool* of stores is a separate concern from reading and writing one:
`rdf-dataset-hosting` is the port for it and `rdf-dataset-hosting-rdf4j` its
RDF4J backend ([ADR-0009](docs/adr/0009-dataset-hosting-module-split.md)). The
port is deliberately kept off the content-port path, because hosting — a
registry, leasing, eviction, seeding, a storage root, on-disk deletion — is a
concern of whatever process owns the stores, not an RDF concept, and is
structurally unconstructible for a store this library does not host.

`DatasetLifecycle` is pure mechanism: a dataset is obtained through
`acquire(DatasetId)`, which returns a leased `DatasetHandle`; while any lease is
open the backing store cannot be evicted (`close`) or deleted (`delete`). Any
idle/TTL *policy* lives with the consumer. `DatasetStoreConfig` carries only
backend-neutral knobs (persistence, full-text-search requirement); engine
specifics never enter the port layer. For that reason `DatasetHandle` is named
"handle", not "dataset": it is a short-lived, leased session onto the store —
not a value-typed RDF dataset — that exposes the content ports and must be closed
to release its lease.

Settled semantics worth knowing before consuming it:

- **A persistent lifecycle owns its storage location exclusively.** It caches
  each dataset's store and holds it open, and the backing engine locks the
  directory. Two lifecycles over the same storage root do not share the physical
  store — the second one fails on the lock. Construct one lifecycle per storage
  location and share it across every logical repository that reads or writes
  there; that is also what makes cross-repository reads within a dataset possible
  at all. The lock is process-held, so a second JVM over the same directory fails
  the same way — no in-process arrangement avoids it.
- **`DatasetLifecycleRdf4j` is the sole builder and owner of the `Repository`**
  (a `MemoryStore` for `IN_MEMORY`, a `NativeStore` for `PERSISTENT`); it is
  never handed out. The content ports behind each handle are the `rdf-dataset-rdf4j`
  wrappers, composed over that `Repository`.
- **In-flight protection is per-dataset lease counting under a per-key lock**,
  closing the time-of-check-to-time-of-use race between acquisition and
  eviction/deletion. Enforcement reaches the accessors too: each of
  `graphStore()`/`sparqlQuery()`/`sparqlUpdate()`/`transactor()` returns a thin,
  per-handle wrapper that throws `IllegalStateException` once *that* handle is
  closed, while the underlying shared instance keeps working for any other open
  handle on the same dataset. `shutDownAll()` is the deliberate exception — a
  last-resort teardown that does not consult lease counts, logging a warning
  naming any dataset still leased before tearing everything down regardless.
- **The opaque `DatasetId` is Base64url-encoded into a single directory
  segment**, so values like `"../etc"` cannot escape the storage root.

## SHACL validation (`rdf-shacl`, `rdf-shacl-rdf4j`)

A standalone validation port: `ShaclValidation.validate(data, shapes, options)`
takes two `ReadableGraph`s and answers with a `ShaclReport`
([ADR-0007](docs/adr/0007-standalone-shacl-validation-port.md)). It is
non-transactional and stateless — it validates a *candidate* graph before a
write rather than constraining a store, which suits the single-writer case where
one adapter owns every write. A transactional variant (RDF4J's `ShaclSail` on the
write path) is a different tool for shared stores and is not what this is.

Settled semantics worth knowing before consuming it:

- **Conformance is recomputed, not taken from RDF4J.** Only `Severity.VIOLATION`
  makes a report non-conforming; `sh:Warning` and `sh:Info` results are carried
  in `results()` but never flip `conforms`. RDF4J's own report treats *any*
  result as non-conforming, so the adapter deliberately diverges from it.
- **Messages are handed over whole.** `ShaclResult.messages()` carries every
  `sh:resultMessage` as a `ShaclMessage(text, language)` with its tag intact,
  and the port picks none of them: a language fallback chain is a deployment
  decision and belongs where that context exists. The list order is a parse-order
  artifact of the shapes graph and carries no meaning — select by tag, not by
  position. Tags are lower-cased so that selection actually works.
- **RDFS subclass reasoning is opt-in** (`ValidationOptions.rdfsSubClassReasoning`,
  default off), and a silent no-op if no `rdfs:subClassOf` axioms are present in
  either graph.

`rdf-shacl-rdf4j` wraps RDF4J's `ShaclValidator` and loads both graphs into
transient in-memory sails per call. It depends on `rdf-terms` and `rdf-shacl`
only — never on the dataset modules — so validation and storage stay separable.

## Build & release

Java 25, built with the pinned Maven wrapper (`./mvnw`). The version is a single
`${revision}` property; a release is a manually triggered CI workflow on `main`,
which tags the commit `vX.Y.Z` only after a successful upload — pushing a tag by
hand triggers nothing ([ADR-0006](docs/adr/0006-revision-versioning-and-release.md)).
Deployment runs exclusively through CI, never locally. See the [README](README.md)
and [CONTRIBUTING](CONTRIBUTING.md) for the day-to-day commands.

## Non-goals

- No default graph / full RDF 1.1 dataset semantics (see above).
- No SPARQL `DESCRIBE`.
- No object mapping, and no general reasoning or inference layer — this is a
  data-model and store-access abstraction, not a framework. The one exception is
  narrow and opt-in: `ValidationOptions.rdfsSubClassReasoning` resolves
  `rdfs:subClassOf` for the duration of a validation call, so a shape may target
  a superclass. It infers nothing beyond that and nothing is materialised.
