// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import io.kogn.rdf.terms.BlankNode;

/**
 * RDF4J-based implementation of BlankNode.
 */
public final class RDF4JBlankNode extends RDF4JTerm implements BlankNode {

  /**
   * Wraps the given RDF4J blank node.
   *
   * @param rdf4jBNode the RDF4J blank node to adapt
   */
  RDF4JBlankNode(org.eclipse.rdf4j.model.BNode rdf4jBNode) {
    super(rdf4jBNode);
  }

  @Override
  public String uniqueReference() {
    return getRDF4JValue().getID();
  }

  @Override
  org.eclipse.rdf4j.model.BNode getRDF4JValue() {
    return (org.eclipse.rdf4j.model.BNode) super.getRDF4JValue();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (!(obj instanceof BlankNode))
      return false;
    return uniqueReference().equals(((BlankNode) obj).uniqueReference());
  }

  @Override
  public int hashCode() {
    return uniqueReference().hashCode();
  }
}
