// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import java.util.List;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFList;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * An {@link RDF} factory that keeps a language tag as written, creating {@link AsWrittenLiteral}s, and delegates
 * everything else to {@link SimpleRdf}. Handed to the serializer, it leaves the lower-casing of ni-rdf/1 &sect;4.1 to
 * rdf-cid alone: with {@link SimpleRdf}, which lower-cases on creation, a test of that step stays green even if
 * rdf-cid drops it.
 */
public final class AsWrittenRdf implements RDF {

  private final RDF delegate = new SimpleRdf();

  @Override
  public IRI createIRI(String iri) {
    return delegate.createIRI(iri);
  }

  @Override
  public Literal createLiteral(String lexicalForm) {
    return delegate.createLiteral(lexicalForm);
  }

  @Override
  public Literal createLiteral(String lexicalForm, String languageTag) {
    return new AsWrittenLiteral(lexicalForm, languageTag);
  }

  @Override
  public Literal createLiteral(String lexicalForm, IRI datatype) {
    return delegate.createLiteral(lexicalForm, datatype);
  }

  @Override
  public BlankNode createBlankNode() {
    return delegate.createBlankNode();
  }

  @Override
  public BlankNode createBlankNode(String identifier) {
    return delegate.createBlankNode(identifier);
  }

  @Override
  public Triple createTriple(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
    return delegate.createTriple(subject, predicate, object);
  }

  @Override
  public Graph createGraph() {
    return delegate.createGraph();
  }

  @Override
  public RDFList createRDFList(List<RDFTerm> items) {
    return delegate.createRDFList(items);
  }
}
