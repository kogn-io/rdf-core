package io.kogn.rdf.terms;

import java.util.Objects;

/**
 * Represents a named graph container in the RDF store.
 *
 * <p>A named graph is identified by an IRI and acts as a container
 * for multiple {@link Graph}s (each identified by a public IRI).
 * This is distinct from {@link Graph} which represents the actual triple content.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * NamedGraph catalog = NamedGraph.of("http://example.com/api/foodcatalogs/bls");
 * }</pre>
 *
 * @param iri the IRI identifying this named graph
 */
public record NamedGraph(IRI iri) {
  public NamedGraph {
    Objects.requireNonNull(iri, "iri must not be null");
  }

  /**
   * Creates a NamedGraph from an IRI.
   *
   * @param iri the IRI
   * @return a new NamedGraph
   */
  public static NamedGraph of(IRI iri) {
    return new NamedGraph(iri);
  }
}
