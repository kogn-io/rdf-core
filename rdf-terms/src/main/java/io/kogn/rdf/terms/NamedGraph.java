// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

import java.util.Objects;

/**
 * Represents the name of a named graph in the RDF store.
 *
 * <p>In RDF 1.1 a named graph is a pair {@code (IRI, Graph)}. This record holds
 * only the IRI — i.e. the <em>name</em> of that pair — not the graph itself.
 * Use this type wherever a named-graph identity is needed without carrying the
 * triple content.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * NamedGraph catalog = NamedGraph.of("http://example.com/api/foodcatalogs/bls");
 * }</pre>
 *
 * @param iri the IRI that names this named graph
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
