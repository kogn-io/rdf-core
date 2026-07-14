// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.shacl.internal;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.util.Values;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * Internal adapter glue: converts a {@link ReadableGraph} to an RDF4J {@link Model}.
 * <strong>Not public API</strong> — lives in an {@code internal} package and may change
 * or move without notice; consumers must not depend on it.
 *
 * <p>Reimplements the small subset of {@code io.kogn.rdf.terms} to RDF4J term
 * conversion needed by this module, rather than depending on
 * {@code rdf-dataset-rdf4j}: SHACL validation is store-independent and must not pull in
 * the dataset adapter.</p>
 */
public final class GraphModelConverter {

  private GraphModelConverter() {
    // Utility class - no instantiation
  }

  /**
   * Converts a graph to an in-memory RDF4J {@link Model}.
   *
   * @param graph the graph to convert; must not be {@code null}
   * @return an equivalent {@link LinkedHashModel}
   */
  public static Model toModel(ReadableGraph graph) {
    Model model = new LinkedHashModel();
    graph.stream()
        .forEach(triple -> model.add(toResource(triple.getSubject()), toRDF4JIRI(triple.getPredicate()),
            toValue(triple.getObject())));
    return model;
  }

  private static Resource toResource(BlankNodeOrIRI term) {
    if (term instanceof IRI iri) {
      return Values.iri(iri.getIRIString());
    }
    if (term instanceof BlankNode blankNode) {
      return Values.bnode(blankNode.uniqueReference());
    }
    throw new IllegalArgumentException("Unsupported BlankNodeOrIRI type: " + term.getClass());
  }

  private static org.eclipse.rdf4j.model.IRI toRDF4JIRI(IRI iri) {
    return Values.iri(iri.getIRIString());
  }

  private static Value toValue(RDFTerm term) {
    if (term instanceof IRI iri) {
      return Values.iri(iri.getIRIString());
    }
    if (term instanceof Literal literal) {
      if (literal.getLanguageTag().isPresent()) {
        return Values.literal(literal.getLexicalForm(), literal.getLanguageTag().get());
      }
      return Values.literal(literal.getLexicalForm(), toRDF4JIRI(literal.getDatatype()));
    }
    if (term instanceof BlankNode blankNode) {
      return Values.bnode(blankNode.uniqueReference());
    }
    throw new IllegalArgumentException("Unsupported RDFTerm type: " + term.getClass());
  }
}
