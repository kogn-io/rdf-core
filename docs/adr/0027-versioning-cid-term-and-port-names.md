# ADR-0027: The stored term of a history entry's CID is `versioning:cid`; the port names follow what they carry; corrects ADR-0024

Status: Accepted (2026-11-19) — corrects the name of the stored term
`versioning:content` in [ADR-0024](0024-versioning-fragment-index-and-corrections.md)

## Context

ADR-0024 defines `versioning:versionFragment` by the CID "in the entry's
`versioning:content`", and `VocabVersioning` promises that names are added and
never renamed, because stored data binds them (ADR-0023). The term is the CID of
the version an entry names, an `ni:` IRI. "Content" already names the caller's
content graph and the content store, and in the Java port `HistoryEntry.content()`
returned an IRI where a reader expects a graph.

The same review found names in the port that say something other than what they
carry: `readVersion(tx, cid)` next to `readVersion(tx, proxyIRI, sequence)`
silently returned empty for a proxy IRI passed as a CID; `PublishDetails` is also
the metadata of `delete`; `PublishResult` held two parallel maps; `readSlice` and
`replaceSlice` did not say which slice.

The module has not been released, so no stored dataset and no consumer uses these
names yet. This is the last point at which the stored term can change without a
data migration.

## Decision

- The property that names the CID of the version a history entry records is
  `versioning:cid` (`https://w3id.org/kognio/rdf/versioning#cid`, constant
  `VocabVersioning.CID`). It replaces `versioning:content` wherever ADR-0024
  names the latter; the rest of ADR-0024 stands, including that a change of the
  vocabulary is a change of the stored format.
- The Java names follow: `HistoryEntry.cid()`, `readVersionByCID` (rejects an IRI
  that is not an `ni:` IRI), `EntryDetails` for the metadata of an entry (publish
  and delete), `readDraft`/`replaceDraft` with the parameter `triples`,
  `PublishResult(Map<IRI, EntryResult>)` with `EntryResult(entryIRI, token)`, and
  `DeleteResult(entryIRI, token)`. Acronyms are capitals (`proxyIRI`, `entryIRI`).

## Consequences

- Breaking for the API and for the stored format of a dataset written with
  `versioning:content`; none exists outside tests, so no migration is provided.
  A dataset written later with `versioning:content` would need its triples
  rewritten.
- `readVersionByCID(tx, proxyIRI)` fails loudly instead of returning empty.
- ADR-0024 stays as written and points here from its status line; this ADR is
  the correction.
