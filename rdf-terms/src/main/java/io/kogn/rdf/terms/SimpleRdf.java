// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.kogn.rdf.terms.vocab.VocabRdf;

/**
 * Default library-free {@link RDF} factory backed by value-based term records.
 *
 * <p>Creates {@link SimpleIRI} IRIs, in-memory {@link SimpleGraph}s and the
 * pure-Java term implementations ({@code SimpleBlankNode}, {@code SimpleLiteral},
 * {@code SimpleTriple}) without depending on any RDF backend. This is the
 * reference implementation used when no backend-specific {@link RDF} is wired in.</p>
 */
public class SimpleRdf implements RDF {

  /** Creates a new {@code SimpleRdf} factory. */
  public SimpleRdf() {
  }

  @Override
  public IRI createIRI(String iri) {
    return new SimpleIRI(iri);
  }

  @Override
  public Literal createLiteral(String lexicalForm) {
    return new SimpleLiteral(lexicalForm, new SimpleIRI("http://www.w3.org/2001/XMLSchema#string"), null);
  }

  @Override
  public Literal createLiteral(String lexicalForm, String languageTag) {
    return new SimpleLiteral(lexicalForm, new SimpleIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"),
        languageTag);
  }

  @Override
  public Literal createLiteral(String lexicalForm, IRI datatype) {
    return new SimpleLiteral(lexicalForm, datatype, null);
  }

  @Override
  public BlankNode createBlankNode() {
    return new SimpleBlankNode(UUID.randomUUID().toString());
  }

  @Override
  public BlankNode createBlankNode(String identifier) {
    return new SimpleBlankNode(identifier);
  }

  @Override
  public Triple createTriple(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
    return new SimpleTriple(subject, predicate, object);
  }

  @Override
  public Graph createGraph() {
    return new SimpleGraph();
  }

  @Override
  public RDFList createRDFList(List<RDFTerm> items) {
    if (items == null || items.isEmpty()) {
      return RDFList.empty();
    }

    Graph graph = createGraph();
    BlankNode head = createBlankNode();
    BlankNode node = head;
    for (int i = 0; i < items.size(); i++) {
      graph.add(node, VocabRdf.FIRST, items.get(i));
      boolean last = i == items.size() - 1;
      RDFTerm rest = last ? VocabRdf.NIL : createBlankNode();
      graph.add(node, VocabRdf.REST, rest);
      if (!last) {
        node = (BlankNode) rest;
      }
    }
    return new RDFList(head, graph);
  }

  record SimpleBlankNode(String identifier) implements BlankNode {

    SimpleBlankNode {
      if (identifier == null || identifier.isEmpty()) {
        throw new IllegalArgumentException("BlankNode identifier must not be null or empty");
      }
    }

    @Override
    public String uniqueReference() {
      return identifier;
    }

    @Override
    public String ntriplesString() {
      return "_:" + identifier;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (!(obj instanceof BlankNode other))
        return false;
      return identifier.equals(other.uniqueReference());
    }

    @Override
    public int hashCode() {
      return identifier.hashCode();
    }

    @Override
    public String toString() {
      return ntriplesString();
    }
  }

  record SimpleLiteral(String lexicalForm, IRI datatype, String langTag) implements Literal {

    @Override
    public String getLexicalForm() {
      return lexicalForm;
    }

    @Override
    public IRI getDatatype() {
      return datatype;
    }

    @Override
    public Optional<String> getLanguageTag() {
      return Optional.ofNullable(langTag);
    }

    @Override
    public String ntriplesString() {
      if (langTag != null) {
        return "\"" + lexicalForm + "\"@" + langTag;
      }
      return "\"" + lexicalForm + "\"^^<" + datatype.getIRIString() + ">";
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (!(obj instanceof Literal other))
        return false;
      return lexicalForm.equals(other.getLexicalForm()) && datatype.equals(other.getDatatype())
          && getLanguageTag().equals(other.getLanguageTag());
    }

    @Override
    public int hashCode() {
      return Objects.hash(getLexicalForm(), getDatatype(), getLanguageTag());
    }

    @Override
    public String toString() {
      return ntriplesString();
    }
  }

  record SimpleTriple(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) implements Triple {

    @Override
    public BlankNodeOrIRI getSubject() {
      return subject;
    }

    @Override
    public IRI getPredicate() {
      return predicate;
    }

    @Override
    public RDFTerm getObject() {
      return object;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (!(obj instanceof Triple other))
        return false;
      return subject.equals(other.getSubject()) && predicate.equals(other.getPredicate())
          && object.equals(other.getObject());
    }

    @Override
    public int hashCode() {
      return Objects.hash(subject, predicate, object);
    }

    @Override
    public String toString() {
      return subject + " " + predicate + " " + object + " .";
    }
  }
}
