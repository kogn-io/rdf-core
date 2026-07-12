# ADR-0005: RDF4J backend for the dataset ports

Status: Accepted

## Context

The dataset ports in `rdf-dataset` (`GraphStore`, `SparqlQuery`, `SparqlUpdate`,
`DatasetTransactor`/`DatasetTx`, `DatasetLifecycle`) are interfaces only. They
need at least one real implementation to be usable, and that implementation must
not leak its backend's types — otherwise the whole point of the ports (a
swappable backend) is lost.

The implementation also has to make dataset lifecycle safe under concurrent use:
opening, evicting and deleting datasets must not race, and an opaque
`DatasetId` must never be trusted as a filesystem path.

## Decision

Provide `rdf-dataset-rdf4j`, an RDF4J-backed implementation of every port, in
its own module so consumers can depend on the ports without RDF4J and swap in a
different backend later.

- `DatasetLifecycleRdf4j` is the sole builder and owner of the RDF4J
  `Repository` (a `MemoryStore` for `IN_MEMORY`, a `NativeStore` for
  `PERSISTENT`, default index spec `spoc,posc,cosp`); the `Repository` is never
  exposed. Callers only ever see the neutral port types through a leased
  `DatasetHandle`.
- **In-flight protection:** each dataset carries a lease count maintained under a
  per-key lock. `acquire` increments the lease inside the same atomic
  create-or-find step that produces the handle; `close` and `delete` inspect the
  lease count under the same lock. A store therefore cannot be shut down or
  deleted while a handle is open — closing the time-of-check-to-time-of-use race
  a bare get-or-create + evict design would have.
- **Path safety:** the opaque `DatasetId` value is never used as a path
  directly; it is Base64url-encoded into a single directory segment, so values
  such as `"../etc"` cannot escape the storage root.

## Consequences

- `rdf-terms` + `rdf-dataset` + `rdf-dataset-rdf4j` form a complete, usable
  stack; the ports remain backend-swappable.
- No RDF4J type appears in any public signature.
- Store creation happens under the per-key lock. For the expected workload (few
  datasets, rare creation) holding the lock across initialisation is an
  acceptable trade for correctness.
