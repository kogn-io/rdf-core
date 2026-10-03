# ADR-0019: The content-addressed IRI is an RFC 6920 `ni:` name over a specified input

Status: Accepted (2026-10-03) — supersedes the identifier format (`urn:cid:`
with unpadded lower-case Base32) fixed among the consequences of
[ADR-0014](0014-content-addressed-iri-module.md) and in the decision of
[ADR-0016](0016-content-addressed-iri-self-placeholder.md); the canonicalizer
(URDNA2015) named in ADR-0014's context and ADR-0016's decision; and ADR-0016's
internal namespace `urn:x-cid-self:`, whose name, like every other byte-level
choice, the specification now owns

## Context

`rdf-cid` exists so that the same content is recognisably the same wherever it
is published (ADR-0014). Within one deployment that holds by construction. Across
a federation it holds only if every participating server derives the same
identifier for the same resource, and those servers will not all run this
library, or Java. The identifier is therefore an interoperability contract
between independent implementations. The current scheme does not meet that
contract in three ways:

- **It is not a valid URN.** RFC 8141: "in order for the name to be a valid URN,
  the namespace identifier (NID) needs to be registered". `cid` is not in the
  IANA URN namespace registry. The name is also overloaded: the `cid:` URI scheme
  (RFC 2392) and IPFS content identifiers use it, and W3C Controlled Identifiers
  are abbreviated to it. None of them is this.
- **It does not name its procedure.** `urn:cid:<digest>` carries neither the hash
  algorithm nor the version of the canonicalization and serialization. Any later
  change to the procedure would mint identifiers indistinguishable in form from
  today's but incomparable in meaning.
- **The procedure is documented partly, and nowhere reproducibly.** ADR-0014,
  ADR-0016 and ADR-0017 record why the derivation looks as it does, not how to
  reproduce it byte for byte. ADR-0016 names the kind tags `S`, `F` and `I`, the
  length-prefixed netstrings and the internal namespace `urn:x-cid-self:` with
  the principle of its escape rule. Other bytes that reach the digest exist only
  in code: the remaining kind tags, the internal namespace `urn:skolem:`, the
  sort order and the escape rule in detail. Which hash function the
  canonicalizer is parameterized with does not reach the digest directly, but it
  decides the blank node labels that do. A second implementation would have to
  read the Java.

The canonicalizer adds a fourth, narrower gap. The digest is taken over this
module's own S-expression, not over canonical N-Quads; the canonicalizer
contributes only the blank node labels (`c14n0`, `c14n1`, …). Those labels are
derived from hashes over N-Quads serializations of each blank node's quads, and
RDFC-1.0 (W3C Recommendation, 2024-05-21) escapes some control characters in
literals differently from URDNA2015, its predecessor. Where a blank node's quads
carry a literal with such a character, a URDNA2015 implementation and an
RDFC-1.0 implementation can assign labels differently, and the digest differs.
Rare, but enough to split a federation.

`rdf-cid` is in no release yet, so any change to the format is free now and a
migration later.

Formats weighed:

1. **`urn:<nid>:v1:<digest>`.** The version is visible in the name, but it is
   valid only once a formal NID passes IANA expert review, with an uncertain
   outcome. Until then it is the same defect as today.
2. **CIDv1 with a codec of our own.** Version, codec and multihash are carried
   in the value, which is the cleanest form of self-description on offer. But
   `rdfc-1` (0xb403) names a hash over the whole canonical dataset, which is not
   this procedure, so a new codec would have to be registered in the multicodec
   table maintained by the IPFS project; the multihash Internet-Draft has
   expired. It needs a registration of ours and rests on no IETF or W3C standard.
3. **`tag:` (RFC 4151).** Only the holder of the domain may mint under it
   (§2.2). The holder may delegate parts of its namespace, but even then every
   identifier carries one project's domain and every federated server mints by
   that holder's leave. A neutral domain such as `w3id.org` is not ours to mint
   under.
4. **An HTTPS IRI under w3id.org.** It is valid and dereferenceable, but the
   identity depends on a third-party operator and an approved path, and the
   redirect can target one fixed host only, so resolution would be centralised.
5. **An HTTPS IRI under the serving host's base.** The same content would get a
   different identifier on every host, which is the opposite of the purpose.
6. **RFC 6920 named information, `ni:`.** An IETF standards-track URI scheme
   that needs no registration from us. `sha3-256` is registered in the IANA
   Named Information Hash Algorithm Registry (ID 10, `current`). RFC 6920
   explicitly leaves the hash input to other specifications (§2: "we do not
   specify the hash function input here. Other specifications are expected to
   define this"). Without such a specification the input defaults to the bytes
   returned when the name is dereferenced (§3); defining our own input overrides
   that default. Name equality covers only algorithm and digest (§2). It does
   not carry a procedure version.

### Prior art

- **Trusty URIs** (Kuhn and Dumontier, 2014) replace the self-reference with a
  placeholder before hashing an RDF graph — the mechanism of ADR-0016, and the
  reason `docs/design/105-versioning.md` cites them. They append the hash to an
  HTTP(S) URI, so the identifier carries the minting host, and they name the
  procedure in the identifier through a module code (`RA`, …).
- **pukkamustard, "Content-addressable RDF"** (openEngiadina, v0.1, June 2020)
  defines the *fragment graph*, the triples of a base IRI and its `#fragment`
  IRIs. It leaves the base out of the hashed bytes and serializes the rest as
  canonical, length-prefixed S-expressions. ADR-0016 already credits its
  successor encoding, RDF/CBOR. We differ in four points:
  - blank nodes are admitted and canonicalized deterministically, where the
    paper skolemizes them with random UUID URNs;
  - the own IRI is a placeholder that may occur in any position, not merely
    omitted as subject;
  - graphs with foreign subjects or unreachable triples are rejected, not
    trimmed;
  - the identifier is `ni:` over SHA3-256 with a versioned input, where the
    paper uses `urn:blake2b:` and no version.
- **ERIS** (v1.0.0, 2022) works at a different layer: it encodes byte streams
  into encrypted blocks under a `urn:eris:` read capability. It is
  complementary, a possible store beneath these identifiers, not an
  alternative.
- **ActivityPub** discusses content addressing
  ([w3c/activitypub#573](https://github.com/w3c/activitypub/issues/573)) without
  an agreed format. Its only resolution (2026-07-16) keeps HTTPS for `id` and
  documents `alsoKnownAs` for alternative identifiers. RFC 6920 was the one
  candidate named there as citable by a W3C specification.

## Decision

**The identifier is `ni:///sha3-256;<digest>`**, the digest being the SHA3-256
of the specified input, base64url-encoded without padding.

- **One canonical form.** No authority, no query parameters, and the algorithm
  string exactly `sha3-256` in lower case. RFC 6920 leaves the case of the name
  string unspecified (erratum 8174), and the registry holds only lower-case
  names.
- **The procedure version goes into the hashed bytes, not the name.** The
  serialization opens with a procedure tag, so two versions of the procedure can
  never yield the same digest for the same content. A verifier that does not
  know the version tries the versions it supports. At first there is one.
- **The canonicalization is RDFC-1.0**, replacing URDNA2015. Which hash
  function it is parameterized with is fixed by the specification, not left to
  an implementation's default.
- **The procedure is defined by a specification independent of this library**,
  with machine-readable test vectors. It fixes every byte that reaches the
  digest, the internal namespaces included. The specification owns every
  byte-level choice — the names of the internal namespaces, the kind tags, the
  length prefixes and the ordering. Where ADR-0014 and ADR-0016 name such a
  choice, they describe the procedure as it stands, not a decision the
  specification has to keep. `rdf-cid` implements it, and its tests consume
  the same vectors. The specification is complete only when an implementation
  written from it alone, without this code, reproduces every vector. It lives in
  `docs/spec/` for now, licensed CC-BY-4.0 rather than the library's Apache-2.0,
  so that other implementations can take up its text freely.
- **The library defines no resolution.** The identifier names no host. RFC
  6920 §4 ties `/.well-known/ni/` to the expectation that a retriever "can
  determine whether or not it is content that matches the ni URI". An
  application that maps the identifier there therefore serves the hashed bytes,
  or makes no promise that what it serves can be verified against the name.

Option 6 is taken because it is the only one that is valid without a
registration of ours, names no project or operator, and is host-independent by
the definition of name equality, not by convention. The version it cannot carry
in the name goes into the input instead. Options 1 and 2 need a registration of
ours, option 3 binds every identifier to one domain holder, option 4 centralises
resolution, and option 5 is not host-independent.

## Consequences

- **Breaking for the identifiers, free now.** Every `urn:cid:` becomes an `ni:`
  name with a different digest, since the input gains the procedure tag and the
  canonicalizer changes. Nothing is released or persisted yet, so nothing
  migrates. Snapshots of `main` already carry `urn:cid:`; they are not a
  compatibility promise. The origin copy ADR-0014 mentions keeps its own
  identifiers, which have differed from this module's since ADR-0014 anyway.
- **The specification blocks the release that first ships `rdf-cid`.** Once
  `ni:` names are published, version 1 of the procedure is frozen. Any later
  change is a new version tag, never an edit.
- **RFC 6920 name equality is not RDF IRI equality.** RFC 6920 treats
  `ni://host/sha3-256;X` and `ni:///sha3-256;X?ct=…` as the same name as
  `ni:///sha3-256;X`. RDF compares IRIs as strings, so a store holds three
  different IRIs. The specification therefore mandates the canonical form above
  for anything written into RDF, and a consumer that receives another spelling
  normalises it before storing or comparing.
- **Fragment IRIs carry over.** ADR-0016 lets a resource refer to its parts as
  `<base#part>`; under this format they become `ni:///sha3-256;X#part`. RFC
  6920's ABNF defines no fragment, but RFC 3986 allows one on any URI reference.
  base64url contains no `#`, so "the base is everything before the first `#`"
  (ADR-0017) stays unambiguous.
- **"Same content, same identifier" holds within one procedure version.** The
  same content gets different names under v1 and a later v2, so a federation
  mixing versions sees two identifiers for one resource. A verifier also cannot
  tell "unknown procedure version" from "content altered": both are a digest it
  cannot reproduce.
- **`ni:` does not show the version.** A reader of the name cannot tell which
  procedure produced it. This is accepted as the price of not depending on a
  registration. A media type for RFC 6920's optional `ct` parameter could add
  the hint later without changing any identifier, since `ct` is not part of name
  equality. It would still not enter the canonical form stored in RDF.
- **A standard RFC 6920 client cannot verify these names.** It hashes the bytes
  it fetched, such as Turtle or JSON-LD, and gets a different digest.
  Verification requires the procedure. A plain byte check succeeds only if a
  server serves the hashed bytes themselves under `/.well-known/ni/`: the
  S-expression with procedure tag and placeholders, which is not RDF and has no
  registered media type.
- **The set of addressable graphs must stop being implementation-defined.**
  ADR-0014 records that `io.setl:rdf-urdna` gives up on sufficiently symmetric
  blank node structures. In a federation, one server minting an identifier that
  another cannot reproduce is a split. The specification therefore fixes a
  resource limit deterministically, with test vectors on both sides of it. How
  the limit is expressed (for example a criterion on the graph's shape rather
  than a permutation count) is left to the specification.
  Whether `io.setl:rdf-urdna` is RDFC-1.0-conformant is checked as part of the
  implementation.
- **Content addressing makes erased content provable.** A hash binds no
  author; that would take a signature. But anyone holding a copy can show,
  after an erasure, that it is exactly the content a still-circulating
  identifier names. w3c/activitypub#573 raised this, as non-repudiability, as
  its main risk. It affects what consumers can promise about erasure. The
  versioning design (`docs/design/105-versioning.md`, D12) has to say how an
  erased version relates to an identifier that is still circulating.
- README, CLAUDE.md, ARCHITECTURE.md, the port and implementation Javadoc and
  the versioning design note change from `urn:cid:` to `ni:` together with the
  code.
