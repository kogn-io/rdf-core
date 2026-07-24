// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

/**
 * A leased, {@link AutoCloseable} access handle to a single dataset, obtained
 * from {@link DatasetLifecycle#acquire(DatasetId)}.
 *
 * <p><strong>Not an RDF dataset.</strong> Despite the {@code Dataset} in its
 * name this type is deliberately <em>not</em> a dataset in the sense of the
 * <a href="https://www.w3.org/TR/rdf11-concepts/#section-dataset">RDF 1.1
 * concepts</a> (a default graph plus zero or more named graphs) and it is
 * <em>not</em> an {@code org.apache.commons.rdf.api.Dataset} (a collection of
 * quads). It carries no triples or quads of its own and is not a value type. It
 * is a short-lived, leased view onto the underlying store — conceptually the
 * counterpart of an RDF4J {@code RepositoryConnection} — through which the
 * neutral dataset ports are reached. The RDF data itself is reached via
 * {@link #graphStore()} / {@link #sparqlQuery()} and is modelled as named graphs
 * only (see the package documentation).</p>
 *
 * <p>The handle is the unit of <em>in-flight protection</em>. While at least one
 * handle for a dataset is open, the underlying store must not be shut down,
 * evicted or deleted — this is what closes the time-of-check-to-time-of-use race
 * between acquisition and eviction. Each {@code acquire} takes a lease that is
 * released by {@link #close()}, so handles must be used in a
 * try-with-resources block:</p>
 *
 * <pre>{@code
 * try (DatasetHandle ds = lifecycle.acquire(id)) {
 *     ds.graphStore().add(graphIri, triples);
 *     return ds.sparqlQuery().select(query).toList();
 * }
 * }</pre>
 *
 * <p>A handle is scoped to one unit of work: do not retain the objects returned by
 * the accessors beyond the life of the handle. This is enforced, not merely
 * advisory — each accessor returns a thin, per-handle wrapper delegating to the
 * dataset's shared underlying instance, and every operation invoked on such a
 * wrapper after {@link #close()} has run throws {@link IllegalStateException}
 * instead of silently reaching the store. The underlying instance itself stays
 * shared and open for as long as the dataset is cached — only the wrapper obtained
 * through <em>this</em> handle stops working.</p>
 */
public interface DatasetHandle extends AutoCloseable {

  /**
   * Returns the named-graph store for this dataset.
   *
   * <p>The returned instance is bound to this handle: once this handle is
   * {@linkplain #close() closed}, every subsequent call to it throws
   * {@link IllegalStateException}.</p>
   *
   * @return the {@link GraphStore}; never {@code null}
   */
  GraphStore graphStore();

  /**
   * Returns the SPARQL read port for this dataset.
   *
   * <p>The returned instance is bound to this handle: once this handle is
   * {@linkplain #close() closed}, every subsequent call to it throws
   * {@link IllegalStateException}.</p>
   *
   * @return the {@link SparqlQuery}; never {@code null}
   */
  SparqlQuery sparqlQuery();

  /**
   * Returns the SPARQL write port for this dataset.
   *
   * <p>The returned instance is bound to this handle: once this handle is
   * {@linkplain #close() closed}, every subsequent call to it throws
   * {@link IllegalStateException}.</p>
   *
   * @return the {@link SparqlUpdate}; never {@code null}
   */
  SparqlUpdate sparqlUpdate();

  /**
   * Returns the transactional unit-of-work port for this dataset.
   *
   * <p>The returned instance is bound to this handle: once this handle is
   * {@linkplain #close() closed}, every subsequent call to it throws
   * {@link IllegalStateException}.</p>
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
