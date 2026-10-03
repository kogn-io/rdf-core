# Design note: versioning (#105)

Status: agreed basis, open questions below | Date: 2026-10-03 (first version 2026-10-02) | Issue: #105

This note is the agreed basis for implementing #105. It collects the decisions,
the reasoning behind them and what is still open. It is not an ADR: once the
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
| **History** | One entry per publish of a proxy IRI — sequence number, CID and the metadata of D8 — plus tombstones (D12). | history | proxy IRI |
| **Proxy** | Shorthand for everything recorded under one proxy IRI, its draft and its history, used in phrases like "per proxy". Not a resource and not a graph. | draft view, history | proxy IRI |
| **Publish** | Turning the draft's current content into a version and appending an entry to the proxy IRI's history. | | |
| **Sequence number** | Position of a publish in one proxy IRI's history; starts at 1, gapless. | | |
| **CID** | `urn:cid:` derived by `rdf-cid` from a resource's content, with the resource's own IRI (and its `#fragment` IRIs) replaced by a placeholder (ADR-0016). | | |
| **Tag** | A named, immutable mapping proxy IRI → CID over many proxy IRIs: which version each of them meant at the time of the tag. | tag store | tag IRI |
| **Draft view, published view, content store, history, tag store** | The five kinds of named graph in D4. | | |

Interface and storage are separate concerns: callers address everything through
the proxy IRI; how drafts, versions and history are kept apart is a storage
matter (D4).

## Decisions

### D1 — Draft/publish per proxy; the library owns the slice

A normal write changes the draft only; an explicit publish turns it into a
version. The unit is a **subject-bounded slice** of a shared named graph — the
draft's subject plus every blank node reachable from it. A draft does not need a
named graph of its own.

The library provides the slice operations — read, replace, delete, each
including the blank-node closure — and the caller always passes the proxy IRI
explicitly; the library never guesses the subject. A blank node reachable from
two proxy IRIs makes both slices ambiguous and is rejected on write. Drafts are
written through these operations, not around them, so that D3, D9 and D10 can
rely on the library having seen every write.

### D2 — Everything runs in the caller's transaction

Publish, tag and the slice writes take an open `DatasetTx` (ADR-0011) and never
open one of their own, so several proxies can be published atomically and an
aborted transaction leaves nothing behind. Every read-then-write guard (next
sequence number, unique tag name, concurrency token) reads through the same
`DatasetTx` (ADR-0008); a conflict is reported, and retrying is the caller's
decision.

### D3 — The draft stays after publish

After a successful publish the draft remains as the working state; at that
moment its content equals the new version. Whether a draft holds unpublished
changes is decided by deriving its CID and comparing it with the CID of the
latest history entry (D6 offers this as one read).

- Publishing an unchanged draft creates **no** history entry and returns the
  current version.
- A → B → A creates three entries; the third names the same CID as the first,
  stored once.

### D4 — Storage: views as named graphs, in the same dataset

RDF's own mechanism for "same IRI, different statements" is the named graph.

| Graph | Subject | Content | Changes |
|---|---|---|---|
| **Draft view** (the caller's content graph) | proxy IRI | working state of every proxy | on every write |
| **Published view** | proxy IRI | copy of the content of each proxy's current version | slice replaced on publish |
| **Content store** | CID | every published content once, by CID, all in one graph; a version is its CID's slice | appended; erased only by D12 |
| **History** | proxy IRI | one entry per publish (D8); tombstones (D12) | appended |
| **Tag store** | tag IRI | tags (D9) | appended |

- A query chooses the state it wants by choosing the graph (`FROM` / `GRAPH`):
  draft view for the working state, published view for the published state.
  One exact version is addressed by its CID as subject in the content store.
- The content store is **one** named graph, not one graph per CID: a version is
  its CID's slice there (D1's slice operations, blank-node closure included).
  A graph per version would make every version a named graph of its
  own and fill the dataset's graph list with them.
- A versioned dataset has exactly one content graph, and so one draft view
  (D13).
- The current version is the **latest history entry**, the one with the highest
  sequence number. The library keeps no separate "current" pointer: a pointer
  would have to be kept in step with the history on every publish and every
  forget, and it tells a reader nothing the entry does not.
- References between proxy IRIs (`ex:usesTerm <proxy-iri>`) need no
  translation: in the draft view they meet the target's draft, in the published
  view its published state.
- Because the draft stays (D3), the draft view is always complete; the library
  has no mixed "draft, else published" view.
- All five live in the **same dataset**, because D2's atomicity is per dataset.
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
history vocabulary (Q7), prefixes are omitted and CIDs shortened:

```turtle
# draft view (the caller's content graph): the working state
ex:baum  skos:prefLabel  "Baum"@de ;
         skos:definition "…" .

# published view: a copy of the current version's content, under the proxy IRI
ex:baum  skos:prefLabel  "Baum"@de ;
         skos:definition "…" .

# content store: every published content once, under its CID
<urn:cid:…4kq>  skos:prefLabel  "Baum"@de .
<urn:cid:…p2m>  skos:prefLabel  "Baum"@de ;
                skos:definition "…" .

# history: one entry per publish; the highest sequence number is the current version
ex:baum  kv:entry  [ kv:seq 1 ; kv:cid <urn:cid:…4kq> ; kv:at "…" ] ,
                   [ kv:seq 2 ; kv:cid <urn:cid:…p2m> ; kv:at "…" ] .

# tag store: which version each proxy IRI meant when the tag was set
<tag:2026-Q4>  kv:entry  [ kv:proxy ex:baum ; kv:cid <urn:cid:…p2m> ] .
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
state; read a version by CID; read version *n* of a proxy (the join over the
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

### D7 — A CID identifies content, not a proxy

Two proxies with identical content share one version — that is the
deduplication. Resolving a CID back to "its" proxy IRI is therefore not a
function; the direction that matters is proxy IRI → current CID. The library
offers no single-valued reverse lookup.

### D8 — History records publishes only

Per proxy: CID, sequence number, timestamp, optional creator, optional comment.
Draft writes are **not** history entries; an application that needs a write
audit keeps it itself. (Recording draft writes as switchable history entries was
proposed and rejected.)

### D9 — Tags

A tag is a named, immutable mapping proxy IRI → CID — a consistent state across
many proxies, comparable to a VCS tag. Release semantics, approval, naming
schemes and content diffs stay with the application.

- Two operations, deliberately not one with an optional argument:
  - `tag(name, Map<proxyIri, cid>)` — exactly these versions; older states can
    be tagged. Each CID must occur in that proxy's history.
  - `tagPublished(name)` — every proxy's current version, read in the same
    transaction.
- `tagPublished` **reports the proxies it passed over** — never published, or
  with unpublished draft changes — instead of leaving them out silently.
- One proxy IRI at most once per tag.
- Immutable; name unique per dataset; tags cannot be deleted (but see D12).
- RDF form: one tag IRI as subject, one blank node per entry carrying proxy IRI
  and CID — a shape `rdf-cid` accepts. The tag's CID is derived from that
  mapping alone; the name and the timestamp sit next to it in the tag store, not
  in the hashed content, so two tags over the same state share a CID.

### D10 — Optimistic concurrency token

The library checks a compare-and-set token on draft writes (D1) and on publish.
The token describes the proxy's state as the writer read it. Shape open (Q3).

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
write their own string replacement.

### D12 — Deletion

Personal data must be erasable; immutability and deduplication must not prevent
it.

- **Forgetting a version:** its content is removed from the content store and,
  if it is current, from the published view. The history entry stays with its
  CID and an *erased* marker — a CID reveals nothing about the content. Tags
  keep their entries and resolve them as *erased*.
- A version shared by several proxies (D7) is erased for all of them. Personal
  data has to go everywhere; this is intended, not reference-counted.
- **Deleting a proxy:** its draft slice is removed and the history records a
  tombstone; history and versions stay unless forgotten explicitly.
- Forgetting a version is a separate operation from deleting a draft or a proxy;
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
in it — the same unit as D2's transaction. Rejected: switching it on per content
graph, which would scope tags and deduplication to part of a dataset and need
one set of library graphs per content graph.

## Pitfalls to address in the implementation

- **P1 — Time consistency of references.** A version containing
  `ex:usesTerm <proxy-iri>` refers to the proxy, not to a version of the target;
  read later, it meets the target's *current* state. Only a tag gives a
  time-consistent picture: inside a tag, proxy IRIs resolve through the tag's
  mapping. References by hash (as in Trusty URIs) would force cascading
  re-hashing on every change. Decision: references stay proxy IRIs; time
  consistency is a tag property. State it in the API docs. Any resolution of
  references (following IRIs into other resources) runs against one fixed view.
- **P2 — Drift between views.** Invariant: the published view equals the
  current version's content with the CID replaced by the proxy IRI. Pin it with
  a test.
- **P3 — Atomicity across graphs.** One publish touches the published view, the
  content store and the history; a tag touches the tag store. Pin with a test
  that an aborted `DatasetTx` leaves all of them unchanged.
- **P4 — Blank nodes and deduplication.** The CID canonicalizes blank nodes, the
  store keeps whatever labels it is given; "store once per CID" therefore needs a
  `contains` check by CID, not a triple-level comparison. Decide together with
  Q2.
- **P5 — Growth.** Every distinct version is stored in full. Unproblematic for
  small resources; measure before using it on large graphs.
- **P6 — `generateIri` can refuse.** Publish, the D3 change check and
  `tagPublished` call `generateIri` with the proxy IRI as base (ADR-0017), which
  rejects an empty draft or a slice with a subject of another resource
  (IllegalArgumentException) and can exceed the
  canonicalizer's resource limit for highly symmetric blank-node structures.
  Each operation states what it does in these cases.

## Open questions

- **Q2 — Blank nodes in the content store:** stored as is, or skolemized?
- **Q3 — Concurrency token shape:** a counter on the proxy, the draft's CID, or
  something else?
- **Q4 — Graph IRIs** of the published view, content store, history and tag
  store. Settled: one set per dataset (D13) and one graph for all versions (D4).
  Open: who names them. Proposal: fixed IRIs in a namespace of the library, so
  a caller cannot pass different names after a restart and scatter the data;
  the caller names only the content graph, which the library records on first
  use and checks on every later one, rejecting a different IRI.
- **Q5 — Export/import:** all graph kinds must travel with a dataset export, or
  history is lost on re-import. There is no import port yet, and
  `DatasetExport` is not transactional (ADR-0013), so a multi-graph export is
  not a consistent snapshot.
- **Q6 — Where versioning lives:** answered by D13.
- **Q7 — Provenance vocabulary** for history entries (`dct:` or PROV-O), and
  whether the timestamp comes from the caller or the library.
- **Q8 — Migration:** turning an unversioned dataset into a versioned one (an
  initial bulk publish?), and data from the origin stack, whose identifiers are
  not compatible with `urn:cid:` and have to be re-derived.
- **Q9 — Sub-resources with identity of their own** (child IRIs under a
  resource): parked; returns once stable child identifiers are needed.

Out of scope: SHACL validation before publish is the application's business.

## Changes against the #105 comment

- **Draft after publish:** the comment says it is deleted; replaced by D3 — it
  stays.
- **Wording:** the comment says "resource" where this note says proxy IRI or
  proxy, including "Draft/publish per resource", "gapless per resource" and
  "resource → CID" for tags. The glossary above is authoritative.
- **Tag signature:** the comment's `tag(name, cids)` becomes
  `tag(name, Map<proxyIri, cid>)`, because a CID does not determine its proxy
  (D7). "Two CIDs of the same resource" becomes "one proxy IRI at most once".
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
  and the entry *history*; *proxy IRI* and *proxy* are spelled out so that
  neither reads as a thing that points or holds state by itself.
- **D4** gains a worked example of the five graphs for one resource.
- **D6** gains the publish-state read.

## Prior art

- Trusty URIs — self-reference placeholder before hashing, the same mechanism as
  our CID: https://arxiv.org/pdf/1401.5775
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
