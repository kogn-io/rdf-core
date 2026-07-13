// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import org.eclipse.rdf4j.model.util.Values;

import io.kogn.rdf.terms.IRI;

/**
 * RDF4J-based implementation of IRI.
 */
public class RDF4JIRI extends RDF4JTerm implements IRI {

  /**
   * Wraps the given RDF4J IRI.
   *
   * @param rdf4jIRI the RDF4J IRI to adapt
   */
  public RDF4JIRI(org.eclipse.rdf4j.model.IRI rdf4jIRI) {
    super(rdf4jIRI);
  }

  /**
   * Creates an IRI from a string.
   *
   * @param iriString the IRI string
   * @return the IRI instance
   */
  public static IRI of(String iriString) {
    return new RDF4JIRI(Values.iri(iriString));
  }

  @Override
  public String getIRIString() {
    return ((org.eclipse.rdf4j.model.IRI) rdf4jValue).stringValue();
  }

  @Override
  public org.eclipse.rdf4j.model.IRI getRDF4JValue() {
    return (org.eclipse.rdf4j.model.IRI) rdf4jValue;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (!(obj instanceof IRI))
      return false;
    return getIRIString().equals(((IRI) obj).getIRIString());
  }

  @Override
  public int hashCode() {
    return getIRIString().hashCode();
  }
}
