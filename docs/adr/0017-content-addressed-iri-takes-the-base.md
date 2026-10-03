# ADR-0017: `generateIri` takes the base IRI as an argument

Status: Accepted (2026-10-03) — supersedes the port signature of
[ADR-0014](0014-content-addressed-iri-module.md) and the inferred base of
[ADR-0016](0016-content-addressed-iri-self-placeholder.md)

## Context

The port read `IRI generateIri(ReadableGraph graph)`. The parameter type says
"any graph", but the contract is "one resource": since ADR-0016, every IRI
subject must be one base IRI or a fragment IRI of it, every other triple a blank
node triple reachable from them. That restriction was visible only in the
Javadoc, and a caller learned about it at runtime (issue #108).

Worse, the base — the one input ADR-0016 treats specially, replacing it by the
self placeholder `S` — was not passed in but *inferred* from the subjects. The
most consequential input of the derivation was therefore a guess. The guess is
not always the obvious one: a graph describing a hash-namespace term such as
`<http://ex.org/ns#Person>` is hashed under the base `http://ex.org/ns`, so every
`ns#…` term in it goes in as a fragment, without the caller ever having named
that base.

Three options were weighed:

1. **A dedicated parameter type** (base plus graph, validated on construction).
   It moves the check to an earlier point, but not to compile time: whether
   every subject belongs to the base and every triple is reachable depends on the
   triples, which no type can carry. It adds a public type for one consumer, and
   where it belongs — the library-free `rdf-terms` or `rdf-cid` — would be a
   decision of its own.
2. **A base argument**, `generateIri(IRI base, ReadableGraph graph)`.
3. **Keep the signature** and leave the restriction a documented precondition.

## Decision

**The caller names the resource: `IRI generateIri(IRI base, ReadableGraph
graph)`.** The base is no longer inferred from the graph.

- `base` must carry no fragment — it names the resource as a whole; a fragment
  IRI names a part of it. A base containing `#`, or a null base, raises
  `IllegalArgumentException`.
- Every IRI subject must be `base` itself or a fragment IRI of it (the IRI
  string before the first `#` equal to `base`, compared as a plain string). Any
  other IRI subject raises `IllegalArgumentException`, naming it. A graph with no
  such subject at all is rejected as well.
- Reachability is unchanged: a triple reachable from none of those subjects is
  still rejected, as in ADR-0014 and ADR-0016.

The derivation itself does not change. For every graph the old signature
accepted, passing the base it inferred yields the same identifier as before; the
golden vectors stay as they are.

Option 1 is not taken because it would still check at runtime while adding a
type. Option 3 is not taken because it keeps the guess. The versioning design
(`docs/design/105-versioning.md`, D1) already has the caller pass the proxy IRI
explicitly — "the library never guesses the subject" — so the content-addressing
port now follows the same rule its first consumer is built on.

## Consequences

- **Breaking signature change, at no cost now.** `rdf-cid` is in no release, and
  the only caller is its own implementation. The same holds for the public
  `ContentAddressableRdfSerializer.serializeWithUrn`, which takes the base as
  well.
- **Identifiers do not change.** The serialization, the placeholders and the
  digest are untouched; this decision changes who says which IRI is the base,
  not what is done with it.
- **The precondition is still a runtime check.** The signature now says "one
  resource, named by you", but that the graph actually describes that resource
  alone is still checked on every call.
- **A wrong base is an error, not a different identifier.** A caller that names
  a base none of the subjects belongs to gets `IllegalArgumentException` rather
  than an identifier derived under some other base.
