# ADR-0004: Converter-based cross-implementation interop in the RDF4J adapter

Status: Accepted

## Context

The RDF4J adapter carries its own term implementations (`RDF4JIRI`,
`RDF4JLiteral`, …) that wrap RDF4J values directly. But callers may hand the
adapter *foreign* term implementations — the `SimpleIRI` from `rdf-terms`, or a
third party's. If some operations accept only native RDF4J-backed terms and
others reject foreign ones, the abstraction boundary is inconsistent and the
adapter is not really a portability layer.

## Decision

Every adapter operation accepts foreign implementations, funnelled through
`RDF4JConverters`. A native term (`instanceof RDF4JTerm`) unwraps to its backing
RDF4J value directly; a foreign term is re-created from its lexical form (e.g.
`Values.iri(iri.getIRIString())`). No operation branches on "is this one of
ours?" to decide whether it will work.

## Consequences

- The port surface is a genuine portability layer: any conforming `rdf-terms`
  implementation works against the RDF4J backend.
- A small conversion cost for foreign terms (re-creation from their string
  form); native terms take the direct path.
