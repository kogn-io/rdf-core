// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import org.eclipse.rdf4j.model.Value;

import io.kogn.rdf.terms.RDFTerm;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * Base class for RDF4J-based RDF terms.
 *
 * <p>Wraps an RDF4J {@link Value} and delegates to its methods.</p>
 */
@RequiredArgsConstructor
@EqualsAndHashCode
public abstract class RDF4JTerm implements RDFTerm {

  protected final Value rdf4jValue;

  /**
   * Returns the wrapped RDF4J value.
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
