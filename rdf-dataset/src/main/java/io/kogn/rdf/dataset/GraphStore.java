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
 *
 * <p>A failure of the store itself — an I/O error, a store that is shut down — reaches the
 * caller as the neutral {@link DatasetStorageException} on every operation, never as a backend
 * type; the original is kept as cause. A defect of the call ({@code null} arguments) is not a
 * storage failure and keeps its own type.</p>
 *
 * <p>A backend may reject a term the data model accepts: {@link io.kogn.rdf.terms} checks
 * little more than RDF 1.1 requires, a backend may check more. The RDF4J backend, for one,
 * rejects an IRI it cannot parse (a space, one of {@code <>"{}|^`}, a second {@code #}), a
 * literal whose lexical form is not valid for its datatype (an ill-typed literal, legal in
 * RDF 1.1, such as {@code "abc"^^xsd:integer}) and an invalid language tag. Such a term is a
 * defect of the call, not a failure of the store: it is reported as an
 * {@link IllegalArgumentException}, and a writing operation reports it before it writes the
 * first triple of the call.</p>
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
   *     concurrent writers to <em>disjoint</em> triples cannot distort the delta. Two
   *     concurrent writers adding the same triple can both report it as inserted, so the
   *     deltas of such writers may sum to more than the net change. This
   *     delta shares the exactness guarantee of {@link #count(IRI)}: it is exact
   *     wherever the implementation's triple count is exact, and no more precise
   *     than an estimate where the count is one.
   * @throws IllegalArgumentException if the backend rejects {@code namedGraph} or a term of
   *     {@code triples}; nothing of the call is written then
   * @throws DatasetStorageException if the backend fails while carrying out the operation
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
   *     writers of <em>disjoint</em> triples cannot distort the delta. Two concurrent writers
   *     removing the same triple can both report it as removed, so the deltas of such writers
   *     may sum to more than the net change. This delta shares
   *     the exactness guarantee of {@link #count(IRI)}: it is exact wherever the
   *     implementation's triple count is exact, and no more precise than an
   *     estimate where the count is one.
   * @throws IllegalArgumentException if the backend rejects {@code namedGraph} or a term of
   *     {@code triples}; nothing of the call is removed then
   * @throws DatasetStorageException if the backend fails while carrying out the operation
   */
  long remove(IRI namedGraph, ReadableGraph triples);

  /**
   * Removes all triples from the named graph without deleting the graph itself.
   *
   * <p>After this call the named graph is empty. The graph may disappear from
   * enumeration if the underlying store only tracks non-empty graphs.</p>
   *
   * @param namedGraph IRI identifying the named graph to clear; must not be {@code null}
   * @throws IllegalArgumentException if the backend rejects {@code namedGraph}
   * @throws DatasetStorageException if the backend fails while carrying out the operation
   */
  void clear(IRI namedGraph);

  /**
   * Returns all triples currently stored in the named graph.
   *
   * <p>Returns an empty graph if the named graph does not exist or is empty.</p>
   *
   * <p>The graph is rejected, not truncated, if the store holds an RDF 1.2 triple term, which
   * lies outside the RDF 1.1 data model (see {@link io.kogn.rdf.terms}); a SPARQL update (see
   * {@link SparqlUpdate}) stores one unchecked.</p>
   *
   * @param namedGraph IRI identifying the named graph to export; must not be {@code null}
   * @return a snapshot of all triples in the named graph
   * @throws IllegalStateException if the named graph holds an RDF 1.2 triple term
   * @throws IllegalArgumentException if the backend rejects {@code namedGraph}
   * @throws DatasetStorageException if the backend fails while carrying out the operation
   * @see DatasetExport#export(java.io.OutputStream, RdfFormat, IRI)
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
   * @throws IllegalArgumentException if the backend rejects {@code namedGraph}
   * @throws DatasetStorageException if the backend fails while carrying out the operation
   */
  long count(IRI namedGraph);

  /**
   * Returns the total number of triples in this store, across all named graphs and the
   * default graph.
   *
   * <p>Shares the exactness behavior of {@link #count(IRI)}.</p>
   *
   * <p>This count is not the sum of {@link #count(IRI)} over the named
   * graphs: it also includes triples in the store's default graph, which no named-graph
   * operation of this port can address (see {@link SparqlUpdate} for how they get there). A
   * store with such statements reports a larger value than the sum.</p>
   *
   * @return total triple count
   * @throws DatasetStorageException if the backend fails while carrying out the operation
   */
  long count();
}
