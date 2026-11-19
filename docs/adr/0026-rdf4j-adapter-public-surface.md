# ADR-0026: The RDF4J adapters keep RDF4J types out of their public surface; corrects ADR-0005

Status: Accepted (2026-11-19) — corrects the Consequences of
[ADR-0005](0005-rdf4j-backend-for-dataset-ports.md)

## Context

ADR-0005 states in its Consequences: "No RDF4J type appears in any public
signature." Two things were wrong with that line.

- It was not true of the code. The term adapters in `io.kogn.rdf.rdf4j`
  (published since v0.3.0) were public, non-final classes with public
  constructors over RDF4J types, `getRDF4JValue()`, `getRDF4JModel()` and
  `toRDF4JStatement()`. Nothing in the repository called the last two, yet they
  pinned the implementation, and a subclass with its own state would have broken
  the symmetry of `equals`, which accepts any foreign implementation.
- It was absolute, but the dataset wrappers take a caller-supplied RDF4J
  `Repository` by design (`GraphStoreRdf4j(Repository)` and the like), and the
  `internal` package is, by its `package-info`, not public API.

`RDF4JBindingSet` also sat in the term-adapter package although it implements a
dataset port type (`io.kogn.rdf.dataset.BindingSet`), which made the term
adapters the one package depending on the dataset port layer and inverted the
layering of ADR-0003.

## Decision

The statement is narrowed to what holds and made true.

- No public or protected member of a public type in `io.kogn.rdf.rdf4j` names an
  RDF4J type. Excepted by design: the caller-supplied `Repository` of the dataset
  wrappers, and the `internal` package.
- Every public class in `io.kogn.rdf.rdf4j` is `final`. The base class
  `RDF4JTerm` is package-private and sealed; its field is private. The
  RDF4J-typed constructors and `getRDF4JValue()` are package-private;
  `toRDF4JStatement()` and `getRDF4JModel()` are removed. Instances come from
  `RDF4JFactory` or `RDF4JIRI.of(String)`.
- `RDF4JBindingSet` moves to `io.kogn.rdf.rdf4j.dataset` and is package-private;
  `io.kogn.rdf.rdf4j` no longer imports `io.kogn.rdf.dataset`.
- `PublicApiStructureTest` pins the first two points.

## Consequences

- Breaking for anyone who compiled against the removed or hidden members; the
  `RDF4JBindingSet` is no longer reachable from the term-adapter package.
- Code in `dataset` and `internal` cannot reach the package-private members, so
  it builds terms and graphs through the RDF4J-free `RDF4JFactory`, and converts
  terms to RDF4J values by the term interfaces. A result graph is copied instead
  of wrapping the `Model`, and a language tag of an RDF4J literal passes through
  its normalised (lower-case) form.
- ADR-0005 stays as written; this ADR is its correction.
