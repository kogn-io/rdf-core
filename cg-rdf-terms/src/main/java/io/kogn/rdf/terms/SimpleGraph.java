package io.kogn.rdf.terms;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * In-memory {@link Graph} backed by a {@link LinkedHashSet} of {@link Triple}s.
 *
 * <p>Pure Java, no external dependencies. Triple identity relies on the
 * value-based {@code equals}/{@code hashCode} of the term implementations
 * (see {@link SimpleTriple}), so duplicate triples are deduplicated and
 * iteration order is insertion order.</p>
 *
 * <p>This is the default {@link Graph} returned by {@link SimpleRdf#createGraph()}.
 * It is not thread-safe.</p>
 */
public final class SimpleGraph implements Graph {

  private final Set<Triple> triples = new LinkedHashSet<>();

  @Override
  public void add(Triple triple) {
    triples.add(Objects.requireNonNull(triple, "triple must not be null"));
  }

  @Override
  public void add(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
    add(new SimpleRdf.SimpleTriple(subject, predicate, object));
  }

  @Override
  public void remove(Triple triple) {
    triples.remove(Objects.requireNonNull(triple, "triple must not be null"));
  }

  @Override
  public void clear() {
    triples.clear();
  }

  @Override
  public boolean contains(Triple triple) {
    return triples.contains(Objects.requireNonNull(triple, "triple must not be null"));
  }

  @Override
  public long size() {
    return triples.size();
  }

  @Override
  public boolean isEmpty() {
    return triples.isEmpty();
  }

  @Override
  public Stream<Triple> stream() {
    return triples.stream();
  }

  @Override
  public Stream<Triple> stream(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
    return triples.stream()
        .filter(t -> subject == null || subject.equals(t.getSubject()))
        .filter(t -> predicate == null || predicate.equals(t.getPredicate()))
        .filter(t -> object == null || object.equals(t.getObject()));
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("SimpleGraph [");
    for (Triple triple : triples) {
      sb.append("\n  ").append(triple);
    }
    return sb.append("\n]").toString();
  }
}
