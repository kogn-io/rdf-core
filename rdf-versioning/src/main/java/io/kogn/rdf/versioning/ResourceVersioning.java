// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

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
}
