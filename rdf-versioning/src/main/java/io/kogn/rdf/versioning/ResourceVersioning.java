// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetStorageException;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.IRI;

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
}
