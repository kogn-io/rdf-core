// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.Objects;

import org.eclipse.rdf4j.common.net.ParsedIRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;

import io.kogn.rdf.dataset.GraphImport;
import io.kogn.rdf.dataset.RDFImportException;
import io.kogn.rdf.rdf4j.RDF4JFactory;
import io.kogn.rdf.rdf4j.internal.RDF4JConverters;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;

/**
 * RDF4J-based implementation of {@link GraphImport}, parsing with {@link Rio}'s Turtle parser.
 *
 * <p>Unlike the other wrappers of this package it needs no {@code Repository}: parsing touches no
 * store, so the port is stateless and one instance can serve any number of threads.</p>
 *
 * <p><strong>Streamed into the graph.</strong> Every statement the parser reports is converted and
 * added to the result as it arrives; no intermediate RDF4J {@code Model} is built. The result is
 * the graph {@link RDF4JFactory#createGraph()} creates, the same kind the read paths of
 * {@link GraphStoreRdf4j} return; it accepts terms of any implementation, so a caller needs
 * nothing of RDF4J to use it.</p>
 *
 * <p><strong>Parser defaults.</strong> The parser runs with Rio's default settings: literals and
 * language tags are taken as written, not normalized, and blank-node labels are not preserved,
 * which is what keeps the blank nodes of two calls apart. A triple term is rejected while the
 * statement carrying it is handled, through the same check the read paths use
 * ({@code RDF4JConverters.requireNoTripleTerm}), so a document fails at its first triple term
 * rather than after it has been read completely.</p>
 */
public class GraphImportRdf4j implements GraphImport {

  private static final RDF4JFactory FACTORY = new RDF4JFactory();

  /** Creates the import port; it holds no state. */
  public GraphImportRdf4j() {
    // Stateless: every call creates its own parser.
  }

  @Override
  public Graph readTurtle(final InputStream in, final IRI base) {
    Objects.requireNonNull(in, "in must not be null");
    Objects.requireNonNull(base, "base must not be null");
    final String baseIRI = requireAbsolute(base);
    final Graph graph = FACTORY.createGraph();
    final RDFParser parser = Rio.createParser(RDFFormat.TURTLE);
    parser.setRDFHandler(new GraphCollector(graph));
    try {
      parser.parse(in, baseIRI);
    } catch (final IOException | RuntimeException e) {
      // IOException is the caller's stream failing; RDFParseException a broken document;
      // IllegalStateException the triple-term check in GraphCollector; any other RDF4J runtime
      // type (UnsupportedRDFormatException if a consumer excludes the Turtle parser) is caught
      // too, so that no backend type leaks through the port. Argument validation ran before
      // this block, so the broad catch cannot mask a NullPointerException or an
      // IllegalArgumentException of the call.
      throw new RDFImportException("failed to import the Turtle document: " + e.getMessage(), e);
    }
    return graph;
  }

  /**
   * Checks that the base is an absolute IRI in the sense of RFC 3987.
   *
   * @param base the base passed by the caller
   * @return its IRI string
   * @throws IllegalArgumentException if it is not an absolute IRI
   */
  private static String requireAbsolute(final IRI base) {
    final String iri = base.getIRIString();
    final ParsedIRI parsed;
    try {
      parsed = new ParsedIRI(iri);
    } catch (final URISyntaxException e) {
      throw new IllegalArgumentException("base is not a valid IRI: " + iri, e);
    }
    if (!parsed.isAbsolute()) {
      throw new IllegalArgumentException("base is not an absolute IRI: " + iri);
    }
    return iri;
  }

  /** Adds every statement the parser reports to the result graph, rejecting triple terms. */
  private static final class GraphCollector extends AbstractRDFHandler {

    private final Graph graph;

    GraphCollector(final Graph graph) {
      this.graph = graph;
    }

    @Override
    public void handleStatement(final Statement statement) {
      RDF4JConverters.requireNoTripleTerm(statement.getSubject(), "subject");
      RDF4JConverters.requireNoTripleTerm(statement.getObject(), "object of " + statement.getPredicate());
      graph.add((BlankNodeOrIRI) RDF4JValues.toTerm(statement.getSubject()),
          (IRI) RDF4JValues.toTerm(statement.getPredicate()), RDF4JValues.toTerm(statement.getObject()));
    }
  }
}
