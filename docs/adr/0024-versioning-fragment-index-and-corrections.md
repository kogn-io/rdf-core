# ADR-0024: The fragment index is part of the stored format of a versioned dataset; corrections to ADR-0022 and ADR-0023

Status: Accepted (2026-11-18) — corrects statements of
[ADR-0022](0022-draft-publish-versioning-per-proxy-iri.md) and
[ADR-0023](0023-versioned-dataset-fixed-named-graphs.md) and adds the fragment
index to the stored format they fix

## Context

ADR-0022 and ADR-0023 were accepted with the first implementation. Checked
against the code of `rdf-versioning`, three of their statements are wrong, one
part of the stored format they fix is missing, and one assumption the module
makes is written down nowhere. The code is right in each case.

A fragment IRI of a proxy IRI cannot be found by a statement pattern: the store
is asked for a subject, an object or a predicate, never for "every IRI that
starts with this string". A slice is therefore read subject by subject, and the
library has to know which fragment subjects a slice has. It records them as
triples in the history graph. Those triples are stored data of every versioned
dataset: an export carries them, a reader of the history graph sees them, and a
later version of the library that did not know them would read slices
incompletely. They belong in the decision that fixes the stored format.

## Decision

**The fragment index is stored format.** Three properties of
`VocabVersioning` (`https://w3id.org/kognio/rdf/versioning#`) record the
fragment subjects of a slice, all in the history graph:

- `versioning:draftFragment`: subject a proxy IRI, object one of its
  `#fragment` IRIs that is a subject of the draft slice. One triple per such
  fragment as the library last wrote the draft; replaced on every draft write,
  removed by a delete that writes a tombstone.
- `versioning:publishedFragment`: subject a proxy IRI, object one of its
  fragment IRIs that is a subject of its slice in the published view. Replaced
  on every publish that writes the published view, removed by a delete that
  writes a tombstone.
- `versioning:versionFragment`: subject a history entry, object one of the
  fragment IRIs of the CID in the entry's `versioning:content`. Every entry that
  names a CID carries them (a publish, and a tombstone, which takes them over
  from the last version); written with the entry, never changed.

Like `versioning:revision`, they are bookkeeping triples beside the entries and
no entries. A change of these properties is a change of the stored format.

**Corrections.** Each replaces the statement named; the rest of ADR-0022 and
ADR-0023 stands.

1. *ADR-0022, Consequences, "The token":* the counter is not raised on every
   publish and delete. It is raised on every draft write and on every publish
   or delete that writes a history entry. An unchanged publish and a delete of a
   proxy IRI that is deleted already write nothing, so they leave the counter
   (and the token) as it is.
2. *ADR-0023, Decision, "Each content is stored once per CID, decided by
   `contains` on the CID as subject":* the decision is made by `contains` on one
   IRI subject of the version, the CID itself or, when the draft has no triple
   on the proxy IRI itself, a fragment IRI of the CID; one such subject proves
   the copy, as the same CID always means the same content. Within one publish
   of several proxy IRIs a CID is stored once as well, although none of them has
   it in the content store yet.
3. *ADR-0023, Consequences, "The revision counter of ADR-0022 is one
   bookkeeping triple per proxy IRI in the history":* the history graph holds
   more bookkeeping per proxy IRI than the counter: the `draftFragment` and
   `publishedFragment` triples above, one per fragment subject. The
   `versionFragment` triples belong to the entries.
4. *ADR-0022, Consequences, "Deleting a proxy IRI":* a delete of a proxy IRI
   that is deleted already (no draft, latest entry a tombstone) is a no-op: it
   writes no second tombstone and returns the existing one.

**Blank-node labels.** `rdf-versioning` assumes that within one
`DatasetTx` a blank node read from the store keeps its label: it reads a slice's
blank nodes as subject and as object and removes them by the labels it read.
`DatasetTx` does not promise this; the assumption is documented on `DatasetTx`,
on `BlankNode` and in the `rdf-versioning` package documentation. Nothing is
assumed across transactions. A backend that renames blank nodes on read cannot
host the module. This is related to
[#40](https://github.com/kogn-io/rdf-core/issues/40) and restricts a second
backend.

## Consequences

- A reader of a versioned dataset's history graph finds triples that are
  neither entries nor counters; tools that list the graph's subjects must expect
  them.
- ADR-0022 and ADR-0023 keep their text; their status lines point here.
- A second backend for `DatasetTx` has to keep blank-node labels stable within a
  transaction, or `rdf-versioning` does not work on it.
