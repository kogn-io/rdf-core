// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import java.util.List;
import java.util.Optional;

import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.RDFCollections;
import org.eclipse.rdf4j.model.util.Values;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFList;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.Triple;

/**
 * RDF4J-based implementation of the RDF factory.
 */
public class RDF4JFactory implements RDF {

  private final ValueFactory valueFactory;

  public RDF4JFactory() {
    this.valueFactory = SimpleValueFactory.getInstance();
  }

  @Override
  public IRI createIRI(String iri) {
    return new RDF4JIRI(valueFactory.createIRI(iri));
  }

  @Override
  public Literal createLiteral(String lexicalForm) {
    return new RDF4JLiteral(valueFactory.createLiteral(lexicalForm));
  }

  @Override
  public Literal createLiteral(String lexicalForm, String languageTag) {
    return new RDF4JLiteral(valueFactory.createLiteral(lexicalForm, languageTag));
  }

  @Override
  public Literal createLiteral(String lexicalForm, IRI datatype) {
    org.eclipse.rdf4j.model.IRI rdf4jDatatype = RDF4JConverters.toRDF4JIRI(datatype);
    return new RDF4JLiteral(valueFactory.createLiteral(lexicalForm, rdf4jDatatype));
  }

  @Override
  public BlankNode createBlankNode() {
    return new RDF4JBlankNode(valueFactory.createBNode());
  }

  @Override
  public BlankNode createBlankNode(String identifier) {
    return new RDF4JBlankNode(valueFactory.createBNode(identifier));
  }

  @Override
  public Triple createTriple(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
    return new RDF4JTriple(toRDF4JResource(subject), toRDF4JIRI(predicate), toRDF4JValue(object));
  }

  @Override
  public Graph createGraph() {
    return new RDF4JGraph();
  }

  /**
   * Converts our IRI to RDF4J IRI.
   */
  private org.eclipse.rdf4j.model.IRI toRDF4JIRI(IRI iri) {
    if (!(iri instanceof RDF4JIRI)) {
      return Values.iri(iri.getIRIString());
      // throw new IllegalArgumentException("IRI must be an RDF4JIRI instance, but is: " + iri.getClass().getName());
    }
    return ((RDF4JIRI) iri).getRDF4JValue();
  }

  /**
   * Converts our BlankNodeOrIRI to RDF4J Resource.
   */
  private org.eclipse.rdf4j.model.Resource toRDF4JResource(BlankNodeOrIRI term) {
    if (term instanceof RDF4JIRI) {
      return ((RDF4JIRI) term).getRDF4JValue();
    } else if (term instanceof RDF4JBlankNode) {
      return ((RDF4JBlankNode) term).getRDF4JValue();
    }
    throw new IllegalArgumentException("Unknown BlankNodeOrIRI type: " + term.getClass());
  }

  /**
   * Converts our RDFTerm to RDF4J Value.
   */
  private org.eclipse.rdf4j.model.Value toRDF4JValue(RDFTerm term) {
    if (term instanceof RDF4JIRI) {
      return ((RDF4JIRI) term).getRDF4JValue();
    } else if (term instanceof RDF4JLiteral) {
      return ((RDF4JLiteral) term).getRDF4JValue();
    } else if (term instanceof RDF4JBlankNode) {
      return ((RDF4JBlankNode) term).getRDF4JValue();
    } else if (term instanceof IRI) {
      return Values.iri(((IRI) term).getIRIString());
    } else if (term instanceof Literal) {
      Literal literal = (Literal) term;
      Optional<String> languageTag = literal.getLanguageTag();
      if (languageTag.isPresent()) {
        return Values.literal(literal.getLexicalForm(), languageTag.get());
      }
      return Values.literal(literal.getLexicalForm(), Values.iri(literal.getDatatype().getIRIString()));
    } else if (term instanceof BlankNode) {
      return Values.bnode(((BlankNode) term).uniqueReference());
    }
    throw new IllegalArgumentException("Unknown RDFTerm type: " + term.getClass());
  }

  @Override
  public RDFList createRDFList(List<RDFTerm> items) {
    if (items == null || items.isEmpty()) {
      return RDFList.empty();
    }

    // Convert RDF terms to RDF4J Values
    List<Value> values = items.stream().map(this::toRDF4JValue).toList();

    // Create RDF collection with head node
    Resource listHead = Values.bnode();
    org.eclipse.rdf4j.model.Model model = RDFCollections.asRDF(values, listHead, new LinkedHashModel());

    // Wrap in abstractions
    Graph listGraph = new RDF4JGraph(model);
    BlankNode head = new RDF4JBlankNode((org.eclipse.rdf4j.model.BNode) listHead);

    return new RDFList(head, listGraph);
  }
}
