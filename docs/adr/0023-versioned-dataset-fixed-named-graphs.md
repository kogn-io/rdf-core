# ADR-0023: A versioned dataset keeps views, versions and history as fixed named graphs in one dataset

Status: Accepted (2026-10-31) — accepted with the merge of the implementation (#201–#206)

## Context

[ADR-0022](0022-draft-publish-versioning-per-proxy-iri.md) fixes what the
versioning port promises; this record fixes how a versioned dataset holds the
data. Consumers bind that format directly, by naming graphs in SPARQL
(`FROM` / `GRAPH`), so changing it later breaks stored data, not only callers.
The values are those of the [design note](../design/105-versioning.md) for
issue [#105](https://github.com/kogn-io/rdf-core/issues/105) (D4, D8, D13,
Q2).

The same proxy IRI stands for different statements depending on the state a
reader wants: the working state, the published state, bookkeeping. RDF's own
mechanism for "same IRI, different statements" is the named graph. A version
has the CID as subject, which keeps it apart from either state.

The graphs must live in one dataset, because the atomicity of a publish is that
of one `DatasetTx`, and a transaction spans one dataset. They need fixed IRIs:
names a caller passes could differ after a restart and scatter the data.

A graph per version would make every version a named graph and fill the
dataset's graph list with them. A "current" pointer beside the history would
have to be kept in step with it on every publish and tell a reader nothing the
latest entry does not.

Blank nodes in a version could be skolemized for stable addressing, but an IRI
under the CID enters the digest as a fragment, and a foreign one is rejected by
`generateIri`; a stored version would no longer derive its own CID
([ADR-0016](0016-content-addressed-iri-self-placeholder.md)).

## Decision

- A versioned dataset keeps, as named graphs in that one dataset: the **draft
  view** (the caller's content graph), the **published view**, the **content
  store** (one graph; a version is its CID's slice there), the **history** and
  a **config graph**. There is one such set per dataset, and the library's
  graphs have fixed IRIs of the library.
- The caller names only the content graph. The library records it in the config
  graph on first use and checks it on every later use; a different IRI is
  rejected.
- The current version is the history entry with the highest sequence number;
  there is no separate current pointer.
- Blank nodes stay blank nodes. Each content is stored once per CID, decided by
  `contains` on the CID as subject, not by comparing triples.
- Further graphs are added beside these, without changing them.

## Consequences

- A query picks the state by picking the graph: the draft view for the working
  state, the published view for the published state, the content store with
  the CID as subject for one exact version. References between proxy IRIs need
  no translation — in each view they meet the target in the same state.
- The published view holds a copy of each proxy IRI's current version under the
  proxy IRI, replaced on publish; it duplicates what the content store holds,
  accepted for read simplicity. Invariant: it equals the current version's
  content with the CID replaced by the proxy IRI.
- Because the draft stays (ADR-0022), the draft view is always complete; there
  is no mixed "draft, else published" view.
- A query without `FROM` / `GRAPH` on a versioned dataset runs over the union
  of all named graphs and sees duplicates plus CID subjects. The content graph
  itself stays physically unchanged, but context-less reads of a versioned
  dataset are no longer meaningful; consumers name their graphs.
- Versioning is switched on per dataset, and a versioned dataset has exactly
  one content graph. Deduplication covers the whole dataset, the same unit as
  the transaction.
- **History form.** Each entry is a subject of its own with a random
  `urn:uuid:` IRI minted by the library on publish. Sequence number (from 1,
  gapless), CID and the procedure version that derived the CID (`ni-rdf/1`) are
  terms of the library's own vocabulary. The metadata — timestamp, and
  optionally a creator IRI and a comment — use `dcterms`. Being derived from
  nothing, the entry IRI survives an export and a restore into another
  installation unchanged.
- The revision counter of ADR-0022 is one bookkeeping triple per proxy IRI in
  the history, beside the entries; it is not an entry.
- Blank-node labels in the content store are the store's and not stable across
  reads; the API docs state it.
- The config graph exists because every other graph holds subjects of its own
  kinds; a settings triple in one of them would be a subject of the wrong kind
  to anyone reading that graph.
- A dataset export over all graphs takes the library's graphs along, but it is
  no consistent snapshot ([ADR-0013](0013-standalone-dataset-export-port.md)).
  An import port does not exist yet.
- This record fixes only that the graph IRIs are fixed and the library's; the
  concrete namespace and IRIs are `VocabVersioning` and `VersioningGraphs` in
  `rdf-versioning`. *Corrected when accepted: the record said they
  were chosen before the implementation.*
- Every distinct version is stored in full. Unproblematic for small resources;
  to be measured before use on large graphs.
