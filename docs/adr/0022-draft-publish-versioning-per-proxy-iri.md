# ADR-0022: Draft/publish versioning per proxy IRI over a library-owned slice

Status: Proposed

## Context

[ADR-0021](0021-resource-versioning-module.md) puts versioning of RDF resources
into `rdf-versioning`. This record fixes what its port promises: the unit that
is versioned, how it is written, what a publish does and how versions come
back. The values are those of the
[design note](../design/105-versioning.md) for issue
[#105](https://github.com/kogn-io/rdf-core/issues/105) (D1–D3, D6, D8, D10–D12).

A caller addresses a resource through its **proxy IRI**, a stable name that
carries no content of its own. A normal write should change the working state,
the **draft**, only; an explicit **publish** turns it into a **version**, which
never changes again and is named by its CID (ADR-0016, ADR-0019). The first
consumer publishes as an act of its own, decoupled from a write and often over
several proxy IRIs at once.

The unit cannot be a named graph per resource: resources share a content graph.
It has to be the triples that make up one resource — the **slice** of a proxy
IRI: every triple whose subject is the proxy IRI or one of its `#fragment`
IRIs, plus the blank nodes reachable from them. That is the graph `generateIri`
accepts for the proxy IRI as base
([ADR-0017](0017-content-addressed-iri-takes-the-base.md); ni-rdf/1 §3, which
compares the base as a string without normalization, ADR-0016). A
sub-resource that must stay addressable from outside therefore gets a fragment
IRI of its proxy IRI and is versioned with it; a child with an IRI of its own
is a resource of its own.

Two things need the library to have seen every write to a slice: the check
whether a draft holds unpublished changes, and the concurrency token. If
callers wrote drafts past the library, a write between read and publish would
go unseen.

Atomicity across graphs is per dataset and per transaction: a publish touches
several graphs ([ADR-0023](0023-versioned-dataset-fixed-named-graphs.md)), and
a publish of several proxy IRIs must land together or not at all.

## Decision

- The unit is the subject-bounded slice of a proxy IRI. Only the library's
  slice operations write it, each under an opaque optimistic token per proxy
  IRI; the caller always names the proxy IRI.
- An explicit publish turns the draft into an immutable version under its CID,
  in the caller's open `DatasetTx` and atomically over several proxy IRIs. The
  draft stays.
- Versions are delivered with their CID as subject and are never altered. Read
  semantics, resolution and the choice of IRIs stay with the application.

## Consequences

- **Slice operations** — read, replace, delete — cover the fragment IRIs and
  the blank-node closure. A blank node reachable from two proxy IRIs makes both
  slices ambiguous and is rejected on write.
- **Transactions.** Every operation takes an open `DatasetTx`
  ([ADR-0011](0011-datasettx-composes-content-ports.md)) and never opens one of
  its own; an aborted transaction leaves nothing behind. A conflict is
  reported; retrying is the caller's decision.
- **Guards** (next sequence number, the token) read through that `DatasetTx`.
  Conflicts on data already in the store are carried by the isolation promise
  of `DatasetTransactor`. In the first-insert cases — first sequence number of a
  proxy IRI, first counter, first use of the library's graphs — the guard reads
  with `DatasetTx#contains`, not with a SPARQL `ASK`, because only `contains`
  is conflict-protected for IRIs the store does not hold yet
  ([ADR-0008](0008-datasettx-contains-guard.md), Context).
  [ADR-0020](0020-delta-and-conflict-promise-holds-for-disjoint-writers.md)
  restricts the delta of `add`/`remove`, not guard reads; it does not touch
  these guards.
- **The token** is a revision counter per proxy IRI, raised on every draft
  write, publish and delete, and opaque to the caller. Unlike a token derived
  from content it exists for an empty or brand-new draft too and costs no
  canonicalization. It sees writes made through the library only.
- **The draft stays after publish.** Whether it holds unpublished changes is
  decided by comparing its CID with the CID of the latest history entry.
  Publishing an unchanged draft creates no history entry and returns the
  current version; A → B → A creates three entries, the third naming the CID of
  the first, stored once.
- **The comparison holds within one CID procedure.** Each history entry records
  the procedure version that derived its CID. Once a second procedure exists,
  a mismatch is resolved by deriving the stored version's content under the
  current procedure and comparing that; names once given are never rewritten.
  This needs `rdf-cid` to expose its procedure tag as a value, a plain port
  extension.
- **The history records publishes only**, plus tombstones. Draft writes are no
  history entries; an application that needs a write audit keeps it itself.
  Publish returns the IRI of the entry it created, so the caller can attach
  evidence to that publish at once. The entry's timestamp comes from an
  injectable clock.
- **Deleting a proxy IRI** removes its draft slice and records a tombstone in
  the history naming the CID of the last version, or the fact alone if the
  proxy IRI was never published. History and versions stay. Forgetting a
  version is not part of this record.
- **Reads** are offered separately: the draft, the published state, a version
  by CID, version *n* of a proxy IRI, the history, and the three-valued publish
  state of a draft (never published; published without unpublished changes;
  published with unpublished changes). A rule such as "return the draft if
  present, else the current version" is the application's, built on these.
- **No reverse lookup.** A CID identifies content, not a proxy IRI: two proxy
  IRIs with identical content share one version, so the port offers no
  single-valued CID → proxy IRI read.
- **No IRI translation.** The library stores the IRIs it is given and rewrites
  none, neither in content nor in SPARQL text. A reference to a proxy IRI is
  never turned into a CID reference: read later, it meets the target's current
  state, and time consistency across resources is a property of tags, which are
  not part of this record. A reference that names a version by its CID is
  stored unchanged.
- **Rebasing.** A protocol that needs another subject gets it from the
  application, by replacing the base IRI and its fragment IRIs on RDF terms as
  ni-rdf/1 §6 defines; the rebased content still verifies against the CID
  (ADR-0016). The library offers a `rebase` term operation for this; which
  module carries it is left to the implementation.
- **No resolution of a CID.** The read by CID works on the local content store
  only ([ADR-0019](0019-content-addressed-iri-as-ni-name.md)).
- Where `generateIri` refuses (an empty draft, a foreign subject in the slice,
  the cost limit), each operation states its behaviour in its API docs.
