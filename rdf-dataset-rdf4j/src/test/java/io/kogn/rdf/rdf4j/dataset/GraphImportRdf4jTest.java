// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.kogn.rdf.dataset.RDFImportException;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * {@link GraphImportRdf4j#readTurtle} turns a Turtle document into a graph of the port data model,
 * resolves relative references against the base, keeps blank nodes apart across calls, leaves the
 * stream open, and reports every failure as {@link RDFImportException} with the backend's signal
 * as cause.
 */
class GraphImportRdf4jTest {

  private static final String EX = "https://example.org/";
  private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

  private final RDF rdf = new SimpleRdf();
  private final IRI base = rdf.createIRI(EX + "doc/");
  private final GraphImportRdf4j graphImport = new GraphImportRdf4j();

  private final IRI s = rdf.createIRI(EX + "s");
  private final IRI p = rdf.createIRI(EX + "p");

  private static InputStream turtle(final String document) {
    return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("IRIs, plain, typed and language-tagged literals arrive as written")
  void readsTriplesOfEveryTermKind() {
    final String document = """
        @prefix ex: <https://example.org/> .
        ex:s ex:p ex:o , "plain" , "42"^^<http://www.w3.org/2001/XMLSchema#integer> , "Hallo"@de .
        """;

    final Graph graph = graphImport.readTurtle(turtle(document), base);

    assertThat(graph.size()).isEqualTo(4);
    assertThat(graph.contains(rdf.createTriple(s, p, rdf.createIRI(EX + "o")))).isTrue();
    assertThat(graph.contains(rdf.createTriple(s, p, rdf.createLiteral("plain")))).isTrue();
    assertThat(graph.contains(rdf.createTriple(s, p, rdf.createLiteral("42", rdf.createIRI(XSD + "integer")))))
        .isTrue();
    assertThat(graph.contains(rdf.createTriple(s, p, rdf.createLiteral("Hallo", "de")))).isTrue();
  }

  @Test
  @DisplayName("a relative reference resolves against the base")
  void resolvesRelativeReferencesAgainstTheBase() {
    final Graph graph = graphImport.readTurtle(turtle("<a> <https://example.org/p> <../b> ."), base);

    assertThat(graph.contains(rdf.createTriple(rdf.createIRI(EX + "doc/a"), p, rdf.createIRI(EX + "b")))).isTrue();
    assertThat(graph.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("an @base in the document takes precedence over the base argument")
  void documentBaseTakesPrecedence() {
    final String document = "@base <https://other.example/> . <a> <https://example.org/p> <b> .";

    final Graph graph = graphImport.readTurtle(turtle(document), base);

    assertThat(graph.contains(
        rdf.createTriple(rdf.createIRI("https://other.example/a"), p, rdf.createIRI("https://other.example/b"))))
        .isTrue();
  }

  @Test
  @DisplayName("a document without statements yields an empty graph")
  void emptyDocumentYieldsEmptyGraph() {
    final Graph graph = graphImport.readTurtle(turtle("@prefix ex: <https://example.org/> .\n# nothing\n"), base);

    assertThat(graph.isEmpty()).isTrue();
  }

  @Test
  @DisplayName("a triple stated twice is held once")
  void duplicateTriplesCollapse() {
    final Graph graph = graphImport.readTurtle(
        turtle(
            "<https://example.org/s> <https://example.org/p> 1 . <https://example.org/s> <https://example.org/p> 1 ."),
        base);

    assertThat(graph.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("the returned graph is the caller's to modify")
  void returnedGraphIsModifiable() {
    final Graph graph = graphImport.readTurtle(turtle("<https://example.org/s> <https://example.org/p> 1 ."), base);
    final Triple added = rdf.createTriple(s, p, rdf.createLiteral("more"));

    graph.add(added);

    assertThat(graph.contains(added)).isTrue();
    assertThat(graph.size()).isEqualTo(2);
  }

  @Test
  @DisplayName("one label is one blank node within a document, [] is a node of its own")
  void blankNodeLabelsAreScopedToTheDocument() {
    final String document = """
        _:x <https://example.org/p> _:x .
        [] <https://example.org/p> 1 .
        """;

    final Graph graph = graphImport.readTurtle(turtle(document), base);

    final List<Triple> triples = graph.stream().toList();
    assertThat(triples).hasSize(2);
    final Triple selfLoop = graph.stream(null, p, null)
        .filter(t -> t.getObject() instanceof BlankNode)
        .findFirst()
        .orElseThrow();
    assertThat(selfLoop.getSubject()).isEqualTo(selfLoop.getObject());
    final Set<RDFTerm> subjects = triples.stream().map(Triple::getSubject).collect(Collectors.toSet());
    assertThat(subjects).hasSize(2).allMatch(BlankNode.class::isInstance);
  }

  @Test
  @DisplayName("reading the same document twice yields distinct blank nodes, which do not merge in a store")
  void blankNodesAreNotSharedAcrossCalls() {
    final String document = "_:x <https://example.org/p> 1 .";

    final Graph first = graphImport.readTurtle(turtle(document), base);
    final Graph second = graphImport.readTurtle(turtle(document), base);

    final RDFTerm firstNode = first.stream().findFirst().orElseThrow().getSubject();
    final RDFTerm secondNode = second.stream().findFirst().orElseThrow().getSubject();
    assertThat(firstNode).isNotEqualTo(secondNode);

    final Repository repository = new SailRepository(new MemoryStore());
    repository.init();
    try {
      final GraphStoreRdf4j store = new GraphStoreRdf4j(repository);
      final IRI named = rdf.createIRI(EX + "g");
      store.add(named, first);
      store.add(named, second);
      assertThat(store.count(named)).isEqualTo(2);
    } finally {
      repository.shutDown();
    }
  }

  @Test
  @DisplayName("the stream is read but not closed")
  void leavesTheStreamOpen() {
    final CloseTrackingStream in = new CloseTrackingStream("<https://example.org/s> <https://example.org/p> 1 .");

    graphImport.readTurtle(in, base);

    assertThat(in.closed).isFalse();
  }

  @Test
  @DisplayName("a syntax error is an RDFImportException carrying the backend's signal and the line")
  void syntaxErrorIsImportException() {
    assertThatThrownBy(() -> graphImport.readTurtle(turtle("<https://example.org/s> <https://example.org/p> ."), base))
        .isExactlyInstanceOf(RDFImportException.class)
        .hasMessageContaining("line 1")
        .cause()
        .isNotNull();
  }

  @Test
  @DisplayName("a TriG graph block is not Turtle")
  void trigGraphBlockIsRejected() {
    final String document = "<https://example.org/g> { <https://example.org/s> <https://example.org/p> 1 . }";

    assertThatThrownBy(() -> graphImport.readTurtle(turtle(document), base))
        .isExactlyInstanceOf(RDFImportException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "<https://example.org/s> <https://example.org/p> <<( <https://example.org/a> <https://example.org/b> 1 )>> .",
      "<< <https://example.org/a> <https://example.org/b> 1 >> <https://example.org/p> 2 .",
      "<https://example.org/a> <https://example.org/b> 1 {| <https://example.org/p> 2 |} ."})
  @DisplayName("an RDF 1.2 triple term, however it is written, is rejected")
  void tripleTermIsRejected(final String document) {
    assertThatThrownBy(() -> graphImport.readTurtle(turtle(document), base))
        .isExactlyInstanceOf(RDFImportException.class)
        .hasMessageContaining("triple term");
  }

  @Test
  @DisplayName("a failing stream is an RDFImportException with the IOException as cause")
  void ioErrorIsImportException() {
    final InputStream failing = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("disk gone");
      }
    };

    assertThatThrownBy(() -> graphImport.readTurtle(failing, base)).isExactlyInstanceOf(RDFImportException.class)
        .rootCause()
        .isInstanceOf(IOException.class)
        .hasMessage("disk gone");
  }

  @ParameterizedTest
  // each value holds a ':' so SimpleIRI accepts it, yet none is an absolute IRI
  @ValueSource(strings = {"relative/path:x", "//example.org/no-scheme:x", "https://example.org/has space",
      "https://example.org/%zz"})
  @DisplayName("a base that is not an absolute IRI is an IllegalArgumentException, before the document is read")
  void baseMustBeAnAbsoluteIRI(final String invalid) {
    final CloseTrackingStream in = new CloseTrackingStream("<https://example.org/s> <https://example.org/p> 1 .");

    assertThatThrownBy(() -> graphImport.readTurtle(in, rdf.createIRI(invalid)))
        .isExactlyInstanceOf(IllegalArgumentException.class);
    assertThat(in.read).isFalse();
  }

  @Test
  @DisplayName("a base with a fragment is accepted, the fragment plays no part")
  void baseWithFragmentIsAccepted() {
    final Graph graph = graphImport.readTurtle(turtle("<a> <https://example.org/p> 1 ."),
        rdf.createIRI(EX + "doc/x#frag"));

    assertThat(graph.contains(
        rdf.createTriple(rdf.createIRI(EX + "doc/a"), p, rdf.createLiteral("1", rdf.createIRI(XSD + "integer")))))
        .isTrue();
  }

  /** A stream over a fixed document that records whether it was read from or closed. */
  private static final class CloseTrackingStream extends ByteArrayInputStream {

    private boolean closed;
    private boolean read;

    CloseTrackingStream(final String document) {
      super(document.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public synchronized int read() {
      read = true;
      return super.read();
    }

    @Override
    public synchronized int read(final byte[] b, final int off, final int len) {
      read = true;
      return super.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
