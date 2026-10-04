// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import java.util.List;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import io.kogn.rdf.rdf4j.internal.RDF4JConverters;
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

  /** Creates a factory using RDF4J's default {@link SimpleValueFactory}. */
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
    return new RDF4JTriple(RDF4JConverters.toRDF4JResource(subject), RDF4JConverters.toRDF4JIRI(predicate),
        RDF4JConverters.toRDF4JValue(object));
  }

  @Override
  public Graph createGraph() {
    return new RDF4JGraph();
  }

  @Override
  public RDFList createRDFList(List<RDFTerm> items) {
    if (items == null || items.isEmpty()) {
      return RDFList.empty();
    }

    // Build the bare rdf:first/rdf:rest chain ourselves: RDFCollections.asRDF would add an
    // rdf:type rdf:List triple on the head, which SimpleRdf does not emit.
    Model model = new LinkedHashModel();
    BNode listHead = valueFactory.createBNode();
    BNode node = listHead;
    for (int i = 0; i < items.size(); i++) {
      model.add(node, org.eclipse.rdf4j.model.vocabulary.RDF.FIRST, RDF4JConverters.toRDF4JValue(items.get(i)));
      boolean last = i == items.size() - 1;
      Resource rest = last ? org.eclipse.rdf4j.model.vocabulary.RDF.NIL : valueFactory.createBNode();
      model.add(node, org.eclipse.rdf4j.model.vocabulary.RDF.REST, rest);
      if (!last) {
        node = (BNode) rest;
      }
    }

    Graph listGraph = new RDF4JGraph(model);
    BlankNode head = new RDF4JBlankNode(listHead);

    return new RDFList(head, listGraph);
  }
}
