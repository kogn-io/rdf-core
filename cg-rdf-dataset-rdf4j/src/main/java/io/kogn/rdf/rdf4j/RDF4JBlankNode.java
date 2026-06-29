// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import io.kogn.rdf.terms.BlankNode;

/**
 * RDF4J-based implementation of BlankNode.
 */
public class RDF4JBlankNode extends RDF4JTerm implements BlankNode {

  public RDF4JBlankNode(org.eclipse.rdf4j.model.BNode rdf4jBNode) {
    super(rdf4jBNode);
  }

  @Override
  public String uniqueReference() {
    return ((org.eclipse.rdf4j.model.BNode) rdf4jValue).getID();
  }

  @Override
  public org.eclipse.rdf4j.model.BNode getRDF4JValue() {
    return (org.eclipse.rdf4j.model.BNode) rdf4jValue;
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
