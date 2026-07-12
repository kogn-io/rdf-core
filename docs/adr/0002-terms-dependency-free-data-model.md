# ADR-0002: The terms module is a dependency-free, generic data model

Status: Accepted

## Context

`rdf-terms` is the RDF data model — term types, the graph family, the `RDF`
factory and standard-vocabulary constants. It is the foundation every other
module and every consumer builds on. A data model that drags in framework or
utility dependencies forces all of those consumers to inherit them, and pins
them to a version matrix they did not ask for.

There is also a scope temptation: query/serialization types, backend format
enums, or domain-specific vocabularies could drift into the data-model module
because they are "RDF-ish". They are not part of a generic data model and would
undermine its reusability.

## Decision

Keep `rdf-terms` **free of external dependencies** — not even annotation
libraries — and restricted to a **generic** Commons-RDF-oriented data model plus
standard vocabularies (RDF, RDFS, XSD, DCT, schema.org). Anything
backend-specific, query-specific or domain-specific belongs in a higher layer,
not here (see [ADR-0003](0003-bindingset-in-dataset-layer.md) for where query
result types go).

## Consequences

- Consumers can depend on the data model without inheriting any transitive
  library baggage.
- The module is publishable as a clean, standalone library.
- New types have to earn their place here: the "is this generic RDF?" test is
  the gate, and query/backend/domain types fail it.
