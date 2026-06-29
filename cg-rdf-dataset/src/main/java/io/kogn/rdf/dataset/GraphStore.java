package io.kogn.rdf.dataset;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * Named-graph-addressed RDF store port.
 *
 * <p>Provides basic triple management scoped to named graphs. Each operation targets
 * exactly one named graph identified by its {@link IRI}.</p>
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
   */
  void add(IRI namedGraph, ReadableGraph triples);

  /**
   * Removes all triples in the given graph from the named graph.
   *
   * <p>Triples that are not present are silently ignored. If the named graph becomes
   * empty after removal it may be implicitly dropped.</p>
   *
   * @param namedGraph IRI identifying the target named graph; must not be {@code null}
   * @param triples the triples to remove; must not be {@code null}
   */
  void remove(IRI namedGraph, ReadableGraph triples);

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
   * <p>The result is a best-effort estimate; implementations backed by approximate
   * cardinality statistics may return a value that differs from the exact count.</p>
   *
   * @param namedGraph IRI identifying the named graph; must not be {@code null}
   * @return triple count (estimated); {@code 0} if the named graph does not exist
   */
  long count(IRI namedGraph);

  /**
   * Returns the total number of triples across all named graphs in this store.
   *
   * <p>Like {@link #count(IRI)}, the result may be an estimate.</p>
   *
   * @return total triple count (estimated)
   */
  long count();
}
