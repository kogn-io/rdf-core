package io.kogn.rdf.terms;

import java.util.stream.Stream;

/**
 * Unmodifiable view wrapping a {@link Graph}.
 *
 * <p>This wrapper exposes only the {@link ReadableGraph} interface,
 * preventing any mutation of the underlying graph data.</p>
 */
public final class UnmodifiableGraph implements ReadableGraph {

  private final ReadableGraph delegate;

  private UnmodifiableGraph(ReadableGraph delegate) {
    this.delegate = delegate;
  }

  /**
   * Wraps the given graph as an unmodifiable {@link ReadableGraph}.
   *
   * <p>If the graph is already an {@code UnmodifiableGraph}, it is returned as-is.</p>
   *
   * @param graph the graph to wrap
   * @return an unmodifiable view of the graph
   */
  public static ReadableGraph of(ReadableGraph graph) {
    if (graph instanceof UnmodifiableGraph) {
      return graph;
    }
    return new UnmodifiableGraph(graph);
  }

  @Override
  public boolean contains(Triple triple) {
    return delegate.contains(triple);
  }

  @Override
  public long size() {
    return delegate.size();
  }

  @Override
  public Stream<Triple> stream() {
    return delegate.stream();
  }

  @Override
  public Stream<Triple> stream(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
    return delegate.stream(subject, predicate, object);
  }

  @Override
  public boolean isEmpty() {
    return delegate.isEmpty();
  }
}
