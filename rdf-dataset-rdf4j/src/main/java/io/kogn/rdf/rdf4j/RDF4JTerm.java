// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import org.eclipse.rdf4j.model.Value;

import io.kogn.rdf.terms.RDFTerm;
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

  @Override
  public String ntriplesString() {
    return rdf4jValue.toString();
  }

  @Override
  public String toString() {
    return ntriplesString();
  }
}
