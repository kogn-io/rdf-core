# ADR-0021: Versioning of RDF resources as its own backend-free module over `DatasetTx`

Status: Proposed

## Context

Issue [#105](https://github.com/kogn-io/rdf-core/issues/105) adds versioning of
RDF resources: a mutable draft per proxy IRI, immutable versions named by their
CID, and a history of publishes. The design is agreed in the
[design note](../design/105-versioning.md) (D1–D13); this record takes its
module cut (D13). "Versioning" here means versioning of RDF resources held in a
dataset, not the Maven versioning of this project's artifacts, which is
[ADR-0006](0006-revision-versioning-and-release.md).

Everything versioning needs from a store — adding, removing and exporting per
named graph, SPARQL, the guard read `contains` — is on `DatasetTx` already
([ADR-0011](0011-datasettx-composes-content-ports.md),
[ADR-0008](0008-datasettx-contains-guard.md)). Deriving a version's name is
`ContentAddressedIriGenerator#generateIri` in `rdf-cid`
([ADR-0017](0017-content-addressed-iri-takes-the-base.md)). Versioning is
therefore plain logic over existing ports and needs no backend of its own.

A `DatasetTx` does not have to come from the hosting layer: a caller can wrap a
repository of its own with `rdf-dataset-rdf4j`. Tying versioning to the hosting
layer — to `DatasetStoreConfig`, say, as a switch per hosted dataset — would
shut those callers out.

## Decision

Versioning of RDF resources is a new module, `rdf-versioning`, that depends on
`rdf-dataset` and `rdf-cid` only and runs on any `DatasetTx`, including one over
a repository the caller supplies. It has no dependency on `rdf-dataset-hosting`
or on RDF4J. Its port and the port's only implementation share the module.

## Consequences

- Versioning works the same whether the caller hosts its datasets through
  `rdf-dataset-hosting` or brings its own repository; nothing in the hosting
  port changes for it.
- `rdf-versioning` consumes `DatasetTx` (ADR-0011) and
  `ContentAddressedIriGenerator` (ADR-0017). The guards it needs read through
  that `DatasetTx`; how they stay conflict-protected is part of the port
  contract ([ADR-0022](0022-draft-publish-versioning-per-proxy-iri.md)).
- Port and implementation in one module follow `rdf-cid` and `rdf-shacl`.
- The module table in `CLAUDE.md` and the README gain the module when it is
  built, not with this record.
- What the port promises is
  [ADR-0022](0022-draft-publish-versioning-per-proxy-iri.md); how a versioned
  dataset keeps its data is
  [ADR-0023](0023-versioned-dataset-fixed-named-graphs.md).
