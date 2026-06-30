// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

/**
 * A leased handle to a single dataset, obtained from
 * {@link DatasetLifecycle#acquire(DatasetId)}.
 *
 * <p>The handle is the unit of <em>in-flight protection</em>. While at least one
 * handle for a dataset is open, the underlying store must not be shut down,
 * evicted or deleted — this is what closes the time-of-check-to-time-of-use race
 * between acquisition and eviction. Each {@code acquire} takes a lease that is
 * released by {@link #close()}, so handles must be used in a
 * try-with-resources block:</p>
 *
 * <pre>{@code
 * try (Dataset ds = lifecycle.acquire(id)) {
 *     ds.graphStore().add(graphIri, triples);
 *     return ds.sparqlQuery().select(query).toList();
 * }
 * }</pre>
 *
 * <p>A handle is scoped to one unit of work; do not retain the operation objects
 * returned by the accessors beyond the life of the handle.</p>
 */
public interface Dataset extends AutoCloseable {

  /**
   * Returns the named-graph store for this dataset.
   *
   * @return the {@link GraphStore}; never {@code null}
   */
  GraphStore graphStore();

  /**
   * Returns the SPARQL read port for this dataset.
   *
   * @return the {@link SparqlQuery}; never {@code null}
   */
  SparqlQuery sparqlQuery();

  /**
   * Returns the SPARQL write port for this dataset.
   *
   * @return the {@link SparqlUpdate}; never {@code null}
   */
  SparqlUpdate sparqlUpdate();

  /**
   * Returns the transactional unit-of-work port for this dataset.
   *
   * @return the {@link DatasetTransactor}; never {@code null}
   */
  DatasetTransactor transactor();

  /**
   * Releases this lease. After the last open handle for a dataset is closed the
   * store becomes eligible for eviction or deletion again.
   *
   * <p>Overridden to declare no checked exception.</p>
   */
  @Override
  void close();
}
