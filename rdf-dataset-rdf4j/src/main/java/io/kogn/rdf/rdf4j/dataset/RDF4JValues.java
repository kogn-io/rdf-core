// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;

import io.kogn.rdf.rdf4j.RDF4JFactory;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;

/**
 * Turns RDF4J values and models into port data-model terms and graphs, through the RDF4J-free
 * {@link RDF4JFactory}.
 */
final class RDF4JValues {

  private static final RDF4JFactory FACTORY = new RDF4JFactory();

  private RDF4JValues() {
    // Utility class - no instantiation
  }

  /**
   * Converts an RDF4J value to a term.
   *
   * @param value an RDF4J IRI, blank node or literal
   * @return the term
   * @throws IllegalArgumentException if the value is none of these
   */
  static RDFTerm toTerm(final org.eclipse.rdf4j.model.Value value) {
    if (value instanceof org.eclipse.rdf4j.model.IRI iri) {
      return FACTORY.createIRI(iri.stringValue());
    }
    if (value instanceof org.eclipse.rdf4j.model.BNode bnode) {
      return FACTORY.createBlankNode(bnode.getID());
    }
    if (value instanceof org.eclipse.rdf4j.model.Literal literal) {
      return literal.getLanguage()
          .map(tag -> FACTORY.createLiteral(literal.getLabel(), tag))
          .orElseGet(
              () -> FACTORY.createLiteral(literal.getLabel(), FACTORY.createIRI(literal.getDatatype().stringValue())));
    }
    throw new IllegalArgumentException("Unknown RDF4J Value type: " + value.getClass());
  }

  /**
   * Copies a model into a graph.
   *
   * @param model the model; its statements must hold no RDF 1.2 triple term
   * @return a graph holding the model's triples
   */
  static Graph toGraph(final Model model) {
    final Graph graph = FACTORY.createGraph();
    for (final Statement statement : model) {
      graph.add((BlankNodeOrIRI) toTerm(statement.getSubject()), (IRI) toTerm(statement.getPredicate()),
          toTerm(statement.getObject()));
    }
    return graph;
  }
}
