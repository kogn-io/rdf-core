// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * Named-graph-addressed RDF store port.
 *
 * <p>Provides basic triple management scoped to named graphs. Each operation targets
 * exactly one named graph identified by its {@link IRI}.</p>
 *
 * <p><strong>Named graphs only — not an RDF 1.1 dataset.</strong> This store has no
 * <a href="https://www.w3.org/TR/rdf11-concepts/#section-dataset">RDF 1.1</a> default
 * graph: every operation requires a graph {@link IRI}, graph names are always IRIs
 * (never blank nodes), and there is no unnamed graph to read from or write to. A
 * context-less SPARQL read (no {@code GRAPH} clause) via {@link SparqlQuery} therefore
 * ranges over the <em>union</em> of all named graphs, not over a default graph. The
 * default graph is intentionally not modelled (YAGNI). See the package documentation
 * for the full data-model contract.</p>
 *
 * <p>Implementations may choose to buffer writes; callers must not assume immediate
 * persistence outside of a {@link DatasetTransactor} transaction.</p>
 */
public interface GraphStore {

  /**
   * Adds all triples in the given graph to the named graph.
   *
   * <p>If the named graph does not yet exist it is created implicitly. Duplicate
   * triples are silently ignored.</p>
   *
   * @param namedGraph IRI identifying the target named graph; must not be {@code null}
   * @param triples the triples to add; must not be {@code null}
   * @return the net number of triples actually inserted — duplicates that were
   *     already present do not count, measured atomically with the write so that
   *     concurrent writers to the same named graph cannot distort the delta. This
   *     delta shares the exactness guarantee of {@link #count(IRI)}: it is exact
   *     wherever the implementation's triple count is exact, and no more precise
   *     than an estimate where the count is one.
   */
  long add(IRI namedGraph, ReadableGraph triples);

  /**
   * Removes all triples in the given graph from the named graph.
   *
   * <p>Triples that are not present are silently ignored. If the named graph becomes
   * empty after removal it may be implicitly dropped.</p>
   *
   * @param namedGraph IRI identifying the target named graph; must not be {@code null}
   * @param triples the triples to remove; must not be {@code null}
   * @return the net number of triples actually removed — triples that were not
   *     present do not count, measured atomically with the write so that concurrent
   *     writers to the same named graph cannot distort the delta. This delta shares
   *     the exactness guarantee of {@link #count(IRI)}: it is exact wherever the
   *     implementation's triple count is exact, and no more precise than an
   *     estimate where the count is one.
   */
  long remove(IRI namedGraph, ReadableGraph triples);

  /**
   * Removes all triples from the named graph without deleting the graph itself.
   *
   * <p>After this call the named graph is empty. The graph may disappear from
   * enumeration if the underlying store only tracks non-empty graphs.</p>
   *
   * @param namedGraph IRI identifying the named graph to clear; must not be {@code null}
   */
  void clear(IRI namedGraph);

  /**
   * Returns all triples currently stored in the named graph.
   *
   * <p>Returns an empty graph if the named graph does not exist or is empty.</p>
   *
   * @param namedGraph IRI identifying the named graph to export; must not be {@code null}
   * @return a snapshot of all triples in the named graph
   */
  ReadableGraph export(IRI namedGraph);

  /**
   * Returns the number of triples in the named graph.
   *
   * <p>The result is a best-effort count; an implementation backed by approximate
   * cardinality statistics may return a value that differs from the true count.
   * This port makes no cross-backend exactness promise, so that a future
   * statistics-based backend remains a conforming implementation — but the
   * RDF4J-backed implementation this library ships is exact, because it is
   * measured directly, not estimated.</p>
   *
   * @param namedGraph IRI identifying the named graph; must not be {@code null}
   * @return triple count; {@code 0} if the named graph does not exist
   */
  long count(IRI namedGraph);

  /**
   * Returns the total number of triples across all named graphs in this store.
   *
   * <p>Shares the exactness behavior of {@link #count(IRI)}.</p>
   *
   * @return total triple count
   */
  long count();
}
