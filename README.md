# Kogn RDF

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Build](https://github.com/kogn-io/rdf-core/actions/workflows/snapshot.yml/badge.svg)](https://github.com/kogn-io/rdf-core/actions/workflows/snapshot.yml)

Technology-agnostic RDF data model, dataset ports and RDF4J backend.
Provides a clean, framework-free abstraction layer over RDF term types and
named-graph dataset operations, with an RDF4J implementation that can be swapped
for any other backend.

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

## Modules

| Directory | Maven artifact | Role |
|---|---|---|
| `rdf-terms` | `io.kogn.rdf:rdf-terms` | RDF data model — technology-agnostic term interfaces, graph types and vocabulary constants. Zero external dependencies. |
| `rdf-dataset` | `io.kogn.rdf:rdf-dataset` | Low-level dataset ports: `GraphStore`, `SparqlQuery` (read), `SparqlUpdate` (write), `DatasetTransactor`, `DatasetTx`, `DatasetLifecycle` (open-or-create/close/delete/list by opaque `DatasetId`, lease-protected; `acquire` returns a leased `DatasetHandle` — an access handle, not an RDF dataset). |
| `rdf-dataset-rdf4j` | `io.kogn.rdf:rdf-dataset-rdf4j` | RDF4J backend implementing the dataset ports. |

These modules were extracted from a larger RDF stack; the `io.kogn.*` group id
reflects that origin. The library itself is deliberately framework- and
application-agnostic, with no ties to any specific product.

## Build

Requires JDK 25; Maven itself comes from the pinned `./mvnw` wrapper.

```bash
./mvnw verify
```
