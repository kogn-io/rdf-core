// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;

/**
 * RDF4J-based implementation of Literal.
 */
public final class RDF4JLiteral extends RDF4JTerm implements Literal {

  /**
   * Wraps the given RDF4J literal.
   *
   * @param rdf4jLiteral the RDF4J literal to adapt
   */
  RDF4JLiteral(org.eclipse.rdf4j.model.Literal rdf4jLiteral) {
    super(rdf4jLiteral);
  }

  @Override
  public String getLexicalForm() {
    return getRDF4JValue().getLabel();
  }

  @Override
  public IRI getDatatype() {
    org.eclipse.rdf4j.model.IRI datatypeIRI = getRDF4JValue().getDatatype();
    return new RDF4JIRI(datatypeIRI);
  }

  @Override
  public Optional<String> getLanguageTag() {
    return getRDF4JValue().getLanguage().map(tag -> tag.toLowerCase(Locale.ROOT));
  }

  @Override
  org.eclipse.rdf4j.model.Literal getRDF4JValue() {
    return (org.eclipse.rdf4j.model.Literal) super.getRDF4JValue();
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
