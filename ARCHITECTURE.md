# Architecture

A lean, outward-facing overview of how Kogn RDF is put together. For the
reasoning behind individual decisions see the [Architecture Decision
Records](docs/adr/).

## What it is

Kogn RDF is a backend-agnostic RDF layer built on a pure data model, with three
independent port families above it — dataset access, SHACL validation and
content-addressed identifiers. The first two each have an RDF4J backend
implementing them; the third needs none. Nothing above the RDF4J modules is tied
to a particular store: the ports are the contract, RDF4J is one adapter behind
them.

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
    ├──────────  rdf-shacl    ◄──────  rdf-shacl-rdf4j
    │            validation port       RDF4J adapter
    │            (no rdf4j)           (wraps ShaclValidator)
    │
    └──────────  rdf-cid
                 content-addressing port
                 + its implementation
                 (no rdf4j, no adapter)
```

Dependencies point left only: `rdf-dataset` depends on `rdf-terms`;
`rdf-dataset-rdf4j` depends on both. Nothing depends on the content adapter —
callers program against the ports.

Hosting is a third, optional arm above the content ports. `rdf-dataset-hosting`
depends on `rdf-dataset` (for the five content-port types a handle exposes) and
`rdf-terms`; its RDF4J adapter `rdf-dataset-hosting-rdf4j` depends on
`rdf-dataset-hosting` **and** on `rdf-dataset-rdf4j` — it builds the backing
store and composes the content-adapter wrappers behind a leased handle. A
consumer that wires an adapter for a store this library does not host stays on
`rdf-dataset` alone and never sees the hosting vocabulary (ADR-0009).

The port families are siblings, not layers: `rdf-shacl` and `rdf-cid` each
depend on `rdf-terms` alone and know nothing about datasets, so validation and
content addressing are usable without a store and a store is usable without
either. Wiring validation into the dataset write path is not done here today;
ADR-0007 explains why validation stands alone, and issue #2 tracks whether an
optional write-path variant should join it. `rdf-cid` differs from the other two
families in one respect: it has no adapter, because its algorithm is arithmetic
over the data model rather than a call into a store (ADR-0014).

| Module | Artifact | Role |
|---|---|---|
| `rdf-terms` | `io.kogn.rdf:rdf-terms` | The RDF data model: term interfaces (`IRI`, `BlankNode`, `Literal`, `RDFTerm`), the graph family (`Triple`, `ReadableGraph`, `Graph`, `NamedGraph`, `RDFList`), the `RDF` factory and standard-vocabulary constants. Deliberately dependency-free. |
| `rdf-dataset` | `io.kogn.rdf:rdf-dataset` | Technology-neutral dataset content ports: `GraphStore`, `SparqlQuery`, `SparqlUpdate`, `DatasetExport` (with `RdfFormat`), `DatasetTransactor`/`DatasetTx` (and `BindingSet`). Interfaces only — no backend, and no presumption that the library hosts the store. |
| `rdf-dataset-rdf4j` | `io.kogn.rdf:rdf-dataset-rdf4j` | RDF4J implementation of the content ports — store-agnostic wrappers over a caller-supplied `Repository`. RDF4J types never appear in public signatures. |
| `rdf-dataset-hosting` | `io.kogn.rdf:rdf-dataset-hosting` | Multi-tenant dataset hosting port: `DatasetLifecycle` with `DatasetHandle`, `DatasetId`, `DatasetStoreConfig`. Depends on `rdf-dataset` for the content-port types a handle exposes. Interfaces only — no backend. |
| `rdf-dataset-hosting-rdf4j` | `io.kogn.rdf:rdf-dataset-hosting-rdf4j` | RDF4J implementation of the hosting port. Builds and owns `MemoryStore`/`NativeStore` repositories and composes the `rdf-dataset-rdf4j` wrappers behind leased handles. |
| `rdf-shacl` | `io.kogn.rdf:rdf-shacl` | Technology-neutral SHACL validation port: `ShaclValidation.validate(data, shapes, options)` over `ReadableGraph`, returning `ShaclReport`/`ShaclResult`/`ShaclMessage`/`Severity` plus `ValidationOptions`. Interfaces and value objects only — no backend, and no dependency on the dataset ports. |
| `rdf-shacl-rdf4j` | `io.kogn.rdf:rdf-shacl-rdf4j` | RDF4J implementation of the SHACL port, wrapping `ShaclValidator`. Store-independent: it does not depend on `rdf-dataset` or its adapter. |
| `rdf-cid` | `io.kogn.rdf:rdf-cid` | Content-addressed IRI generation port: `ContentAddressedIriGenerator.generateIri(graph)` over `ReadableGraph`, returning a deterministic `urn:cid:` derived from the graph's triples — every term in full, datatype and language tag included — for a graph describing exactly one IRI subject. Unlike the other port families it carries its own implementation, `ContentAddressedIriGeneratorSexpr` — there is no backend to swap. No dependency on the dataset ports. |

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
- **`DatasetExport`** — serialization to a byte stream: the whole dataset (only
  in a quad-capable `RdfFormat` — TriG or N-Quads — since a triple-only format
  would flatten the named graphs and silently lose which statement came from
  where) or a single named graph (any format). The counterpart to
  `GraphStore#export(IRI)`, which despite its name returns an in-memory graph
  rather than a document. The stream stays the caller's: it is written to and
  flushed, never closed, and a failure to write surfaces as the neutral
  `RdfExportException`. Deliberately *not* part of `DatasetTx`
  ([ADR-0013](docs/adr/0013-standalone-dataset-export-port.md)): a dump streams
  at the pace of the caller's sink and would hold a transaction open for that
  span while observing the whole store, and a dump cannot act as the
  optimistic-concurrency guard that price buys. For a snapshot atomic with other
  work, take `DatasetTx#export(IRI)` inside the transaction and serialize it
  afterwards.
- **`DatasetTransactor`** / **`DatasetTx`** — an atomic, all-or-nothing
  unit-of-work (`inTransaction(work)`; roll back on any `RuntimeException` or
  `Error` the work throws — the adapter catches `Throwable` itself rather than
  relying on the backend connection rolling back at close time). `DatasetTx`
  composes `GraphStore`, `SparqlQuery` and `SparqlUpdate`
  ([ADR-0011](docs/adr/0011-datasettx-composes-content-ports.md)): every
  operation those three ports declare, called through a `DatasetTx`,
  participates in the same transaction instead of each being its own implicit,
  single-operation transaction the way it is when one of those ports is used
  directly. `DatasetTx` adds one operation of its own, `contains(graph, s, p,
  o)` with `null` as wildcard: a guard read stated as a statement pattern
  rather than as a query, so a backend can answer it from its own pattern
  lookup instead of through query evaluation — the longer path, and the one on
  which a backend can lose the conflict. Optimistic-concurrency guards belong
  here, not in `ask` — see the "Limits" notes on `DatasetTransactorRdf4j`. The
  loser of such a race fails at commit with the port's neutral
  `ConcurrencyConflictException`, so a caller can catch and retry without
  naming a backend exception type. Not every operation observes the same
  amount of the graph, though: `add`/`remove` conflict only on the individual
  triples they touch (each is checked and mutated one triple at a time via a
  pattern lookup), while `contains`/`count`/`export`/`ask`/`select` conflict on
  whatever they read — up to the whole named graph for a wildcard `count` or
  `export` — because a caller that already read that much is meant to lose the
  race against a concurrent writer to it, the same way a `contains` guard is
  meant to. `add`/`remove` used to fall into the whole-graph case too, sampling
  the graph's size before and after the write; that made two transactions
  adding disjoint triples to the same named graph conflict almost every time,
  a false conflict rather than a real one, since neither transaction read
  anything the other wrote ([ADR-0012](docs/adr/0012-per-triple-conflict-surface-for-add-remove.md)).

Every query and update method on `SparqlQuery`, `SparqlUpdate` and `DatasetTx`
also has a `Map<String, RDFTerm>` bindings overload
([ADR-0010](docs/adr/0010-sparql-parameter-binding.md)): each entry substitutes
its value for the same-named `?variable` before the operation runs, so a caller
assembling a query around an external value binds it instead of concatenating
it into the SPARQL string — closing a SPARQL-injection and escaping gap the
`String`-only methods left open. The `String`-only overloads are unchanged and
remain the right choice when there is nothing to bind.

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
  `DatasetExportRdf4j` / `DatasetTransactorRdf4j` (with the package-private
  `DatasetTxRdf4j`) each wrap a *caller-supplied* `Repository` and are
  store-agnostic — hand them a `Repository` from anywhere and they work. They do
  not build a store; assembling one is the hosting adapter's job (below).
- `DatasetExportRdf4j` streams: `RepositoryConnection#export` feeds a `Rio`
  writer that writes straight to the caller's stream, so no intermediate `Model`
  is built and a dataset larger than memory can still be dumped. The four writer
  modules that serve `RdfFormat` are runtime dependencies, resolved through
  `Rio`'s `ServiceLoader` registry.
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
- **A handle exposes all five content ports, `DatasetExport` among them.** Since
  the lifecycle never hands out its `Repository`, the accessor is the only way a
  hosted dataset can be serialized at all — which is why it was added even though
  a new abstract method on `DatasetHandle` breaks every implementation of that
  interface ([ADR-0013](docs/adr/0013-standalone-dataset-export-port.md) records
  the port design and named this gap while it was still open). The guard against
  a closed handle is checked when the export call starts, so closing a handle
  does not abort a dump already streaming — it only drops the lease that was
  keeping the store from being evicted underneath it.
- **In-flight protection is per-dataset lease counting under a per-key lock**,
  closing the time-of-check-to-time-of-use race between acquisition and
  eviction/deletion. Enforcement reaches the accessors too: each of
  `graphStore()`/`sparqlQuery()`/`sparqlUpdate()`/`datasetExport()`/`transactor()`
  returns a thin,
  per-handle wrapper that throws `IllegalStateException` once *that* handle is
  closed, while the underlying shared instance keeps working for any other open
  handle on the same dataset. `shutDownAll()` is the deliberate exception — a
  last-resort teardown that does not consult lease counts, logging a warning
  naming any dataset still leased before tearing everything down regardless.
- **`close` is eviction only for a `PERSISTENT` store; for `IN_MEMORY` it
  destroys.** The resume half of eviction — drop the store, re-`acquire` later,
  find the data — needs storage to resume *from*, and an `IN_MEMORY` dataset has
  none: closing it discards its contents, and the next `acquire` builds an empty
  store and re-runs the on-create hook, the same outcome as `delete`. The
  "one-time" in the hook's contract is therefore scoped to the store, not to the
  `DatasetId`. This matters because the port invites a consumer to write one
  generic idle/TTL policy against it: applied uniformly, that policy resumes
  cheaply against `PERSISTENT` and silently wipes `IN_MEMORY`. Documented rather
  than prevented — refusing or no-op'ing the call would break the callers relying
  on today's behaviour, and there is no third outcome to offer.
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
- **RDF4J's proprietary SHACL extensions are switched off**, so `ValidationOptions`
  alone decides reasoning behaviour and no `http://rdf4j.org/shacl-extensions#`
  predicate in a shapes graph takes effect. This covers `rdf4j-ext:targetShape`
  too: a shape targeting only through it never fires and the report comes back
  conforming — target with standard SHACL
  ([ADR-0007](docs/adr/0007-standalone-shacl-validation-port.md)).
- **A run that yields no report at all fails with the port's neutral
  `ShaclValidationException`** — an unparsable shapes graph, a construct the
  backend does not support, a term it rejects. It is the counterpart to the
  dataset ports' `ConcurrencyConflictException`: the caller handles a broken
  input without naming a backend exception type, with the backend's own signal
  kept as `cause`. A *non-conforming* data graph is not this — that is a normal
  run reporting `conforms() == false`.

`rdf-shacl-rdf4j` wraps RDF4J's `ShaclValidator` and loads both graphs into
transient in-memory sails per call. It depends on `rdf-terms` and `rdf-shacl`
only — never on the dataset modules — so validation and storage stay separable.

## Content-addressed identifiers (`rdf-cid`)

`ContentAddressedIriGenerator.generateIri(graph)` derives a deterministic
`urn:cid:` from a graph's triples, so the same content always mints the same
identifier and re-importing a dataset is detectable without keeping a ledger of
what was imported before
([ADR-0014](docs/adr/0014-content-addressed-iri-module.md)). The derivation
canonicalizes the graph with URDNA2015, skolemizes its blank nodes to
deterministic IRIs keyed off the canonical label URDNA2015 already assigns them,
serializes the result into a sorted, length-prefixed S-expression and hashes it
with Blake2b-256.

Two constraints callers need to know before persisting the result anywhere:

- **The graph must describe exactly one IRI subject**, plus whatever blank
  nodes hang off it. Zero, several, or a triple reachable from none of them all
  raise `IllegalArgumentException` rather than silently addressing a partial
  graph — a wrong answer is worse than no answer here, because deduplication and
  integrity checks both read "same identifier" as "same content".
- **Every term goes into the digest in full** — an IRI by its IRI string, a
  literal by lexical form, datatype IRI *and* language tag, the subject IRI
  itself included. The identifier is therefore independent of blank node labels
  and triple order, but not of the IRIs the data uses; two graphs describing the
  same thing under different subject IRIs are different content.

Unlike the other two port families, `rdf-cid` carries its own and only
implementation, `ContentAddressedIriGeneratorSexpr` — there is no backend to
swap, because the algorithm is arithmetic over `rdf-terms` values rather than a
call into a store. It depends on `rdf-terms` alone among our modules and on no
RDF4J artifact (`CidPortHasNoBackendDependencyTest` pins that the same way
`rdf-shacl` does), but pulls the heaviest third-party dependency set in this
repository to get there: `io.setl:rdf-urdna` for URDNA2015, BouncyCastle for
Blake2b-256, Commons Codec for Base32, and Titanium JSON-LD as `rdf-urdna`'s own
dependency for its RDF dataset model.

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
