# ADR-0009: Split dataset hosting into its own module

Status: Accepted (implementation targets `0.2.0`)

## Context

[Issue #41](https://github.com/kogn-io/rdf-core/issues/41) asked whether
`DatasetLifecycle` belongs in this library at all. It, `DatasetHandle`,
`DatasetId` and `DatasetStoreConfig` currently live in `rdf-dataset` alongside
the four content ports (`GraphStore`, `SparqlQuery`, `SparqlUpdate`,
`DatasetTransactor`, `DatasetTx`).

What `DatasetLifecycle` actually provides is multi-tenant store *hosting*: an
id-to-store registry, lease-based in-flight protection, eviction, an on-create
seeding hook, a storage root, an index specification, on-disk deletion. None of
these are RDF concepts; they are the concerns of whatever process owns a pool
of stores.

The RDF4J adapter confirms the point structurally, not just conceptually:
`DatasetLifecycleRdf4j.createAndSeed()` always instantiates the backing store
itself (`new SailRepository(new MemoryStore())` for `IN_MEMORY`, `new
SailRepository(new NativeStore(dir, indexSpec))` for `PERSISTENT`). There is no
constructor path that accepts an existing `Repository`. For a store this
library does not host — a remote endpoint, a managed GraphDB, someone else's
`Repository` — `DatasetLifecycle` is not merely awkward to implement, it is
**unconstructible**: nothing in the type lets an adapter wrap a foreign store.

The other four ports do not have this problem: give `GraphStoreRdf4j` /
`SparqlQueryRdf4j` / `SparqlUpdateRdf4j` / `DatasetTransactorRdf4j` a
`Repository` from anywhere and they work.

The status quo also costs naming clarity. `DatasetHandle`'s javadoc has to
carry a "not an RDF 1.1 dataset" disclaimer and, to explain what it is,
compares itself to an RDF4J `RepositoryConnection` — a backend-specific analogy
leaking into a port module documented as backend-neutral. That friction is a
symptom of a hosting concept sharing both a module and a `Dataset*` vocabulary
with the pure content ports.

## Decision

Split `rdf-dataset` into two modules:

- **`rdf-dataset`** — the four content ports (`GraphStore`, `SparqlQuery`,
  `SparqlUpdate`, `DatasetTransactor`, `DatasetTx`) only. Nothing here presumes
  the library hosts the store.
- **`rdf-dataset-hosting`** — `DatasetLifecycle`, `DatasetHandle`, `DatasetId`,
  `DatasetStoreConfig`. Depends on `rdf-dataset` for the four port types
  `DatasetHandle` exposes.

`DatasetLifecycleRdf4j` and its supporting classes move out of
`rdf-dataset-rdf4j` into a matching hosting adapter module, mirroring the
`rdf-shacl` / `rdf-shacl-rdf4j` split already established for the SHACL port
(see [ADR-0007](0007-standalone-shacl-validation-port.md)). Exact module name
and package layout are decided at implementation time.

This is a breaking change — a package and module move for every consumer of
`DatasetLifecycle` — bundled into the still-unreleased `0.2.0` alongside the
other breaking changes already merged for that release (`DatasetTx#contains`,
`ConcurrencyConflictException`, `MalformedSparqlException`, the SHACL message
model), rather than deferred to a later line.

## Consequences

- A consumer wiring an adapter for a store this library does not host depends
  on `rdf-dataset` alone; the hosting vocabulary, and its RDF4J-flavored
  javadoc, are not even on the classpath. The naming friction issue #41 raised
  is closed structurally, not by a documentation disclaimer.
- `rdf-dataset-rdf4j` keeps only `GraphStoreRdf4j` / `SparqlQueryRdf4j` /
  `SparqlUpdateRdf4j` / `DatasetTransactorRdf4j` — store-agnostic wrappers over
  a caller-supplied `Repository`, consistent with how the SHACL adapter is
  scoped.
- Existing consumers of `DatasetLifecycle` add a dependency on
  `rdf-dataset-hosting` and update imports; this must be called out in the
  breaking-change release notes.
- This resolves issue #41 with option 2 of the three it proposed (own module),
  not option 1 (leave and document) or option 3 (move out of the repository
  entirely) — the concern is real and belongs at a module boundary, but there
  is currently exactly one consumer need (embedded/self-hosted stores), which
  does not justify moving it out of this repository.
