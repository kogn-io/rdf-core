// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import java.util.Objects;

import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.Triple;

/**
 * RDF4J-based implementation of Triple.
 */
public class RDF4JTriple implements Triple {

  private final Resource subject;
  private final org.eclipse.rdf4j.model.IRI predicate;
  private final Value object;

  /**
   * Creates a triple from RDF4J subject, predicate and object values.
   *
   * @param subject the RDF4J subject (IRI or blank node)
   * @param predicate the RDF4J predicate IRI
   * @param object the RDF4J object value
   */
  public RDF4JTriple(Resource subject, org.eclipse.rdf4j.model.IRI predicate, Value object) {
    this.subject = subject;
    this.predicate = predicate;
    this.object = object;
  }

  @Override
  public BlankNodeOrIRI getSubject() {
    if (subject instanceof org.eclipse.rdf4j.model.IRI) {
      return new RDF4JIRI((org.eclipse.rdf4j.model.IRI) subject);
    } else if (subject instanceof org.eclipse.rdf4j.model.BNode) {
      return new RDF4JBlankNode((org.eclipse.rdf4j.model.BNode) subject);
    }
    throw new IllegalStateException("Unknown subject type: " + subject.getClass());
  }

  @Override
  public IRI getPredicate() {
    return new RDF4JIRI(predicate);
  }

  @Override
  public RDFTerm getObject() {
    if (object instanceof org.eclipse.rdf4j.model.IRI) {
      return new RDF4JIRI((org.eclipse.rdf4j.model.IRI) object);
    } else if (object instanceof org.eclipse.rdf4j.model.Literal) {
      return new RDF4JLiteral((org.eclipse.rdf4j.model.Literal) object);
    } else if (object instanceof org.eclipse.rdf4j.model.BNode) {
      return new RDF4JBlankNode((org.eclipse.rdf4j.model.BNode) object);
    }
    throw new IllegalStateException("Unknown object type: " + object.getClass());
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (!(obj instanceof Triple other))
      return false;
    return getSubject().equals(other.getSubject()) && getPredicate().equals(other.getPredicate())
        && getObject().equals(other.getObject());
  }

  @Override
  public int hashCode() {
    return Objects.hash(getSubject(), getPredicate(), getObject());
  }

  /**
   * Converts to RDF4J Statement.
   *
   * @return the equivalent RDF4J {@link Statement}
   */
  public Statement toRDF4JStatement() {
    return SimpleValueFactory.getInstance().createStatement(subject, predicate, object);
  }

  @Override
  public String toString() {
    return subject + " " + predicate + " " + object + " .";
  }
}
