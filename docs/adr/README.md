# Architecture Decision Records

Short records of the design decisions behind Kogn RDF, in
[MADR](https://adr.github.io/madr/)-style. Each file captures one decision: its
context, the choice made, and the consequences.

These are numbered fresh from 1; they document the current OSS codebase and are
not a historical log of every change that led here.

| ADR | Title |
|---|---|
| [0001](0001-irifactory-minimal-interface.md) | IRIFactory as a minimal IRI-creation interface |
| [0002](0002-terms-dependency-free-data-model.md) | The terms module is a dependency-free data model |
| [0003](0003-bindingset-in-dataset-layer.md) | BindingSet lives in the dataset port layer |
| [0004](0004-converter-based-interop.md) | Converter-based cross-implementation interop |
| [0005](0005-rdf4j-backend-for-dataset-ports.md) | RDF4J backend for the dataset ports |
| [0006](0006-revision-versioning-and-release.md) | CI-friendly versioning with `${revision}` and manually triggered releases |
| [0007](0007-standalone-shacl-validation-port.md) | Standalone, non-transactional SHACL validation port |
| [0008](0008-datasettx-contains-guard.md) | `DatasetTx#contains` as the conflict-protected guard read |
| [0009](0009-dataset-hosting-module-split.md) | Split dataset hosting into its own module |
