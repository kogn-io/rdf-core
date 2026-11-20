// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.eclipse.rdf4j.common.net.ParsedIRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.eclipse.rdf4j.rio.turtle.TurtleParser;

import io.kogn.rdf.dataset.GraphImport;
import io.kogn.rdf.dataset.RDFImportException;
import io.kogn.rdf.rdf4j.internal.RDF4JConverters;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.SimpleRdf;

/**
 * RDF4J-based implementation of {@link GraphImport}, parsing with RDF4J's Turtle parser.
 *
 * <p>Unlike the other wrappers of this package it needs no {@code Repository}: parsing touches no
 * store, so the port is stateless and one instance can serve any number of threads.</p>
 *
 * <p><strong>Streamed into a backend-free graph.</strong> Every statement the parser reports is
 * converted and added to the result as it arrives; no intermediate RDF4J {@code Model} is built.
 * The result is a {@link io.kogn.rdf.terms.SimpleGraph} of {@link SimpleRdf} terms, so it carries
 * no RDF4J type, and each term is converted once; the literals have already passed the store's
 * validation (below), so the graph can be handed to {@code GraphStoreRdf4j#add} as it is.</p>
 *
 * <p><strong>Parser settings.</strong> The parser runs with RDF4J's defaults: literal values are
 * not normalized, so a well-typed literal keeps its lexical form ({@code "042"^^xsd:integer} stays
 * {@code "042"}); language tags arrive lower-cased, as everywhere in this library (ADR-0026);
 * blank-node labels are not preserved, which is what keeps the blank nodes of two calls apart. The
 * bytes go through a strict UTF-8 decoder, so a byte sequence that is not UTF-8 fails the import
 * instead of turning into U+FFFD; a leading byte order mark is skipped.</p>
 *
 * <p><strong>Rejected content.</strong> A directional language string, an {@code rdf:langString}
 * literal without a language tag and a literal the store would refuse — checked through the same
 * conversion {@code GraphStoreRdf4j#add} applies, so an ill-typed literal such as
 * {@code "abc"^^xsd:integer} or a literal with an invalid language tag such as
 * {@code "x"@abcdefghi} fails here exactly when it would fail there — are refused while the
 * parser creates the literal, so the message carries the line the parser reports (RDF4J's Turtle
 * parser gives no column for these). A triple term is refused while the
 * statement carrying it is handled, through the same check the read paths use
 * ({@code RDF4JConverters.requireNoTripleTerm}); that message names the line the parser had
 * reached. Either way a document fails at its first such content, not after it has been read
 * completely.</p>
 */
public class GraphImportRdf4j implements GraphImport {

  private static final SimpleRdf TERMS = new SimpleRdf();
  private static final int BYTE_ORDER_MARK = 0xFEFF;
  private static final String FAILED = "failed to import the Turtle document: ";
  private static final String DIRECTIONAL = "RDF 1.2 directional language string is not supported by the port data model";

  /** Creates the import port; it holds no state. */
  public GraphImportRdf4j() {
    // Stateless: every call creates its own parser.
  }

  @Override
  public Graph readTurtle(final InputStream in, final IRI base) {
    Objects.requireNonNull(in, "in must not be null");
    Objects.requireNonNull(base, "base must not be null");
    return parse(in, requireAbsolute(base));
  }

  /**
   * {@inheritDoc}
   *
   * <p>The parser runs with no base at all. RDF4J's Turtle parser then refuses every relative
   * reference that no absolute {@code @base} of the document resolves, as a fatal parse error
   * carrying the line; it does not fall back to an empty or made-up base.</p>
   */
  @Override
  public Graph readTurtle(final InputStream in) {
    Objects.requireNonNull(in, "in must not be null");
    return parse(in, null);
  }

  /**
   * Parses the document; the one path behind both forms of {@code readTurtle}.
   *
   * @param in the caller's stream, already checked for {@code null}
   * @param baseIRI the absolute base IRI, already checked, or {@code null} for none
   * @return the graph of the complete document
   * @throws RDFImportException if the import fails for any reason
   */
  private static Graph parse(final InputStream in, final String baseIRI) {
    final Graph graph = TERMS.createGraph();
    final RDFParser parser = new GuardedTurtleParser();
    final GraphCollector collector = new GraphCollector(graph);
    parser.setRDFHandler(collector);
    parser.setParseLocationListener(collector::locate);
    try {
      parser.parse(strictUTF8(in), baseIRI);
    } catch (final RDFImportException e) {
      throw e;
    } catch (final IOException | RuntimeException e) {
      // IOException is the caller's stream failing or a byte sequence that is not UTF-8
      // (MalformedInputException); RDFParseException a broken document or a literal the
      // GuardedTurtleParser refuses; any other RDF4J runtime type is caught too, so that no
      // backend type leaks through the port. Argument validation ran before this block, so the
      // broad catch cannot mask a NullPointerException or an IllegalArgumentException of the
      // call.
      throw new RDFImportException(FAILED + e.getMessage(), e);
    }
    return graph;
  }

  /**
   * Wraps the caller's stream in a reader that fails on bytes that are not UTF-8, past a leading
   * byte order mark.
   *
   * @param in the caller's stream; the reader is never closed, so neither is the stream
   * @return the reader
   * @throws IOException if reading the first character fails
   */
  private static Reader strictUTF8(final InputStream in) throws IOException {
    final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    final PushbackReader reader = new PushbackReader(new InputStreamReader(in, decoder));
    // RDF4J skips a byte order mark only when it decodes the stream itself, so skip it here
    final int first = reader.read();
    if (first >= 0 && first != BYTE_ORDER_MARK) {
      reader.unread(first);
    }
    return reader;
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
    private long line = -1;

    GraphCollector(final Graph graph) {
      this.graph = graph;
    }

    /**
     * Records where the parser is, for the message of a rejection.
     *
     * @param lineNumber the current line, or -1 if unknown
     * @param columnNumber the current column, unused
     */
    void locate(final long lineNumber, final long columnNumber) {
      line = lineNumber;
    }

    @Override
    public void handleStatement(final Statement statement) {
      try {
        RDF4JConverters.requireNoTripleTerm(statement.getSubject(), "subject");
        RDF4JConverters.requireNoTripleTerm(statement.getObject(), "object of " + statement.getPredicate());
      } catch (final IllegalStateException e) {
        final String where = line < 0 ? "" : " [line " + line + "]";
        throw new RDFImportException(FAILED + e.getMessage() + where, e);
      }
      graph.add((BlankNodeOrIRI) RDF4JValues.toTerm(statement.getSubject(), TERMS),
          (IRI) RDF4JValues.toTerm(statement.getPredicate(), TERMS), RDF4JValues.toTerm(statement.getObject(), TERMS));
    }
  }

  /**
   * RDF4J's Turtle parser, refusing the literals it would otherwise create without complaint
   * although the data model cannot hold them: a directional language string ({@code "x"@en--ltr},
   * or one written as {@code "x"^^rdf:dirLangString}) and an {@code rdf:langString} without a
   * language tag. RDF4J rewrites the last two to {@code xsd:string} before any value factory or
   * handler sees them, so the check sits here, where the datatype is still as written. It also
   * refuses a literal the store would refuse, such as an ill-typed one or one with an invalid
   * language tag. A refusal is a fatal parse error and carries the line the parser reports.
   */
  private static final class GuardedTurtleParser extends TurtleParser {

    @Override
    protected Literal createLiteral(final String label, final String lang, final org.eclipse.rdf4j.model.IRI datatype,
        final long lineNo, final long columnNo) {
      if (RDF.DIRLANGSTRING.equals(datatype)) {
        reportFatalError(DIRECTIONAL + " (" + datatype + ")", lineNo, columnNo);
      }
      if (RDF.LANGSTRING.equals(datatype) && (lang == null || lang.isEmpty())) {
        reportFatalError("a literal of datatype " + datatype + " requires a language tag", lineNo, columnNo);
      }
      final Literal literal = super.createLiteral(label, lang, datatype, lineNo, columnNo);
      if (literal.getBaseDirection() != Literal.BaseDirection.NONE) {
        reportFatalError(DIRECTIONAL + " (@" + lang + ")", lineNo, columnNo);
      }
      requireStorable(literal, lineNo, columnNo);
      return literal;
    }

    /**
     * Refuses a literal the store would refuse, by running it through the very conversion
     * {@code GraphStoreRdf4j#add} applies ({@code RDF4JConverters.toRDF4JValue}, RDF4J's
     * validating literal creation), so the import is exactly as strict as the store and has no
     * rule of its own.
     */
    private void requireStorable(final Literal literal, final long lineNo, final long columnNo) {
      try {
        RDF4JConverters.toRDF4JValue(RDF4JValues.toTerm(literal, TERMS));
      } catch (final IllegalArgumentException e) {
        // neutral wording: the store's rule refuses an ill-typed value as well as an invalid language tag
        reportFatalError("literal " + literal + " is rejected by the store's rule: " + e.getMessage(), e, lineNo,
            columnNo);
      }
    }
  }
}
