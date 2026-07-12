# ADR-0003: BindingSet lives in the dataset port layer, not in the data model

Status: Accepted

## Context

A SPARQL `SELECT` returns rows of variable-to-term bindings. `SparqlQuery.select`
and `DatasetTx.select` (both in `rdf-dataset`) return `Stream<BindingSet>`, so
`BindingSet` has to live somewhere both can reach.

Two placements were rejected:

- **In `rdf-terms`** — that would put a query-result type into the generic data
  model, which [ADR-0002](0002-terms-dependency-free-data-model.md) reserves for
  the RDF abstract syntax only.
- **In a higher layer above `rdf-dataset`** — the ports need it, so that would
  invert the dependency direction.

## Decision

`BindingSet` lives in `io.kogn.rdf.dataset`, the port/primitive layer. It is a
plain result type of one SPARQL `SELECT` row (variable names → `RDFTerm`
values), which is exactly where the query ports live — not in the generic data
model.

## Consequences

- Clean layering: `rdf-terms` ← `rdf-dataset`, no cycle, and the data model
  stays generic.
- `BindingSet` sits next to the ports that produce it.
