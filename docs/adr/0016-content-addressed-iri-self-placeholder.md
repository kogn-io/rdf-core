# ADR-0016: The content-addressed IRI replaces the graph's own name with a placeholder

Status: Accepted (2026-10-02) — supersedes the subject-IRI section of
[ADR-0014](0014-content-addressed-iri-module.md); the inferred base is superseded
by [ADR-0017](0017-content-addressed-iri-takes-the-base.md): the caller passes it

## Context

[ADR-0014](0014-content-addressed-iri-module.md) put the subject IRI into the
digest like every other term and read "the same body under two subjects gets the
same identifier" as a collision. That misses what the identifier is for. A
foreign IRI in object position is content: it says something about the world.
The graph's *own* name is not content — it is how the content is addressed.

The consequence is a chicken-and-egg problem. A resource cannot carry its own
content-addressed identifier as its subject, because the subject is part of what
the identifier is computed from: rename the subject to the `urn:cid:` it was
derived from and the content, hence the identifier, changes. Conversely, a stored
resource cannot be recomputed and checked against the identifier it carries.

### Prior art

The content-addressable molecules of
[RDF/CBOR](https://openengiadina.codeberg.page/rdf-cbor/) solve exactly this:
the base subject cannot be part of its own encoding, so it is replaced by a
placeholder; references to a fragment of the base (`<base#part>`) are encoded as
the fragment string alone; and the term grammar (`ca-term`) applies to every
position, not only the subject. This decision follows that idea, with three
deliberate deviations:

- an S-expression serialization instead of CBOR;
- SHA3-256 instead of Blake2b;
- blank nodes are supported through URDNA2015 canonicalization, where RDF/CBOR
  forbids them in content-addressed molecules.

Byte-level conformance is not a goal. RDF/CBOR has had no commit since March 2023
and no IETF submission of it is known, so it is a source of ideas rather than a
normative reference; it prescribes no hash input to be conformant with; a typed
literal encoding like its own would change this module's equality model; and
Blake2b and CBOR would be new dependencies for nothing in return.

### A placeholder and a stable resource IRI do not contradict each other

An application that wants both keeps both: a stable, public IRI for the resource
that acts as a proxy and points at the *current* identifier (plus a version
history), and the snapshot content stored under the `urn:cid:` as its subject.
Two resources with the same content share one snapshot yet remain two resources
through their proxies. No resources collapse; only identical snapshots are
deduplicated — which is the point of content addressing.

## Decision

**The graph's own name is replaced by a placeholder while hashing.** The graph
names its resource by one *base IRI*: the IRI string before the first `#`.
During serialization:

- the base IRI goes in as a self placeholder under its own kind tag `S`, with no
  payload (just the tag netstring `1:S`);
- a fragment IRI of the base, `<base#frag>`, goes in as kind tag `F` followed by
  the fragment string without the `#`;
- any other IRI still goes in as kind tag `I` plus its full IRI string.

Because the placeholders differ by kind tag, no IRI or literal can spell them.
The replacement applies in **every position** — subject, predicate, object and a
literal's datatype IRI — so a self-reference does not pull the name back into
the digest, not even as `"3"^^<base#unit>`. To carry the tags there as well, a
literal's datatype is written as an IRI term with its own kind tag instead of a
bare string; a language-tagged literal keeps `rdf:langString`, which RDF fixes.
The S-expression structure, the length-prefixed netstrings, SHA3-256, the
`urn:cid:` prefix with unpadded lower-case Base32, and that a literal goes in
with lexical form, datatype and language tag are unchanged.

**Precondition.** The graph must have exactly one base: every IRI subject is the
base itself or a fragment IRI of the same base. A graph whose subjects are only
fragment IRIs of one base is accepted; the placeholder `S` then occurs only
where the base itself is referenced, as an object, predicate or datatype. Two
different bases, or no IRI subject at all, raise `IllegalArgumentException`.
Triples not reachable from the root set — the base and its fragments, plus the
blank nodes hanging off them — are still rejected rather than silently dropped,
as in ADR-0014.

**Edge cases fixed here.**

- The base is the IRI string before the first `#`: RFC 3986 starts the fragment
  there, and a query cannot contain an unencoded `#`. An IRI holding further `#`
  characters is not rejected; everything after the first `#` is its fragment
  string, so `<base#a#b>` goes in as `F` with `a#b`.
- `<base#>` is a fragment with the empty fragment string, not the base.
- Fragment membership is an exact string test on the prefix `base + "#"`, with no
  URI normalization.

RDF/CBOR leaves these open: it does not define "fragment resource", says nothing
on the empty fragment, and does not require a base triple in a fragment molecule
(its definition is a disjunction), so a fragment-only graph is formally allowed
but not discussed. These choices are ours, not the specification's.

**The replacement runs before URDNA2015 canonicalization.** Blank node canonical
labels are ordered by hashes that include the neighbouring IRIs; substituting
after canonicalization would leave those labels, and thus the digest, dependent
on the base wherever blank nodes hang off it. Before canonicalizing, the base and
its fragments are therefore mapped into an internal namespace, `urn:x-cid-self:`,
and mapped back to the `S`/`F` tags while writing. A foreign IRI that happens to
lie in that namespace is escaped, which keeps the mapping injective. The
namespace influences the label order and so belongs to the derivation: changing
it can change identifiers like any other part.

## Consequences

- **A resource can carry its own identifier and be verified.** Recomputing the
  `urn:cid:` of a stored resource that uses its identifier as subject yields the
  identifier it carries.
- **The golden vectors change, deliberately.** The identifiers differ from those
  of ADR-0014's derivation.
- **Identifiers remain a compatibility surface.** ADR-0014's rule still holds:
  anything persisted or federated on an identifier breaks if canonicalization,
  skolemization, serialization or digest changes, the placeholder tags and the
  internal namespace included. The break here is acceptable because nothing is
  published yet: `rdf-cid` is in no release.
- Two graphs that differ only in their own name now get the same identifier. That
  is intended (see the proxy discussion above) and is what makes deduplication of
  snapshots work.
- Foreign IRIs, in any position, remain content: environment-specific URIs in
  object position still change the identifier.
- A graph that describes a hash-namespace vocabulary term, such as
  `<http://ex.org/ns#Person>`, has `http://ex.org/ns` as its base, so every
  `ns#…` predicate, object or datatype it uses goes in as a fragment. Verifying
  such a graph after renaming means renaming those occurrences too.
