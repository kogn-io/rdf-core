// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import org.eclipse.rdf4j.model.Value;

import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import lombok.EqualsAndHashCode;

/**
 * Base class for RDF4J-based RDF terms.
 *
 * <p>Wraps an RDF4J {@link Value} and delegates to its methods.</p>
 */
@EqualsAndHashCode
public abstract class RDF4JTerm implements RDFTerm {

  /** The wrapped RDF4J value. */
  protected final Value rdf4jValue;

  /**
   * Wraps the given RDF4J value.
   *
   * @param rdf4jValue the RDF4J value to adapt
   */
  protected RDF4JTerm(Value rdf4jValue) {
    this.rdf4jValue = rdf4jValue;
  }

  /**
   * Returns the wrapped RDF4J value.
   *
   * @return the underlying RDF4J {@link Value}
   */
  public Value getRDF4JValue() {
    return rdf4jValue;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Serialized by {@code rdf-terms} itself, so both backends produce the same string.</p>
   */
  @Override
  public String ntriplesString() {
    if (rdf4jValue instanceof org.eclipse.rdf4j.model.IRI iri) {
      return new SimpleRdf().createIRI(iri.stringValue()).ntriplesString();
    }
    if (rdf4jValue instanceof org.eclipse.rdf4j.model.BNode bnode) {
      return new SimpleRdf().createBlankNode(bnode.getID()).ntriplesString();
    }
    if (rdf4jValue instanceof org.eclipse.rdf4j.model.Literal literal) {
      final SimpleRdf rdf = new SimpleRdf();
      return literal.getLanguage()
          .map(tag -> rdf.createLiteral(literal.getLabel(), tag))
          .orElseGet(() -> rdf.createLiteral(literal.getLabel(), rdf.createIRI(literal.getDatatype().stringValue())))
          .ntriplesString();
    }
    throw new IllegalStateException("Unsupported RDF4J value: " + rdf4jValue.getClass());
  }

  @Override
  public String toString() {
    return rdf4jValue.toString();
  }
}
