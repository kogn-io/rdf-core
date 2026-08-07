# ADR-0014: Content-addressed IRI generation as its own leaf module

Status: Accepted

## Context

The larger, application-specific RDF stack these modules were extracted from had
grown a content-addressing capability: given a graph, derive an identifier from
the graph's *content* rather than from its location or a random draw. Two things
follow from that, and both are what the capability is for — the same content
published on two instances is recognisably the same, and re-importing a dataset
mints the same identifiers again, so duplicates are detectable without keeping a
ledger of what was imported before.

The implementation canonicalizes the graph with URDNA2015
(`io.setl:rdf-urdna`), skolemizes blank nodes to deterministic IRIs, serializes
the result into a canonical byte form and hashes it with SHA3-256
(`java.security.MessageDigest`, no third-party dependency), Base32-encoding the
digest into a `urn:`. An earlier draft used Blake2b-256 via BouncyCastle
without ever writing down why; a review found the choice unjustified and, with
nothing published yet, this is corrected in the same breath as everything
else in this ADR. For this purpose — collision resistance over an internal
byte string, not a password or a compatibility target — SHA3-256 and Blake2b
offer comparable security margins; SHA3-256 is additionally a NIST standard
(FIPS 202) shipped by every JDK since 9, so it drops the entire BouncyCastle
provider — 10 of the module's then 10.8 MiB of third-party jars — for no
loss. If a future consumer needs Blake2b specifically (speed, or
compatibility with something external), that is a reason to write down here,
not to reintroduce silently.

The point that made this an extraction candidate rather than a rewrite: the code
was already written **entirely against `io.kogn.rdf.terms.*`**. A dependency
check found no reference to the legacy stack's domain services or vocabularies
— no graph command/query service, no application-specific vocabulary class.
Nothing in it knows what the data means. It is arithmetic over the data model,
which is exactly the shape of the modules already extracted into this repository
(`rdf-terms`, `rdf-dataset`, `rdf-shacl`), so it could move across as-is rather
than being reimplemented. The extraction originates in the source stack's own,
non-public tracker; this repository's own issue for it is #96.

The port was reviewed before it landed, and the review found the identifier
derivation itself defective: only a literal's lexical form went into the hash, so
`"100"^^xsd:integer` and `"100"^^xsd:decimal` collided, `"Bank"@en` and
`"Bank"@de` collided, and an IRI object collided with a literal spelling out the
same string. The subject IRI went in only when it carried a `#fragment`, so two
resources with the same body collided as well, and a blank node component
reachable from no IRI subject dropped out of the digest without a word. All four
were measured on the branch, not inferred. That turns the capability against its
own purpose: deduplication and integrity checks both read "same identifier" as
"same content", so a collision is not a missing feature but a wrong answer.

## Decision

A new leaf module `rdf-cid` (`io.kogn.rdf:rdf-cid`), the code moved across with
the package renamed to `io.kogn.rdf.cid.*`:

- **`ContentAddressedIriGenerator`** — the port: `IRI generateIri(ReadableGraph)`.
  Neutral in and out, `rdf-terms` types only.
- **`ContentAddressedIriGeneratorSexpr`** — the implementation, with
  `ContentAddressableRdfSerializer` and `RdfDatasetCanonicalizer` in
  `io.kogn.rdf.cid.sexpr` beneath it.

**Port and implementation share the module**, as in `rdf-shacl` — but for a
different reason, and the difference is the point. `rdf-shacl` is split from
`rdf-shacl-rdf4j` because the validation *is* a call into a backend engine, so a
second backend is a real prospect and the port exists to keep it swappable. Here
there is no backend to swap: the algorithm runs on `rdf-terms` values and its
third-party dependencies (rdf-urdna, commons-codec) are libraries
it calls, not a store it talks to. Splitting off an `rdf-cid-<backend>` would
name a backend that does not exist. The module therefore depends on `rdf-terms`
alone among our modules and on **no** RDF4J artifact, which
`CidPortHasNoBackendDependencyTest` pins the same way `rdf-shacl` does.

The port is nevertheless kept as an interface rather than collapsed into the
class: the identifier scheme is a contract callers persist. A different
canonicalization or digest is a different generator, and consumers should be
able to name the capability without naming the algorithm.

### The identifier is derived from the whole graph, terms in full

The collisions above are corrected here rather than documented as a limitation,
and the rule is stated once so it can be checked: **a graph is identified by its
triples, and every term of every triple goes into the digest in full, tagged by
its kind** — an IRI by its IRI string, a literal by lexical form *and* datatype
*and* language tag, a blank node by the skolem IRI its structural position maps
it to. The identifier therefore honours `Literal`'s three-component equality
contract, and no term can collide with a term of another kind that happens to
spell the same.

Two consequences of that rule are worth naming because the previous shape
suggested otherwise:

- **The subject IRI is part of the content.** The earlier code took the subject
  only via its `#fragment`, and the package documentation called the result
  "environment independence". That independence was never delivered — object
  IRIs went into the hash in full all along, so any environment-specific URI in
  object position was already in there. Half a rule is worse than either whole
  one, so the subject goes in like every other term, and the documentation drops
  the claim. Two graphs describing the same thing under different subject IRIs
  are different content.
- **A graph the derivation cannot address is rejected, not reduced.** No IRI
  subject, several IRI subjects, or triples reachable from none of them all
  raise `IllegalArgumentException`. Silently hashing the reachable part is what
  let two different graphs share an identifier; failing says so. These are input
  errors, so they are `IllegalArgumentException` and not the
  `IllegalStateException` the port used to declare — the latter told a caller
  the fault was not theirs.

## Consequences

- Content addressing becomes available to any consumer of Kogn RDF without
  taking a dependency on the application stack it came from, and without a
  store: `generateIri` needs a `ReadableGraph`, nothing else.
- **The identifiers differ from the ones the origin copy mints, deliberately.**
  The original stays in place for now, marked `@deprecated` pointing at
  `io.kogn.rdf:rdf-cid` in a separate change on that side — but the two no
  longer agree, and cannot be made to without reinstating the collisions. A
  consumer migrating across therefore re-derives its identifiers; it cannot
  assume the old ones still match. Whether the origin copy adopts the corrected
  derivation or is retired is a decision on that side; either way the divergence
  is known rather than a drift nobody noticed.
- **The generated identifiers are a compatibility surface, not an
  implementation detail.** Anything persisted or federated on them breaks if the
  canonicalization, the skolemization, the serialization or the digest changes.
  Changing any of those is a breaking change for stored data even though no
  signature moves, so it cannot be justified by "the tests still pass". The
  correction above was taken precisely because nothing was published yet — the
  same change after a release would cost a migration, and after federation would
  cost more than that.
- The module is the only backend-free (non-adapter) module in this repository
  with a third-party dependency set of any weight at all (rdf-urdna,
  titanium-json-ld, commons-codec) — every other non-adapter module carries
  almost none. It is not, however, the heaviest dependency set in the
  repository overall: the RDF4J adapter modules (`rdf-shacl-rdf4j`,
  `rdf-dataset-rdf4j`, `rdf-dataset-hosting-rdf4j`) pull considerably more,
  both in jar count and in bytes. That earlier, broader claim was measured and
  found false; this ADR, README.md, ARCHITECTURE.md and CLAUDE.md all carried
  it and are corrected together. `rdf-cid`'s own weight is contained by it
  being a leaf: nothing else here depends on `rdf-cid`, so a consumer that
  does not want those jars simply does not put the module on its classpath.
  `rdf-urdna`'s transitive `titanium-json-ld-jre8` is excluded — it ships the
  same `com.apicatalog.rdf.*` classes as the `titanium-json-ld` we depend on
  explicitly, and brings okhttp plus the Kotlin standard library along for a
  document loader this module never invokes.
- **Not every graph meeting `generateIri`'s documented preconditions gets an
  identifier.** `io.setl:rdf-urdna` caps the permutations it will try in the
  `hash-n-degree-quads` step while telling apart blank nodes it cannot yet
  distinguish; a graph with enough symmetric blank node structure — measured
  on this branch with a complete graph over `n` blank nodes, every edge the
  same predicate, hanging off one IRI subject — exceeds that cap at `n=7` (49
  triples) and gets no identifier at all, only a
  `CanonicalizationResourceLimitExceededException`. This is a property of
  `io.setl:rdf-urdna`, not of URDNA2015 itself: another conformant
  implementation may address a graph this one cannot. That makes the set of
  addressable graphs implementation-defined, not just the digest — a
  canonicalizer swap is therefore a compatibility break in two ways, not one,
  and both belong in the "generated identifiers are a compatibility surface"
  consequence above.
- The `cbor` package name and the `Cbor` suffix on the implementation class,
  inherited from the origin, were inaccurate: the serialization that gets
  hashed is a length-prefixed S-expression form, not CBOR. Unlike the rest of
  this ADR's "nothing is published yet" reasoning, an earlier draft of this
  decision kept the misnomer anyway, on the premise that renaming a published
  package is a breaking change for no functional gain — a premise that does
  not hold for a module extracted in this very change and not yet released.
  The names are corrected before the fact rather than documented as a defect
  after it: the package is `io.kogn.rdf.cid.sexpr`, the class is
  `ContentAddressedIriGeneratorSexpr`. Renaming after a release, once a
  consumer or a federated peer has the old names in code or in a persisted
  `urn:cid:` provenance record, would be the breaking change this ADR's
  own reasoning elsewhere warns against — so this is the last point at which
  the correction is free.
- The identifier is `urn:cid:<hash>`, the hash being the Base32 digest
  lower-cased and **without** `=` padding. The `cid:` segment was what the port
  javadoc had promised all along while the code emitted a bare `urn:` plus a
  padded digest — which is not a syntactically valid URN either, RFC 8141
  allowing at most 32 alphanumeric characters as the namespace identifier. Both
  are corrected in the same breath as the derivation, for the same reason:
  nothing has been published yet.
