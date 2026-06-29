package io.kogn.rdf.terms;

/**
 * Mutable RDF Graph - a set of RDF triples that can be modified.
 *
 * <p>Extends {@link ReadableGraph} with mutation operations. Use this interface
 * when building or modifying graphs (e.g., in {@link Draft}). For read-only
 * access (e.g., in {@link Version}), use {@link ReadableGraph} instead.</p>
 *
 * @see ReadableGraph
 */
public interface Graph extends ReadableGraph {
  /**
   * Adds a triple to the graph.
   *
   * @param triple the triple to add
   */
  void add(Triple triple);

  /**
   * Adds a triple to the graph using individual components.
   *
   * @param subject the subject
   * @param predicate the predicate
   * @param object the object
   */
  void add(BlankNodeOrIRI subject, IRI predicate, RDFTerm object);

  /**
   * Removes a triple from the graph.
   *
   * @param triple the triple to remove
   */
  void remove(Triple triple);

  /**
   * Removes all triples from the graph.
   */
  void clear();
}
