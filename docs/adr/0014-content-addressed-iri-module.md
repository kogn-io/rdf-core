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
the result into a canonical byte form and hashes it with Blake2b-256
(BouncyCastle), Base32-encoding the digest into a `urn:`.

The point that made this an extraction candidate rather than a rewrite: the code
was already written **entirely against `io.kogn.rdf.terms.*`**. A dependency
check found no reference to the legacy stack's domain services or vocabularies
— no graph command/query service, no application-specific vocabulary class.
Nothing in it knows what the data means. It is arithmetic over the data model,
which is exactly the shape of the modules already extracted into this repository
(`rdf-terms`, `rdf-dataset`, `rdf-shacl`), so it could move across as-is rather
than being reimplemented. The extraction originates in the source stack's own,
non-public tracker; this repository's own issue for it is #96.

## Decision

A new leaf module `rdf-cid` (`io.kogn.rdf:rdf-cid`), ported 1:1 with the package
renamed to `io.kogn.rdf.cid.*`:

- **`ContentAddressedIriGenerator`** — the port: `IRI generateIri(ReadableGraph)`.
  Neutral in and out, `rdf-terms` types only.
- **`ContentAddressedIriGeneratorCbor`** — the implementation, with
  `ContentAddressableRdfSerializer` and `RdfDatasetCanonicalizer` in
  `io.kogn.rdf.cid.cbor` beneath it.

**Port and implementation share the module**, as in `rdf-shacl` — but for a
different reason, and the difference is the point. `rdf-shacl` is split from
`rdf-shacl-rdf4j` because the validation *is* a call into a backend engine, so a
second backend is a real prospect and the port exists to keep it swappable. Here
there is no backend to swap: the algorithm runs on `rdf-terms` values and its
third-party dependencies (rdf-urdna, BouncyCastle, commons-codec) are libraries
it calls, not a store it talks to. Splitting off an `rdf-cid-<backend>` would
name a backend that does not exist. The module therefore depends on `rdf-terms`
alone among our modules and on **no** RDF4J artifact, which
`CidPortHasNoBackendDependencyTest` pins the same way `rdf-shacl` does.

The port is nevertheless kept as an interface rather than collapsed into the
class: the identifier scheme is a contract callers persist. A different
canonicalization or digest is a different generator, and consumers should be
able to name the capability without naming the algorithm.

## Consequences

- Content addressing becomes available to any consumer of Kogn RDF without
  taking a dependency on the application stack it came from, and without a
  store: `generateIri` needs a `ReadableGraph`, nothing else.
- **The original stays in place for now.** It is marked `@deprecated` pointing
  at `io.kogn.rdf:rdf-cid` in a separate change on that side, not deleted — its
  consumers migrate on their own schedule. Until they have, the algorithm exists
  in two places; they must not drift, because identifiers minted by one are
  expected to match the other. Bug fixes belong here, with the older copy
  following or being retired.
- **The generated identifiers are a compatibility surface, not an
  implementation detail.** Anything persisted or federated on them breaks if the
  canonicalization, the skolemization, the serialization or the digest changes.
  Changing any of those is a breaking change for stored data even though no
  signature moves, so it cannot be justified by "the tests still pass".
- The module carries the heaviest third-party dependency set in this repository
  (rdf-urdna, titanium-json-ld, BouncyCastle, commons-codec, commons-lang3),
  where every other non-adapter module carries almost none. That is contained
  by it being a leaf: nothing else here depends on `rdf-cid`, so a consumer that
  does not want those jars simply does not put the module on its classpath.
  `rdf-urdna`'s transitive `titanium-json-ld-jre8` is excluded — it ships the
  same `com.apicatalog.rdf.*` classes as the `titanium-json-ld` we depend on
  explicitly, and brings okhttp plus the Kotlin standard library along for a
  document loader this module never invokes.
- The `cbor` package name is inherited from the origin and is inaccurate: the
  serialization that gets hashed is a length-prefixed S-expression form, not
  CBOR. Renaming it is a breaking change for no functional gain, so it is
  recorded here rather than fixed.
