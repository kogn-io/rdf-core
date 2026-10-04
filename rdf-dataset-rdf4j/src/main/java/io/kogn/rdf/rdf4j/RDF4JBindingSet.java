// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import java.util.Optional;
import java.util.Set;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.terms.RDFTerm;

/**
 * RDF4J-based implementation of BindingSet.
 *
 * <p>Wraps an RDF4J {@link org.eclipse.rdf4j.query.BindingSet} and adapts it
 * to our API.</p>
 *
 * <p>The port data model has no RDF 1.2 triple terms ({@code <<( s p o )>>}). A store can still
 * hold one, because a SPARQL update accepts it; a result row binding one is rejected on creation
 * with an {@link IllegalStateException}, so {@code select} fails instead of the first access to the
 * value.</p>
 */
public class RDF4JBindingSet implements BindingSet {

  private final org.eclipse.rdf4j.query.BindingSet rdf4jBindingSet;

  /**
   * Wraps the given RDF4J binding set.
   *
   * @param rdf4jBindingSet the RDF4J binding set to adapt
   * @throws IllegalStateException if a binding is an RDF 1.2 triple term, which the port data model
   *     cannot represent
   */
  public RDF4JBindingSet(org.eclipse.rdf4j.query.BindingSet rdf4jBindingSet) {
    for (final org.eclipse.rdf4j.query.Binding binding : rdf4jBindingSet) {
      if (binding.getValue() instanceof org.eclipse.rdf4j.model.TripleTerm) {
        throw new IllegalStateException(
            "RDF 1.2 triple term is not supported by the port data model (bound to ?" + binding.getName() + ")");
      }
    }
    this.rdf4jBindingSet = rdf4jBindingSet;
  }

  @Override
  public Set<String> getBindingNames() {
    return rdf4jBindingSet.getBindingNames();
  }

  @Override
  public Optional<RDFTerm> getValue(String bindingName) {
    return Optional.ofNullable(rdf4jBindingSet.getValue(bindingName)).map(this::toRDFTerm);
  }

  @Override
  public boolean hasBinding(String bindingName) {
    return rdf4jBindingSet.hasBinding(bindingName);
  }

  @Override
  public int size() {
    return rdf4jBindingSet.size();
  }

  /**
   * Converts an RDF4J Value to our RDFTerm.
   */
  private RDFTerm toRDFTerm(org.eclipse.rdf4j.model.Value value) {
    if (value instanceof org.eclipse.rdf4j.model.IRI) {
      return new RDF4JIRI((org.eclipse.rdf4j.model.IRI) value);
    } else if (value instanceof org.eclipse.rdf4j.model.Literal) {
      return new RDF4JLiteral((org.eclipse.rdf4j.model.Literal) value);
    } else if (value instanceof org.eclipse.rdf4j.model.BNode) {
      return new RDF4JBlankNode((org.eclipse.rdf4j.model.BNode) value);
    }
    throw new IllegalArgumentException("Unknown RDF4J Value type: " + value.getClass());
  }
}
