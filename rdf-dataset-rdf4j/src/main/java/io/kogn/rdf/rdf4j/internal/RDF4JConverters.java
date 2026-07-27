// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.internal;

import java.util.Objects;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.util.Values;

import io.kogn.rdf.rdf4j.RDF4JIRI;
import io.kogn.rdf.rdf4j.RDF4JTerm;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;

/**
 * Internal adapter glue: converts between the {@code io.kogn.rdf.terms} API types and the RDF4J
 * value model. <strong>Not public API</strong> — this class lives in an {@code internal} package
 * and may change or move without notice; consumers must not depend on it.
 *
 * <p>The static conversion methods support both native RDF4J implementations (direct access) and
 * foreign implementations (conversion via string representation).</p>
 */
public final class RDF4JConverters {

  private RDF4JConverters() {
    // Utility class - no instantiation
  }

  /**
   * Converts an IRI from our API to RDF4J IRI.
   * Supports both RDF4JIRI (direct access) and foreign implementations (conversion).
   *
   * @param iri the IRI to convert; must not be {@code null}
   * @return RDF4J IRI
   * @throws NullPointerException if {@code iri} is {@code null} — checked explicitly so the
   *     failure names the violated precondition, rather than surfacing as a bare NPE out of
   *     {@code iri.getIRIString()} below
   */
  public static org.eclipse.rdf4j.model.IRI toRDF4JIRI(IRI iri) {
    Objects.requireNonNull(iri, "iri must not be null");
    if (iri instanceof RDF4JIRI) {
      return ((RDF4JIRI) iri).getRDF4JValue();
    }
    // Convert foreign IRI implementation by re-creating from IRI string
    return Values.iri(iri.getIRIString());
  }

  /**
   * Converts a BlankNodeOrIRI from our API to RDF4J Resource.
   * Supports both RDF4J implementations and foreign implementations.
   *
   * @param resource the resource to convert; must not be {@code null}
   * @return RDF4J Resource
   * @throws NullPointerException if {@code resource} is {@code null} — checked explicitly so the
   *     failure names the violated precondition, rather than surfacing as a bare NPE out of
   *     {@code resource.getClass()} on the unreachable fallback below
   */
  public static org.eclipse.rdf4j.model.Resource toRDF4JResource(BlankNodeOrIRI resource) {
    Objects.requireNonNull(resource, "resource must not be null");
    if (resource instanceof RDF4JTerm) {
      return (org.eclipse.rdf4j.model.Resource) ((RDF4JTerm) resource).getRDF4JValue();
    }
    // Convert foreign implementations
    if (resource instanceof IRI) {
      return Values.iri(((IRI) resource).getIRIString());
    }
    if (resource instanceof BlankNode) {
      return Values.bnode(((BlankNode) resource).uniqueReference());
    }
    throw new IllegalArgumentException("Unsupported BlankNodeOrIRI type: " + resource.getClass());
  }

  /**
   * Converts an RDFTerm from our API to RDF4J Value.
   * Supports both RDF4J implementations and foreign implementations.
   *
   * @param term the term to convert; must not be {@code null}
   * @return RDF4J Value
   * @throws NullPointerException if {@code term} is {@code null} — checked explicitly so the
   *     failure names the violated precondition, rather than surfacing as a bare NPE out of
   *     {@code term.getClass()} on the unreachable fallback below
   */
  public static Value toRDF4JValue(RDFTerm term) {
    Objects.requireNonNull(term, "term must not be null");
    if (term instanceof RDF4JTerm) {
      return ((RDF4JTerm) term).getRDF4JValue();
    }
    // Convert foreign implementations
    if (term instanceof IRI) {
      return Values.iri(((IRI) term).getIRIString());
    }
    if (term instanceof Literal) {
      Literal lit = (Literal) term;
      if (lit.getLanguageTag().isPresent()) {
        return Values.literal(lit.getLexicalForm(), lit.getLanguageTag().get());
      }
      if (lit.getDatatype() != null) {
        org.eclipse.rdf4j.model.IRI datatype = toRDF4JIRI(lit.getDatatype());
        return Values.literal(lit.getLexicalForm(), datatype);
      }
      return Values.literal(lit.getLexicalForm());
    }
    if (term instanceof BlankNode) {
      return Values.bnode(((BlankNode) term).uniqueReference());
    }
    throw new IllegalArgumentException("Unsupported RDFTerm type: " + term.getClass());
  }
}
