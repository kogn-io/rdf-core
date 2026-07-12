# ADR-0001: IRIFactory as a minimal IRI-creation interface

Status: Accepted

## Context

The `RDF` factory interface (`io.kogn.rdf.terms.RDF`) creates every kind of
term — IRIs, literals, blank nodes, triples, graphs. But some collaborators only
ever need to create IRIs: vocabulary-constant helpers and IRI normalization, for
example. Forcing them to depend on the full `RDF` factory means either providing
a complete implementation or stubbing most methods with
`UnsupportedOperationException`.

## Decision

Extract a dedicated `IRIFactory` interface with the single method
`createIRI(String)`. `RDF extends IRIFactory`, so full factories remain
drop-in. Collaborators that only mint IRIs (e.g. the vocabulary helpers under
`io.kogn.rdf.terms.vocab`) depend on `IRIFactory` alone. `SimpleIRI` is a
`record` implementing `IRI` directly.

## Consequences

- No more `UnsupportedOperationException` stubs for the IRI-only use case.
- The dependency direction is explicit: vocabulary code needs only IRI creation,
  not the whole term factory.
- One extra interface to keep in mind, but it is trivially small.
