// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import java.util.Objects;
import java.util.Optional;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;

/**
 * RDF4J-based implementation of Literal.
 */
public class RDF4JLiteral extends RDF4JTerm implements Literal {

  public RDF4JLiteral(org.eclipse.rdf4j.model.Literal rdf4jLiteral) {
    super(rdf4jLiteral);
  }

  @Override
  public String getLexicalForm() {
    return ((org.eclipse.rdf4j.model.Literal) rdf4jValue).getLabel();
  }

  @Override
  public IRI getDatatype() {
    org.eclipse.rdf4j.model.IRI datatypeIRI = ((org.eclipse.rdf4j.model.Literal) rdf4jValue).getDatatype();
    return new RDF4JIRI(datatypeIRI);
  }

  @Override
  public Optional<String> getLanguageTag() {
    return ((org.eclipse.rdf4j.model.Literal) rdf4jValue).getLanguage();
  }

  @Override
  public org.eclipse.rdf4j.model.Literal getRDF4JValue() {
    return (org.eclipse.rdf4j.model.Literal) rdf4jValue;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (!(obj instanceof Literal))
      return false;
    Literal other = (Literal) obj;
    return (getLexicalForm().equals(other.getLexicalForm()) && getDatatype().equals(other.getDatatype())
        && getLanguageTag().equals(other.getLanguageTag()));
  }

  @Override
  public int hashCode() {
    return Objects.hash(getLexicalForm(), getDatatype(), getLanguageTag());
  }
}
