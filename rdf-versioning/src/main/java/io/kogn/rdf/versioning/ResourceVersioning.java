// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetStorageException;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * Versioning of the RDF resources in one versioned dataset (ADR-0021, ADR-0022).
 *
 * <p>An instance is bound to the caller's <em>content graph</em> — the draft view, the
 * one graph of a versioned dataset the caller names. Every other graph the versioning
 * keeps has a fixed IRI of the library ({@link VersioningGraphs}), its terms are
 * {@link VocabVersioning}. A versioned dataset has exactly one content graph: the first
 * use records it in the {@link VersioningGraphs#CONFIG config graph}, every later use
 * checks it and rejects a different one (ADR-0023).</p>
 *
 * <p><strong>Transactions.</strong> Every operation takes the caller's open
 * {@link DatasetTx} and never opens a transaction of its own, so several operations
 * commit together or not at all, and an aborted transaction leaves nothing of them
 * behind. A {@link ConcurrencyConflictException} the store raises on commit is
 * reported to the caller of {@link DatasetTransactor#inTransaction}; retrying is the
 * caller's decision. First-insert guards read with
 * {@link DatasetTx#contains(IRI, io.kogn.rdf.terms.BlankNodeOrIRI, IRI,
 * io.kogn.rdf.terms.RDFTerm) contains}, not {@code ASK} (ADR-0008).</p>
 *
 * <p><strong>Union reads.</strong> On a versioned dataset a query without {@code FROM}
 * or {@code GRAPH} runs over the union of all named graphs and sees the library's
 * graphs as well; consumers name the graph they read.</p>
 */
public interface ResourceVersioning {

  /**
   * Returns the content graph this instance is bound to.
   *
   * @return the IRI of the caller's content graph; never {@code null}
   */
  IRI contentGraph();

  /**
   * Binds the dataset of {@code tx} to this instance's {@link #contentGraph() content
   * graph}, or checks an existing binding.
   *
   * <p>If the dataset has no content graph recorded yet, records this one in the
   * {@link VersioningGraphs#CONFIG config graph}, inside {@code tx}. If it has this one
   * recorded, changes nothing. If it has a different one recorded, changes nothing and
   * throws. Every operation of the port does this itself before it touches the
   * dataset; calling it directly switches versioning on for a dataset ahead of the
   * first write.</p>
   *
   * <p>Two transactions that bind the same unbound dataset concurrently are guarded by
   * the isolation promise of {@link DatasetTransactor}: if they name different content
   * graphs, at most one commits.</p>
   *
   * @param tx the caller's open transaction; must not be {@code null}
   * @throws ContentGraphMismatchException if the dataset is bound to another content
   *     graph
   * @throws DatasetStorageException if the backend fails while reading or writing the
   *     config graph
   * @throws IllegalStateException if {@code tx} has already ended
   * @throws NullPointerException if {@code tx} is {@code null}
   */
  void bindContentGraph(DatasetTx tx);

  /**
   * Reads the draft slice of a proxy IRI together with its revision token.
   *
   * <p>The slice is every triple of the {@link #contentGraph() content graph} whose
   * subject is the proxy IRI or one of its {@code #fragment} IRIs, plus the blank nodes
   * reachable from them. Membership of an IRI subject is decided by comparing strings,
   * without normalization: it belongs if it equals the proxy IRI or starts with the
   * proxy IRI and {@code #}. A fragment subject needs no link from the proxy IRI. Blank
   * nodes are followed from the objects of slice triples, transitively; their labels are
   * the store's and not stable across reads.</p>
   *
   * <p>A proxy IRI without a draft yields an empty slice and a valid token (revision
   * zero), so a first {@link #replaceSlice write} needs no special case. An empty slice
   * is not a deletion.</p>
   *
   * <p><strong>The token</strong> is a revision counter per proxy IRI. It sees writes
   * made through this library only; triples written to the content graph past the slice
   * operations do not change it. Reading the slice may bind the dataset to the content
   * graph, see {@link #bindContentGraph(DatasetTx)}.</p>
   *
   * @param tx the caller's open transaction; must not be {@code null}
   * @param proxyIri the proxy IRI; must not be {@code null} and must not contain a
   *     {@code #}
   * @return the slice and its token; never {@code null}
   * @throws ContentGraphMismatchException if the dataset is bound to another content
   *     graph
   * @throws IllegalArgumentException if {@code proxyIri} contains a {@code #}
   * @throws IllegalStateException if {@code tx} has already ended, or the revision
   *     counter in the history graph is malformed
   * @throws DatasetStorageException if the backend fails
   * @throws NullPointerException if an argument is {@code null}
   */
  DraftSlice readSlice(DatasetTx tx, IRI proxyIri);

  /**
   * Replaces the draft slice of a proxy IRI by {@code content} and raises its revision
   * counter, if {@code expected} is still current.
   *
   * <p>Everything in the slice of the proxy IRI is removed and {@code content} is
   * written in its place; triples of other proxy IRIs are untouched. Every triple of
   * {@code content} must belong to the slice (see {@link #readSlice}): an IRI subject
   * that is neither the proxy IRI nor one of its fragment IRIs, or a blank-node subject
   * not reachable from such a subject, is rejected. The proxy IRI is never inferred from
   * the content.</p>
   *
   * <p>An empty {@code content} empties the draft but is not a deletion: the proxy IRI
   * stays known and the counter rises like for any write. {@code expected} is checked
   * first; if it is stale, no triple is changed.</p>
   *
   * <p>The write runs in {@code tx}, opens no transaction and, if {@code tx} aborts,
   * leaves neither triples nor a new counter behind. A stale token is reported, never
   * retried. The first counter of a proxy IRI is looked up with {@link DatasetTx#contains} (ADR-0008). The token
   * sees writes made through the library only.</p>
   *
   * @param tx the caller's open transaction; must not be {@code null}
   * @param proxyIri the proxy IRI; must not be {@code null} and must not contain a
   *     {@code #}
   * @param content the new slice; must not be {@code null}
   * @param expected the token from the last read or write of this proxy IRI; must not
   *     be {@code null}
   * @return the token for the next write of this proxy IRI
   * @throws RevisionConflictException if {@code expected} is not the current revision
   * @throws AmbiguousBlankNodeException if a blank node of {@code content} or of the
   *     slice it replaces is also used by a triple of the content graph outside this
   *     slice
   * @throws ContentGraphMismatchException if the dataset is bound to another content
   *     graph
   * @throws IllegalArgumentException if {@code content} holds a triple outside the slice,
   *     if {@code proxyIri} contains a {@code #} or if {@code expected} belongs to
   *     another proxy IRI
   * @throws IllegalStateException if {@code tx} has already ended, or the revision
   *     counter in the history graph is malformed
   * @throws DatasetStorageException if the backend fails
   * @throws NullPointerException if an argument is {@code null}
   */
  RevisionToken replaceSlice(DatasetTx tx, IRI proxyIri, ReadableGraph content, RevisionToken expected);

  /**
   * Publishes the drafts of one or more proxy IRIs: each becomes an immutable version
   * under its CID, the published view shows it, and the history gets an entry.
   *
   * <p>For each proxy IRI the CID of its {@link #readSlice draft slice} is derived with
   * the {@code rdf-cid} generator, the slice renamed from the proxy IRI to the CID is
   * written to the {@link VersioningGraphs#CONTENT_STORE content store} unless that CID
   * is already there (blank nodes stay blank nodes, with fresh labels), the proxy IRI's
   * slice in the {@link VersioningGraphs#PUBLISHED published view} is replaced by the
   * draft, and a history entry is written (ADR-0022, ADR-0023). The draft stays. The
   * entry has a random {@code urn:uuid:} IRI, the next sequence number of the proxy IRI
   * (from 1, gapless), the CID, the {@code procedureTag()} of the generator, and
   * {@code dcterms:created} from the implementation's clock, plus creator and comment
   * from {@code details} if given. The revision counter of the proxy IRI is raised.</p>
   *
   * <p><strong>Unchanged drafts.</strong> A draft whose CID equals the CID of the proxy
   * IRI's latest entry, derived by the same procedure, is not published again: no entry
   * is created, nothing is written and the counter stays; the result names that latest
   * entry and returns the token that was passed in. So a repeated publish is idempotent.
   * A draft that changes and returns (A, B, A) creates three entries; the third names
   * the CID of the first, whose content is stored once. If the latest entry was derived
   * by another procedure than the generator's, the CIDs are not comparable and the draft
   * is published again.</p>
   *
   * <p><strong>All or nothing.</strong> Every token is checked and every CID derived
   * before the first triple is written (apart from the binding of the content graph on first use), so a failure for any proxy IRI leaves the
   * transaction as it was; if {@code tx} aborts, published view, content store, history
   * and counters are unchanged. A stale token is reported, never retried. The first
   * sequence number and the first copy of a CID are looked up with
   * {@link DatasetTx#contains} (ADR-0008). Reading the draft may bind the dataset to the
   * content graph, see {@link #bindContentGraph(DatasetTx)}.</p>
   *
   * @param tx the caller's open transaction; must not be {@code null}
   * @param expected the token of each proxy IRI to publish, from its last read or write;
   *     must not be {@code null}, hold no {@code null}, and at most one token per proxy
   *     IRI; may be empty, which publishes nothing
   * @param details creator and comment for the entries created; must not be {@code null}
   * @return per proxy IRI the entry naming its current version, and its next token; never
   *     {@code null}
   * @throws RevisionConflictException if a token is not the current revision of its proxy
   *     IRI
   * @throws IllegalArgumentException if two tokens belong to the same proxy IRI, or the
   *     CID of a draft cannot be derived because it violates a precondition of
   *     {@code ContentAddressedIriGenerator#generateIri}: an empty draft, a reserved
   *     IRI, a term outside RDF 1.1
   * @throws io.kogn.rdf.cid.CanonicalizationResourceLimitExceededException if a draft
   *     exceeds the cost limit of ni-rdf/1
   * @throws io.kogn.rdf.cid.ContentAddressingException if the identifier of a draft
   *     cannot be derived for another reason
   * @throws ContentGraphMismatchException if the dataset is bound to another content
   *     graph
   * @throws IllegalStateException if {@code tx} has already ended, or the history graph
   *     is malformed: a revision counter or a latest entry of a proxy IRI that does not
   *     read back
   * @throws DatasetStorageException if the backend fails
   * @throws NullPointerException if an argument is {@code null}
   */
  PublishResult publish(DatasetTx tx, Collection<RevisionToken> expected, PublishDetails details);

  /**
   * Reads the published state of a proxy IRI: the copy of its current version in the
   * {@link VersioningGraphs#PUBLISHED published view}, under the proxy IRI.
   *
   * <p>The slice is cut like the draft's (see {@link #readSlice}). It is empty if the
   * proxy IRI was never published. Blank-node labels are the store's and not stable
   * across reads, nor equal to those of the draft or of the stored version (ADR-0023),
   * so compare by CID, not by triple. A reference to a proxy IRI inside the slice stays
   * a reference to the proxy IRI and meets the target's current state when read later
   * (ADR-0022). Reading may bind the dataset to the content graph, see
   * {@link #bindContentGraph(DatasetTx)}.</p>
   *
   * @param tx the caller's open transaction; must not be {@code null}
   * @param proxyIri the proxy IRI; must not be {@code null} and must not contain a
   *     {@code #}
   * @return the published slice, a copy the caller may keep; never {@code null}
   * @throws ContentGraphMismatchException if the dataset is bound to another content
   *     graph
   * @throws IllegalArgumentException if {@code proxyIri} contains a {@code #}
   * @throws IllegalStateException if {@code tx} has already ended
   * @throws DatasetStorageException if the backend fails
   * @throws NullPointerException if an argument is {@code null}
   */
  ReadableGraph readPublished(DatasetTx tx, IRI proxyIri);

  /**
   * Reads a version by its CID from the local {@link VersioningGraphs#CONTENT_STORE
   * content store}.
   *
   * <p>The version is delivered with the CID as subject, as stored, and never differs
   * between reads except in blank-node labels, which are the store's and not stable
   * (ADR-0023). The CID must be in the canonical form {@code ni:///sha3-256;...}
   * (ADR-0019); another spelling of the same name is not normalized and is not found.
   * Stored references are not translated: a reference to a proxy IRI inside the version
   * keeps naming the proxy IRI and meets the target's current state when read later
   * (ADR-0022).
   * The library does not resolve a CID it does not hold, and offers no read from a CID
   * back to a proxy IRI: two proxy IRIs with identical content share one version
   * (ADR-0022). Reading may bind the dataset to the content graph, see
   * {@link #bindContentGraph(DatasetTx)}.</p>
   *
   * @param tx the caller's open transaction; must not be {@code null}
   * @param cid the CID of the version; must not be {@code null} and must not contain a
   *     {@code #}
   * @return the version, a copy the caller may keep, or empty if the content store holds
   *     none under {@code cid}
   * @throws ContentGraphMismatchException if the dataset is bound to another content
   *     graph
   * @throws IllegalArgumentException if {@code cid} contains a {@code #}
   * @throws IllegalStateException if {@code tx} has already ended
   * @throws DatasetStorageException if the backend fails
   * @throws NullPointerException if an argument is {@code null}
   */
  Optional<ReadableGraph> readVersion(DatasetTx tx, IRI cid);

  /**
   * Reads version <em>n</em> of a proxy IRI: the content its history entry with sequence
   * number {@code sequence} names.
   *
   * <p>A join of the history and the content store, so that no consumer writes it. The
   * result has the CID as subject, like {@link #readVersion(DatasetTx, IRI)}. A version
   * whose content was removed from the store has an entry but no content: the result is
   * empty then, as for an {@code sequence} the proxy IRI never reached. Reading may bind
   * the dataset to the content graph, see {@link #bindContentGraph(DatasetTx)}.</p>
   *
   * <p>Stored references are not translated: a reference to a proxy IRI inside the version
   * keeps naming the proxy IRI and meets the target's current state when read later
   * (ADR-0022).</p>
   *
   * @param tx the caller's open transaction; must not be {@code null}
   * @param proxyIri the proxy IRI; must not be {@code null} and must not contain a
   *     {@code #}
   * @param sequence the sequence number of the entry, counted from 1; must be at least 1
   * @return the version, a copy the caller may keep, or empty if there is no such entry
   *     or the store holds no content for it
   * @throws ContentGraphMismatchException if the dataset is bound to another content
   *     graph
   * @throws IllegalArgumentException if {@code proxyIri} contains a {@code #} or
   *     {@code sequence} is less than 1
   * @throws IllegalStateException if {@code tx} has already ended, or the history of the
   *     proxy IRI is malformed or not gapless
   * @throws DatasetStorageException if the backend fails
   * @throws NullPointerException if an argument is {@code null}
   */
  Optional<ReadableGraph> readVersion(DatasetTx tx, IRI proxyIri, long sequence);

  /**
   * Reads the history of one proxy IRI: its publishes, oldest first.
   *
   * <p>The list is empty for a proxy IRI that was never published. The sequence numbers
   * run from 1 without gaps; the last entry names the current version. The history of
   * several proxy IRIs in one call is not offered. Reading may bind the dataset to the
   * content graph, see {@link #bindContentGraph(DatasetTx)}.</p>
   *
   * @param tx the caller's open transaction; must not be {@code null}
   * @param proxyIri the proxy IRI; must not be {@code null} and must not contain a
   *     {@code #}
   * @return the entries by ascending sequence number; never {@code null}
   * @throws ContentGraphMismatchException if the dataset is bound to another content
   *     graph
   * @throws IllegalArgumentException if {@code proxyIri} contains a {@code #}
   * @throws IllegalStateException if {@code tx} has already ended, or the history of the
   *     proxy IRI is malformed or not gapless
   * @throws DatasetStorageException if the backend fails
   * @throws NullPointerException if an argument is {@code null}
   */
  List<HistoryEntry> history(DatasetTx tx, IRI proxyIri);

  /**
   * Tells whether the draft of a proxy IRI was published and whether it differs from the
   * latest version.
   *
   * <p>Decided by comparing the CID of the draft slice with the CID of the latest history
   * entry, never triple by triple: blank-node labels may differ for equal content
   * (ADR-0022). A proxy IRI without history is {@link PublishState#NEVER_PUBLISHED},
   * whatever its draft holds, and no CID is derived. An <em>empty</em> draft of a
   * published proxy IRI cannot equal a version (an empty draft is not publishable) and
   * is {@link PublishState#PUBLISHED_WITH_UNPUBLISHED_CHANGES} without a derivation. If
   * the latest entry was derived by another procedure than the one in use, the CIDs are
   * not comparable and the answer is {@code PUBLISHED_WITH_UNPUBLISHED_CHANGES}, as a
   * publish would publish again. Writes past this library are seen here, by content,
   * though the revision token does not see them. Reading may bind the dataset to the
   * content graph, see {@link #bindContentGraph(DatasetTx)}.</p>
   *
   * @param tx the caller's open transaction; must not be {@code null}
   * @param proxyIri the proxy IRI; must not be {@code null} and must not contain a
   *     {@code #}
   * @return the state; never {@code null}
   * @throws IllegalArgumentException if {@code proxyIri} contains a {@code #}, or the CID
   *     of the draft cannot be derived because it violates a precondition of
   *     {@code ContentAddressedIriGenerator#generateIri}: a reserved IRI, a term outside
   *     RDF 1.1
   * @throws io.kogn.rdf.cid.CanonicalizationResourceLimitExceededException if the draft
   *     exceeds the cost limit of ni-rdf/1
   * @throws io.kogn.rdf.cid.ContentAddressingException if the identifier of the draft
   *     cannot be derived for another reason
   * @throws ContentGraphMismatchException if the dataset is bound to another content
   *     graph
   * @throws IllegalStateException if {@code tx} has already ended, or the history of the
   *     proxy IRI is malformed or not gapless
   * @throws DatasetStorageException if the backend fails
   * @throws NullPointerException if an argument is {@code null}
   */
  PublishState publishState(DatasetTx tx, IRI proxyIri);
}
