# ADR-0025: `rdf-cid` hides its internals and names its steps after the specification; acronyms are capitals

Status: Accepted (2026-11-19) — supersedes the names and the public surface in
[ADR-0014](0014-content-addressed-iri-module.md) and the name `generateIri` in
[ADR-0017](0017-content-addressed-iri-takes-the-base.md); their decisions
(module, package `sexpr`, the base as an argument) stay valid

## Context

`rdf-cid` has never been released, so a rename or a narrowing of the public
surface costs nothing now and a migration after the first consumer.

- **Internals were public API, as a test seam only.** The serializer and the
  canonicalizer were public, non-final classes with public constants; the
  generator took a serializer through a public constructor. A caller passing their
  own serializer got a generator that derived different names while
  `procedureTag()` still reported `ni-rdf/1` — and `rdf-versioning` stores that
  tag next to every identifier. Only tests used the seam.
- **The procedure lived in a class called "serializer".** Spec §3 to §4.8 ran in
  one 336-line class; the generator only delegated. One concept had several
  names (`sexprBytes`, `hashed`, `document`) where the specification (§1.2) says
  *hashed input*, *identifier*, *mapped graph*.
- **`BaseRebaser` copied spec rules**, and the copies had drifted: its base check
  missed the unpaired-surrogate rejection its Javadoc promised.
- **Acronyms.** The module spelled `Iri`, the core interfaces `IRI`, `RDF`.

## Decision

- **Visibility.** `ContentAddressedIRIGeneratorSexpr` moves into
  `io.kogn.rdf.cid.sexpr` and is `public final`; `BaseRebaser` moves there too
  and is `public final`. Every other type of the package is package-private and
  final. The two-argument constructor is gone; tests inject a failing
  canonicalization step through a package-private constructor.
- **Procedure.** The implementation holds the steps of spec §1.3 in one method,
  `derive(IRI base, Collection<Triple>)`, returning a `Derivation` of
  `identifier` and `hashedInput`. The steps are package-private building blocks:
  `Preconditions` (§3, including the recreation of foreign terms), `BaseMapping`
  (§4.1, §4.2), `RDFDatasetCanonicalizer` (§4.3, §4.4), `HashedInputEncoder`
  (§4.5), `NiName` (§4.7, §4.8). Names follow the specification.
- **One place for spec rules.** The reserved IRI is one constant
  (`Terms.RESERVED_IRI`); failure codes no longer share its name. `BaseRebaser`
  uses the same base check (`Preconditions.checkBase`) and term rules as the
  generator, so it rejects an unpaired surrogate in a base too.
- **Acronyms are written in capitals** (`IRI`, `RDF`), project-wide, as in
  Commons RDF: `ContentAddressedIRIGenerator`, `generateIRI`,
  `ContentAddressedIRIGeneratorSexpr`, `RDFDatasetCanonicalizer`. Names already
  published elsewhere (`SimpleRdf`, the `Vocab*` classes, `RdfFormat`,
  `RdfExportException`) stay.

## Consequences

- **Breaking, at no cost now:** type and method renames, the move of
  `ContentAddressedIRIGeneratorSexpr` and `BaseRebaser` to `io.kogn.rdf.cid.sexpr`,
  and the removal of the public serializer, canonicalizer and constructors.
  `rdf-versioning` is adapted.
- **Identifiers do not change.** The derivation, the vectors and the procedure tag
  are untouched.
- A caller can no longer swap the derivation steps; a different procedure is a new
  implementation of the port under a new tag, not a subclass.
- Other modules apply the acronym rule when they are next touched.
