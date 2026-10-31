# Design note: versioning (#105)

Status: historical background — superseded by [ADR-0021](../adr/0021-resource-versioning-module.md), [ADR-0022](../adr/0022-draft-publish-versioning-per-proxy-iri.md), [ADR-0023](../adr/0023-versioned-dataset-fixed-named-graphs.md) and the `rdf-versioning` module; where they differ, they win | Date: 2026-10-31 (first version 2026-10-02) | Issue: #105

This note was the agreed basis for implementing #105 (at the time of writing).
It collects the decisions, the reasoning behind them and what was still open. It is not an ADR: once the
implementation starts, each architecturally significant decision below becomes
its own ADR (one decision per record, see `docs/adr/README.md`), and this note is
then removed or kept as background.

Where it disagrees with the design comment on #105 from the same day, this note
wins (see [Changes against the #105 comment](#changes-against-the-105-comment)).

## Prerequisite

The CID as used here — content identity independent of the subject IRI — rests
on **ADR-0016** (issue #122, PR #123), which replaced the subject part of
ADR-0014. Under ADR-0014's original rule D7, D11 and the P2 invariant below
would not hold.

## Glossary

| Term | Meaning | Lives in (D4) | Subject there |
|---|---|---|---|
| **Resource** | Content identified by a subject IRI. Two kinds: draft and version. | | |
| **Proxy IRI** | The stable IRI callers use. A name only: it carries no content of its own and points at nothing by itself. Everything known about it is a triple with it as subject, spread over three graphs. | draft view, published view, history | — |
| **Draft** | A *mutable* resource: the working state. | draft view | proxy IRI |
| **Version** | An *immutable* resource. It has no history of its own. | content store | CID |
| **Current version** | The version named by the proxy IRI's latest history entry (the highest sequence number). There is no separate pointer; "current" is read off the history. Its content is additionally copied into the published view. | content store; copy in the published view | CID; proxy IRI for the copy |
| **History** | One entry per publish of a proxy IRI — sequence number, CID and the metadata of D8 — plus tombstones (D12), and the proxy IRI's revision counter (D10). Each entry has an IRI of its own, a `urn:uuid:` (Q12). | history | proxy IRI; entry IRI for the entries |
| **Publish** | Turning the draft's current content into a version and appending an entry to the proxy IRI's history. | | |
| **Sequence number** | Position of a publish in one proxy IRI's history; starts at 1, gapless. | | |
| **CID** | The RFC 6920 name `ni:///sha3-256;…` derived by `rdf-cid` from a resource's content, with the resource's own IRI (and its `#fragment` IRIs) replaced by a placeholder (ADR-0016, ADR-0019). | | |
| **Tag** | A named, immutable mapping proxy IRI → CID over many proxy IRIs: which version each of them meant at the time of the tag. | tag store | tag IRI |
| **Draft view, published view, content store, history, tag store, config graph** | The six kinds of named graph in D4. | | |

Interface and storage are separate concerns: callers address a draft and its
history through the proxy IRI, and one exact version through its CID; how
drafts, versions and history are kept apart is a storage matter (D4).

## Decisions

### D1 — Draft/publish per proxy IRI; the library owns the slice

A normal write changes the draft only; an explicit publish turns it into a
version. The unit is a **subject-bounded slice** of a shared named graph — every
triple whose subject is the proxy IRI or one of its `#fragment` IRIs, plus every
blank node reachable from those. A draft does not need a named graph of its own.

Fragment IRIs belong to the slice (added 2026-10-31). A sub-resource that must
stay addressable from outside — an acceptance criterion of a requirement, a step
of a use case — gets a fragment IRI of its proxy IRI (`ex:REQ-42#ac-1`) and is
versioned with it: a blank node could not be referenced, and a child with an IRI
of its own would be a resource of its own, which `generateIri` rejects inside
the slice (ni-rdf/1 §3, `FOREIGN_SUBJECT`). Membership follows the spec's rule:
the subject's base, compared as a string without normalization, is the proxy
IRI. A fragment subject needs no link from the proxy IRI — the spec treats every
IRI subject as a root (§3, reachability) — so an unlinked one is still part of
the slice and of the CID.

The library provides the slice operations — read, replace, delete, each
covering the fragment IRIs and the blank-node closure — and the caller always passes the proxy IRI
explicitly; the library never guesses the subject. A blank node reachable from
two proxy IRIs makes both slices ambiguous and is rejected on write. Drafts are
written through these operations, not around them, so that D3, D9 and D10 can
rely on the library having seen every write.

### D2 — Everything runs in the caller's transaction

Publish, tag and the slice writes take an open `DatasetTx` (ADR-0011) and never
open one of their own, so several proxy IRIs can be published atomically and an
aborted transaction leaves nothing behind. Every read-then-write guard (next
sequence number, unique tag name, concurrency token) reads through the same
`DatasetTx` (ADR-0008); a conflict is reported, and retrying is the caller's
decision.

### D3 — The draft stays after publish

After a successful publish the draft remains as the working state; at that
moment its content equals the new version. Whether a draft holds unpublished
changes is decided by deriving its CID and comparing it with the CID of the
latest history entry (D6 offers this as one read). That comparison holds within
one CID procedure only (Q11): when the entry was derived by another procedure
version than the current one, the library derives the CID of the stored
version's content under the current procedure and compares that instead. The
entry's name does not change.

- Publishing an unchanged draft creates **no** history entry and returns the
  current version.
- A → B → A creates three entries; the third names the same CID as the first,
  stored once.

### D4 — Storage: views as named graphs, in the same dataset

RDF's own mechanism for "same IRI, different statements" is the named graph.

| Graph | Subject | Content | Changes |
|---|---|---|---|
| **Draft view** (the caller's content graph) | proxy IRI | working state of every proxy IRI | on every write |
| **Published view** | proxy IRI | copy of the content of each proxy IRI's current version | slice replaced on publish |
| **Content store** | CID | every published content once, by CID, all in one graph; a version is its CID's slice | appended; erased only by D12 |
| **History** | proxy IRI | one entry per publish (D8); tombstones (D12) | appended |
| **Tag store** | tag IRI | tags (D9) | appended |
| **Config graph** | a fixed IRI of the library | settings of the versioned dataset: the recorded content graph | written on first use |

- A query chooses the state it wants by choosing the graph (`FROM` / `GRAPH`):
  draft view for the working state, published view for the published state.
  One exact version is addressed by its CID as subject in the content store.
- The content store is **one** named graph, not one graph per CID: a version is
  its CID's slice there (D1's slice operations, fragment IRIs and blank-node
  closure included).
  A graph per version would make every version a named graph of its
  own and fill the dataset's graph list with them.
- A versioned dataset has exactly one content graph, and so one draft view
  (D13).
- **Graph IRIs (Q4):** the five graphs of the library have fixed IRIs in a
  namespace of the library, so a caller cannot pass different names after a
  restart and scatter the data. The caller names only the content graph. The
  library records it in the config graph on first use, in the caller's
  transaction (D2), checks it on every later use and rejects a different IRI.
  The record has a graph of its own because every other graph holds subjects
  of its own kinds (proxy IRI and entry IRI, CID, tag IRI); a settings triple in one of them
  would be a subject of the wrong kind to anyone reading that graph, such as
  `tagPublished` collecting every proxy IRI from the history.
- The current version is the **latest history entry**, the one with the highest
  sequence number. The library keeps no separate "current" pointer: a pointer
  would have to be kept in step with the history on every publish and every
  forget, and it tells a reader nothing the entry does not.
- References between proxy IRIs (`ex:usesTerm <proxy-iri>`) need no
  translation: in the draft view they meet the target's draft, in the published
  view its published state.
- Because the draft stays (D3), the draft view is always complete; the library
  has no mixed "draft, else published" view.
- All six live in the **same dataset**, because D2's atomicity is per dataset.
  **Consequence:** a query without `FROM`/`GRAPH` runs over the union of all
  named graphs and sees duplicates (draft and published view) plus CID subjects.
  The existing content graph stays physically unchanged, but context-less reads
  of a versioned dataset are no longer meaningful; consumers name their graphs.
- Rejected alternative: materialize no published view and rebuild it from the
  content store on every read (the origin stack's approach). It has no drift
  problem (P2) but makes the published state unreachable for plain SPARQL.
- The published view duplicates the current state already held in the content
  store. Accepted for read simplicity.

What the store holds, by example — a SKOS concept `ex:baum` after two
publishes and one tag. Any resource works the same; `kv:` stands in for the
library's own history terms (the metadata use `dcterms`, Q7), prefixes are
omitted, CIDs and UUIDs shortened:

```turtle
# draft view (the caller's content graph): the working state
ex:baum  skos:prefLabel  "Baum"@de ;
         skos:definition "…" .

# published view: a copy of the current version's content, under the proxy IRI
ex:baum  skos:prefLabel  "Baum"@de ;
         skos:definition "…" .

# content store: every published content once, under its CID
<ni:///sha3-256;…4kq>  skos:prefLabel  "Baum"@de .
<ni:///sha3-256;…p2m>  skos:prefLabel  "Baum"@de ;
                       skos:definition "…" .

# history: one entry per publish, each with an IRI of its own (D8), plus the
# revision counter of D10; the highest sequence number is the current version
ex:baum    kv:entry  <urn:uuid:…a1> , <urn:uuid:…b2> ;
           kv:rev    5 .
<urn:uuid:…a1>  kv:seq 1 ; kv:cid <ni:///sha3-256;…4kq> ; kv:at "…" ; kv:proc "ni-rdf/1" .
<urn:uuid:…b2>  kv:seq 2 ; kv:cid <ni:///sha3-256;…p2m> ; kv:at "…" ; kv:proc "ni-rdf/1" .

# tag store: which version each proxy IRI meant when the tag was set
ex:tag-2026-Q4  kv:entry  [ kv:proxy ex:baum ; kv:cid <ni:///sha3-256;…p2m> ] .
```

`ex:baum` is the subject in three graphs and means something different in
each: content in the draft view, a copy of the current version's content in the
published view, bookkeeping in the history. The content store and the tag store
never carry a proxy IRI as subject.

### D5 — No IRI translation, no host normalization

The library stores the IRIs the caller supplies and never rewrites them, neither
in models nor in SPARQL text (the origin stack mapped public URLs to an internal
URN prefix by string replacement).

`rdf-cid` does **not** normalize hosts either: every term except the resource's
own IRI goes into the digest in full. Identical triples give an identical CID on
every instance; a version fetched from any server verifies against its CID. A
reference to `https://a.example/term/2` is part of the content — another
instance referring to its own `https://b.example/term/2` has different content
and gets a different CID, which is correct. Choosing stable IRIs is the
application's responsibility. (ADR-0016 already states this as a consequence;
ADR-0014's stated purpose and the `ContentAddressedIriGenerator` javadoc
promise more and should be clarified there.)

### D6 — Read semantics belong to the application

The library offers the states separately: read the draft; read the published
state; read a version by CID; read version *n* of a proxy IRI (the join over the
history, so no consumer has to write it); read the history; and read the
**publish state** of a draft (added 2026-10-03) with three outcomes — never
published, published without unpublished changes, published with unpublished
changes. The last is D3's comparison of the draft's CID with the latest history
entry's CID, offered as one read so that no consumer has to call `rdf-cid` and
walk the history itself; a triple-level comparison of draft view and published
view would be wrong, because blank-node labels may differ (P4). An empty draft
falls under P6; a forgotten latest version (D12) keeps its CID in its entry and
compares as usual. It does not decide what a reader "really" wants. A rule
like "a GET on the proxy IRI returns the draft if present, else the current
version" (as the origin stack implemented for ActivityPub) is an application
rule built on these reads.

Reading the history of several proxy IRIs in one call is a consumer wish
(arknet reads it once per resource today), not decided.

### D7 — A CID identifies content, not a proxy IRI

Two proxy IRIs published with identical content share one version — that is the
deduplication. Resolving a CID back to "its" proxy IRI is therefore not a
function; the direction that matters is proxy IRI → current CID. The library
offers no single-valued reverse lookup.

### D8 — History records publishes only

Per history entry: CID, sequence number, timestamp, the version of the CID
procedure that derived the CID (`ni-rdf/1`, Q11), optional creator, optional
comment.
Draft writes are **not** history entries; an application that needs a write
audit keeps it itself. (Recording draft writes as switchable history entries was
proposed and rejected.)

Each entry has an **IRI of its own**, not a blank node (added 2026-10-31): a
consumer attaches evidence to one publish — arknet links Git commits to it,
n:m — and a CID is no unique target for that (D7; A → B → A names one CID
twice). The IRI is a random `urn:uuid:` minted by the library on publish and
returned to the caller, so evidence can be attached at once; it is derived from
nothing, so it survives an export and restore into another installation
unchanged (Q12). The timestamp comes from the library through an injectable
clock; the creator is an optional IRI the caller passes; the metadata vocabulary
is `dcterms` (Q7).

### D9 — Tags

A tag is a named, immutable mapping proxy IRI → CID — a consistent state across
many proxy IRIs, comparable to a VCS tag. Release semantics, approval, naming
schemes and content diffs stay with the application.

- Two operations, deliberately not one with an optional argument:
  - `tag(name, Map<proxyIri, cid>)` — exactly these versions; older states can
    be tagged. Each CID must occur in that proxy IRI's history.
  - `tagPublished(name)` — every proxy IRI's current version, read in the same
    transaction.
- `tagPublished` **reports the proxy IRIs it passed over** — never published, or
  with unpublished draft changes — instead of leaving them out silently.
- One proxy IRI at most once per tag.
- Immutable; name unique per dataset; tags cannot be deleted (but see D12).
- RDF form: one tag IRI as subject, one blank node per entry carrying proxy IRI
  and CID — a shape `rdf-cid` accepts. The tag's CID is derived from that
  mapping alone; the name and the timestamp sit next to it in the tag store, not
  in the hashed content, so two tags over the same state share a CID.

### D10 — Optimistic concurrency token

The library checks a compare-and-set token on draft writes (D1) and on publish.
The token describes the state of the draft and history under the proxy IRI
as the writer read it. The draft's CID alone is not enough: a publish in
between leaves the draft's content as it is (D3), so a token that names only
the draft would not see it, and the publish guard needs the history position as
well.

The token is a **revision counter per proxy IRI** (Q3): the library raises it
on every draft write, publish and delete, and keeps it as one bookkeeping
triple in the history, beside the entries — it is not a history entry (D8).
The token the caller holds is the proxy IRI with the counter value, opaque to
the caller. A counter is always available, for an empty or a brand-new draft
too, where a token derived from the draft's content is not (P6: `generateIri`
refuses an empty draft and one over the cost limit) and would cost a
canonicalization per read and per check. It covers writes made through the
library only, which D1 requires anyway; a write past the slice operations is
not seen by the token, while D3 still sees the content change. Stated in the
API docs.

### D11 — Versions are delivered with their CID as subject; rebasing is a term operation

The library stores and returns a version with its CID as subject and never
alters its content. A protocol that needs a different subject — ActivityPub
requires HTTPS IRIs — gets it from the application, which replaces the
resource's own IRI. Because the CID ignores exactly that IRI and its fragments
(ADR-0016), the rebased content still verifies against the same CID.

The replacement must be done on RDF terms, never on text: replacing the own IRI
and its `#fragment` IRIs is safe; a string replace also hits literals and
unrelated IRIs that happen to contain the string, and changes the content. The
library offers `rebase(version, newBaseIri)` for this, so applications do not
write their own string replacement. The renaming is the one ni-rdf/1 §6
defines: the base becomes the new IRI and every fragment IRI `base#f` becomes
`new#f`, in every position. Under the CID itself that is `ni:///sha3-256;…#f`;
RFC 6920 defines no fragment, RFC 3986 allows one, and the digest contains no
`#`, so the base of such an IRI is the CID.

The library defines no resolution of a CID (ADR-0019): the `ni:` name names no
host, and the read by CID (D6) works on the local content store only. An
application that serves versions under RFC 6920's `/.well-known/ni/` serves the
hashed bytes there, or promises no verifiability. For ActivityPub, the rebased
HTTPS IRI is the object's `id`; the CID can travel alongside in `alsoKnownAs`,
as w3c/activitypub#573 documents for alternative identifiers.

### D12 — Deletion

Personal data must be erasable; immutability and deduplication must not prevent
it.

- **Forgetting a version:** its content is removed from the content store and,
  if it is current, from the published view. The history entry stays with its
  CID and an *erased* marker. Tags keep their entries and resolve them as
  *erased*. The CID does not show the content, but it confirms a guess: whoever
  can enumerate the candidates (a birth date, a yes/no answer) derives the CID
  of each and compares. For guessable content, the CID left in the history and
  the tags is itself a remnant of the erased data, inside this dataset: what
  forgetting promises is "content removed, CID kept".
- **Erasure ends at the dataset (ADR-0019).** Forgetting removes the content
  from this dataset, not from copies elsewhere. Anyone holding a copy can show,
  after the erasure, that it is exactly the content a still-circulating CID
  names; a hash binds no author, but it binds the content. Applications must
  not promise data subjects more than removal from their own store.
- A version shared by several proxy IRIs (D7) is erased for all of them. Personal
  data has to go everywhere; this is intended, not reference-counted.
- **Deleting a proxy IRI:** its draft slice is removed and the history records a
  tombstone; history and versions stay unless forgotten explicitly. The
  tombstone names the CID of the last version, so what was deleted stays
  readable (a consumer need, kogn-io/arknet#596). A proxy IRI that was never
  published has no version to name: its tombstone records the fact alone.
- Forgetting a version is a separate operation from deleting a draft or a proxy IRI;
  neither implies the other. An empty draft is not a deletion.
- Operations that clear whole graphs must not destroy versions as a side effect.

### D13 — A new backend-free module, switched on per dataset

Versioning lives in a new module, `rdf-versioning`, that depends on
`rdf-dataset` and `rdf-cid` only — no RDF4J, no hosting layer. Everything it
needs (adding, removing and exporting per named graph, SPARQL, `contains`) is on
`DatasetTx` already, so it is plain logic over existing ports and runs on any
`DatasetTx`, including one over a repository the caller supplies. Tying it to
the hosting layer's `DatasetStoreConfig` would shut those callers out. Port and
its only implementation share the module, as in `rdf-cid` and `rdf-shacl`.

Versioning is switched on **per dataset**, and a versioned dataset has
**exactly one content graph**. There is one set of the library's graphs per
dataset; tag names are unique per dataset and `tagPublished` covers every proxy
IRI in it — the same unit as D2's transaction. Rejected: switching it on per content
graph, which would scope tags and deduplication to part of a dataset and need
one set of library graphs per content graph.

## Pitfalls to address in the implementation

- **P1 — Time consistency of references.** A version containing
  `ex:usesTerm <proxy-iri>` refers to the proxy IRI, not to a version of the target;
  read later, it meets the target's *current* state. Only a tag gives a
  time-consistent picture: inside a tag, proxy IRIs resolve through the tag's
  mapping. References by hash (as in Trusty URIs) would force cascading
  re-hashing on every change. Decision: the library never turns a proxy IRI
  reference into a CID reference; time consistency is a tag property. State it
  in the API docs. Any resolution of references (following IRIs into other
  resources) runs against one fixed view.
  A reference may still name one version on purpose, by its CID
  (`ex:refersTo <ni:///sha3-256;…>`). The library stores it unchanged (D5) and
  does not normalize it: another spelling of the same name
  (`ni://host/sha3-256;…`, a `?ct=` parameter) is the caller's to bring into
  the canonical form before writing, as ADR-0019 requires of a consumer; the
  read by CID (D6) takes the canonical form only. It enters
  the referring resource's CID in full, since only the base IRI becomes a
  placeholder (ADR-0016), and it cascades nothing: it changes only when its
  author points it at another version. It means the same in every view, needs
  no tag to stay time-consistent and resolves through the read by CID (D6) when
  the version is held locally; otherwise resolution is the application's (D11).
  Once that version is forgotten (D12), the reference finds no content; the
  history entry shows the CID as *erased*. The library does not check on write
  that such a CID occurs in the content store (Q10): it stores the IRIs it is
  given (D5); a CID may name a version held by another server of a federation,
  so its absence here is no error; `ni:` is a general scheme that names any
  bytes, so the scheme shows a hash, not that a version is meant; and a check
  would not survive D12, which can erase a referenced version later.
- **P2 — Drift between views.** Invariant: the published view equals the
  current version's content with the CID replaced by the proxy IRI. Pin it with
  a test.
- **P3 — Atomicity across graphs.** One publish touches the published view, the
  content store and the history; a tag touches the tag store. Pin with a test
  that an aborted `DatasetTx` leaves all of them unchanged.
- **P4 — Blank nodes and deduplication.** The CID canonicalizes blank nodes, the
  store keeps whatever labels it is given; "store once per CID" therefore needs a
  `contains` check by CID, not a triple-level comparison. Decided with Q2: blank
  nodes stay blank nodes.
- **P5 — Growth.** Every distinct version is stored in full. Unproblematic for
  small resources; measure before using it on large graphs.
- **P6 — `generateIri` can refuse.** Publish, the D3 change check and
  `tagPublished` call `generateIri` with the proxy IRI as base (ADR-0017), which
  rejects an empty draft or a slice with a subject of another resource
  (IllegalArgumentException) and can exceed the
  canonicalizer's resource limit for highly symmetric blank-node structures.
  Each operation states what it does in these cases.

## Open questions

- **Q2 — Blank nodes in the content store:** answered 2026-10-31 — stored as
  blank nodes; their labels are the store's and not stable across reads, which
  the API docs state. Skolemizing would break the verification ADR-0016 is for:
  an IRI under the CID (`ni:…#b1`) enters the digest as a fragment, a foreign
  one (`/.well-known/genid/…`) is rejected by `generateIri`, so the stored
  version would no longer derive its own CID. Deduplication and forgetting work
  by the CID as subject plus the blank-node closure (P4). arknet writes no
  blank nodes, so the choice does not touch the first consumer.
- **Q3 — Concurrency token shape:** answered 2026-10-31 — a revision counter
  per proxy IRI, raised on every draft write, publish and delete, opaque to the
  caller (D10). The draft's CID alone misses a publish in between, and a token
  derived from content is unavailable for exactly the drafts `generateIri`
  refuses (P6). arknet keeps its token server-side and passes none to a client.
- **Q4 — Graph IRIs** of the library's graphs: answered by D4 — fixed IRIs of
  the library; the content graph is recorded in a config graph and checked.
- **Q5 — Export/import:** all graph kinds must travel with a dataset export, or
  history is lost on re-import. There is no import port yet, and
  `DatasetExport` is not transactional (ADR-0013), so a multi-graph export is
  not a consistent snapshot. Narrowed 2026-10-31: the export side is covered,
  a dataset export over all graphs takes the library's graphs along (arknet
  exports that way today); the import port is missing on both sides and stays
  out of the first cut.
- **Q6 — Where versioning lives:** answered by D13.
- **Q7 — Provenance vocabulary** for history entries: answered 2026-10-31 —
  `dcterms`; no consumer asks for PROV-O, the history only has to stay mappable
  to it. The timestamp comes from the library through an injectable clock, the
  creator is an optional IRI from the caller (D8).
- **Q8 — Migration:** turning an unversioned dataset into a versioned one (an
  initial bulk publish?), and data from the origin stack, whose identifiers are
  not compatible with the `ni:` names of ADR-0019 and have to be re-derived.
  Answered for arknet 2026-10-31: an initial bulk publish of the current state.
  Its revision chain so far never carried content, so there is no version per
  old revision; the chain stays on arknet's side or goes, arknet's call. The
  origin stack's data remains open.
- **Q9 — Sub-resources with identity of their own** (child IRIs under a
  resource): answered 2026-10-31 by D1 — a sub-resource that stays addressable
  is a fragment IRI of its parent and is versioned with it; a child with an IRI
  of its own is a resource of its own.
- **Q10 — CID references on write:** answered: not checked, see P1.
- **Q11 — A change of the CID procedure:** "same content, same name" holds
  within one procedure version only; the version sits in the hashed bytes, not
  in the name (ADR-0019). Once an ni-rdf/2 exists, D3's comparison of the
  draft's CID with the latest entry's CID compares a v2 name against a v1 name
  and reports a change where the content did not change — an unneeded version
  per resource in every installation, and a publish-state read that is wrong
  until then. Answered 2026-10-31: every history entry records the procedure
  version that derived its CID (D8); on a mismatch D3 derives the stored
  version's content under the current procedure and compares that. Names once
  given are never rewritten — they circulate in CID references (P1), tags and
  copies elsewhere (ADR-0019). The mismatch path is built once a second
  procedure exists; what is needed now is the one literal per entry, and
  `rdf-cid` exposing its procedure tag as a value. Not covered by Q8, which is
  about data that was never versioned.
- **Q12 — The history entry's IRI:** answered 2026-10-31 — a random `urn:uuid:`
  minted by the library on publish (D8). Globally unique across installations,
  which arknet needs because exports are restored elsewhere; dereferenceable it
  need not be. Rejected: a fragment of the proxy IRI (part of the content slice
  since D1, so it would be hashed), a fragment of the CID (not unique, A → B → A
  names one CID twice, D7), and an IRI derived from proxy IRI and sequence
  number (reproducible, but a translation of the proxy IRI's spelling, D5, that
  nobody asked for).

Out of scope: SHACL validation before publish is the application's business.

## First consumer: arknet (2026-10-31)

arknet (kogn-io/arknet) keeps its architecture model in a kognio-rdf dataset
and decides in kogn-io/arknet#440 whether its revision bookkeeping moves onto
this design. What it needs, stated from there and settled by the maintainer,
fixes the first cut:

- Draft/publish as in D1, D3 and D6. Publish is an explicit act, decoupled
  from arknet's own status of a requirement, often over several proxy IRIs in
  one transaction (D2). A version per write was considered there and rejected.
- One content graph per dataset (D13) is enough: arknet merges its eight model
  graphs per project into one.
- Sub-resources (acceptance criteria, use-case steps, ADR options) become
  fragment IRIs of their parent (D1, Q9); today they are IRIs of their own,
  minted anew on every write.
- History entries with an IRI each (D8, Q12), `dcterms` metadata (Q7).
- Tombstones (D12): what was deleted must stay visible. Forgetting a version
  is not needed in the first cut.
- The concurrency token replaces arknet's head-pointer compare-and-set; it stays
  server-side, no client passes one (Q3).
- Migration: a bulk publish of the current state (Q8).
- SHACL before the write, the diff of two versions and any PROV-O view stay in
  arknet; arknet does not use `rdf-cid` directly today.

Not in the first cut: tags (D9 — a baseline is a tag, and arknet's release and
milestone have no write path yet; the history must not do anything that rules
tags out later), forgetting a version, an import port (Q5). The reads by CID
and of version *n* (D6) stay in: the "version *n*" read is the join the history
needs anyway.

The first cut, then: the slice operations, publish (several proxy IRIs
atomically), the history with IRI entries, the publish-state read, the reads of
D6, tombstones, the concurrency token.

## Changes against the #105 comment

- **Draft after publish:** the comment says it is deleted; replaced by D3 — it
  stays.
- **Wording:** the comment says "resource" where this note says proxy IRI,
  including "Draft/publish per resource", "gapless per resource" and
  "resource → CID" for tags. The glossary above is authoritative.
- **Tag signature:** the comment's `tag(name, cids)` becomes
  `tag(name, Map<proxyIri, cid>)`, because a CID does not determine its proxy
  IRI (D7). "Two CIDs of the same resource" becomes "one proxy IRI at most once".
- **Concurrency token:** the comment reasoned that "the draft disappears on
  publish"; with D3 it does not, and D10 is phrased accordingly.
- New since the comment: the prerequisite, D1 (slice operations), D4–D7,
  D11–D13, P1–P6, Q2–Q9.

## Changes on 2026-10-03

From the review of the tutorial built on this note:

- **Graph names:** *version store* → **content store**, *proxy graph* →
  **history**. The old names pointed the wrong way: the graph called "version
  store" holds content addressed by CID and no list of versions, while the list
  of a proxy IRI's versions sat in the graph called "proxy graph". In VCS terms
  the content store is the object store and the history is refs plus log.
- **No current pointer:** the current version is the latest history entry (D4,
  glossary). The earlier "pointer to the current CID; pointer replaced" is gone.
- **Glossary** gains the graph each term lives in and the subject it has there,
  and the entry *history*; *proxy IRI* is spelled out so that it does not
  read as a thing that points or holds state by itself, and *proxy* is
  dropped — the text says proxy IRI, draft or history.
- **D4** gains a worked example of the five graphs for one resource.
- **D6** gains the publish-state read.
- **Addressing:** a version is addressed by its CID, not only through the
  proxy IRI; P1 states that content may reference a version by CID on
  purpose, and Q10 asks whether such references are checked.

Following ADR-0019 (the CID becomes an RFC 6920 `ni:` name):

- **CID format:** `urn:cid:` → `ni:///sha3-256;…` in the glossary, the D4
  example, P1 and Q8; the D4 example's tag IRI no longer uses the `tag:` scheme.
- **Resolution:** D11 states that the library defines none and how
  `/.well-known/ni/` and ActivityPub relate to it.
- **D12** corrects "a CID reveals nothing about the content" — it confirms a
  guess — and states that erasure ends at the dataset: copies stay provable.
- **Q10** answered: CID references are not checked on write (P1).
- **Prior art** gains pukkamustard's content-addressable RDF and ERIS.

Q4 answered:

- **D4** fixes the IRIs of the library's graphs and gains a sixth graph, the
  config graph, which records the content graph; the glossary follows.

## Changes on 2026-10-31

From the first consumer's answer (section *First consumer: arknet*):

- **D1:** the slice includes the proxy IRI's `#fragment` IRIs, with the spec's
  membership rule; **Q9** answered by it; D4 and D11 follow (renaming of
  fragment IRIs under the CID, ni-rdf/1 §6).
- **D8:** every history entry has an IRI of its own; the D4 example and the
  glossary show it; new **Q12** asks how it is minted.
- **D10** and **Q3:** the draft's CID alone misses a publish in between.
- **D12:** the tombstone names the last version's CID; a never-published proxy
  IRI gets a tombstone without one.
- **Q7** answered (`dcterms`, clock in the library), **Q8** answered for arknet
  (bulk publish), **Q5** narrowed (export covered, import deferred).
- **D6** notes the wish for a history read over several proxy IRIs.
- New **Q11:** a change of the CID procedure makes D3 report a change where the
  content did not change.

Decided the same day, after two facts from arknet (it writes no blank nodes;
entry IRIs must stay unique across installations):

- **Q2:** blank nodes are stored as blank nodes; skolemizing would break the
  CID verification. P4 follows.
- **Q3:** the token is a revision counter per proxy IRI, opaque; D10 states it
  and why a content-derived token fails on the drafts `generateIri` refuses.
- **Q11:** every history entry records its CID procedure version; D3 and D8
  follow.
- **Q12:** the entry IRI is a random `urn:uuid:` minted by the library; D8, the
  D4 example and the glossary follow.
- Status line: questions answered, next step the ADRs. Still deferred: the
  import port (Q5), the origin stack's data (Q8), tags and forgetting for the
  first cut.

## Prior art

- Trusty URIs — self-reference placeholder before hashing, the same mechanism as
  our CID: https://arxiv.org/pdf/1401.5775
- pukkamustard, "Content-addressable RDF" (openEngiadina, 2020) — the fragment
  graph of a base IRI hashed as canonical S-expressions without the base, the
  closest predecessor of our CID; ADR-0019 lists where we differ:
  https://openengiadina.net/papers/content-addressable-rdf.html
- ERIS — encrypted, content-addressed block storage for byte streams under
  `urn:eris:`; a different layer, a possible store beneath our CIDs, not an
  alternative: https://eris.codeberg.page/spec/
- R43ples — full current state under the original graph IRI plus revisions and
  history in a separate graph, structurally close to D4:
  https://ceur-ws.org/Vol-1215/paper-03.pdf
- Memento (RFC 7089) — URI-R / URI-M / TimeMap map onto proxy IRI / CID /
  history; says nothing about storage and leaves link time-consistency open:
  https://www.rfc-editor.org/rfc/rfc7089, https://arxiv.org/html/1003.3661v1
- Quit Store — git-based snapshots per graph; reports storage growth and
  blank-node difficulties: https://github.com/AKSW/QuitStore,
  https://arxiv.org/pdf/1805.03721
- OSTRICH — hybrid snapshot/delta/timestamp storage engine, for comparison only:
  https://github.com/rdfostrich/ostrich

Not reviewed: TailR, the W3C versioning discussions, Solid.
