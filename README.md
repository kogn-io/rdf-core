# Kogn RDF

Technology-agnostic RDF data model, dataset ports and RDF4J backend.
Provides a clean, framework-free abstraction layer over RDF term types and
named-graph dataset operations, with an RDF4J implementation that can be swapped
for any other backend.

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

## Modules

| Directory | Maven artifact | Role |
|---|---|---|
| `cg-rdf-terms` | `io.kogn.rdf:rdf-terms` | RDF data model — technology-agnostic term interfaces, graph types and vocabulary constants. Zero external dependencies. |
| `cg-rdf-dataset` | `io.kogn.rdf:rdf-dataset` | Low-level dataset ports: `GraphStore`, `SparqlQuery` (read), `SparqlUpdate` (write), `DatasetTransactor`, `DatasetTx`, `DatasetLifecycle` (open-or-create/close/delete/list by opaque `DatasetId`, lease-protected). |
| `cg-rdf-dataset-rdf4j` | `io.kogn.rdf:rdf-dataset-rdf4j` | RDF4J backend implementing the dataset ports. No dependency on api/cid modules. |

## Build

```bash
./mvnw verify
```
