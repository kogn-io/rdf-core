// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.cid.ContentAddressedIriGenerator;
import io.kogn.rdf.cid.ContentAddressedIriGeneratorSexpr;
import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleGraph;
import io.kogn.rdf.terms.SimpleIRI;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * The fragment index of a versioned dataset (issue #226): the library records the fragment IRIs
 * of each slice it writes in the history graph and reads a slice subject by subject, never by
 * exporting a graph.
 */
class ResourceVersioningFragmentIndexTest {

  private static final IRI CONTENT = new SimpleIRI("https://example.org/graph/content");
  private static final IRI A = new SimpleIRI("https://example.org/res/A");
  private static final IRI A_X = new SimpleIRI("https://example.org/res/A#x");
  private static final IRI A_Y = new SimpleIRI("https://example.org/res/A#y");
  private static final IRI A_Z = new SimpleIRI("https://example.org/res/A#z");
  private static final IRI B = new SimpleIRI("https://example.org/res/B");
  private static final IRI B_X = new SimpleIRI("https://example.org/res/B#x");
  private static final IRI P = new SimpleIRI("https://example.org/vocab/p");

  private final SimpleRdf rdf = new SimpleRdf();
  private final ContentAddressedIriGenerator generator = new ContentAddressedIriGeneratorSexpr();
  private Repository repository;
  private DatasetTransactor transactor;
  private ResourceVersioning versioning;

  @BeforeEach
  void setUp() {
    repository = new SailRepository(new MemoryStore());
    repository.init();
    transactor = new DatasetTransactorRdf4j(repository);
    versioning = new ResourceVersioningNamedGraphs(CONTENT);
  }

  @AfterEach
  void tearDown() {
    repository.shutDown();
  }

  @Test
  void aFragmentSubjectWithoutALinkFromTheProxyIriIsInTheDraftThePublishedViewAndTheVersion() {
    final Graph content = graph(triple(A, "one"), triple(A_X, "part"));
    draft(A, content);
    final IRI cid = generator.generateIri(A, content);

    publish(A);

    assertThat(read(tx -> versioning.readSlice(tx, A).triples()).contains(triple(A_X, "part"))).isTrue();
    assertThat(read(tx -> versioning.readPublished(tx, A)).contains(triple(A_X, "part"))).isTrue();
    final ReadableGraph version = read(tx -> versioning.readVersion(tx, cid).orElseThrow());
    assertThat(version.contains(triple(new SimpleIRI(cid.getIRIString() + "#x"), "part"))).isTrue();
    assertThat(version.size()).isEqualTo(2);
  }

  @Test
  void aSliceOfFragmentSubjectsOnlyIsReadPublishedAndStored() {
    final Graph content = graph(triple(A_X, "part"));
    draft(A, content);
    final IRI cid = generator.generateIri(A, content);

    publish(A);

    assertThat(read(tx -> versioning.readSlice(tx, A).triples()).size()).isEqualTo(1);
    assertThat(read(tx -> versioning.readPublished(tx, A)).size()).isEqualTo(1);
    assertThat(read(tx -> versioning.readVersion(tx, cid)).isPresent()).isTrue();
    assertThat(publishStateOf(A)).isEqualTo(PublishState.PUBLISHED_UNCHANGED);
  }

  @Test
  void aFragmentLeftOutOfTheNextWriteLeavesTheContentGraphAndTheIndex() {
    draft(A, graph(triple(A, "one"), triple(A_X, "x"), triple(A_Y, "y")));
    assertThat(indexed(A, VocabVersioning.DRAFT_FRAGMENT)).containsExactlyInAnyOrder(A_X, A_Y);

    draft(A, graph(triple(A, "one"), triple(A_X, "x")));

    assertThat(holdsSubject(CONTENT, A_Y)).isFalse();
    assertThat(countIn(CONTENT)).isEqualTo(2);
    assertThat(indexed(A, VocabVersioning.DRAFT_FRAGMENT)).containsExactly(A_X);
  }

  @Test
  void aWriteThatNamesAFragmentForTheFirstTimeReplacesWhatWasWrittenOnItPastTheLibrary() {
    final Graph foreign = graph(triple(A_Z, "old"));
    transactor.inTransaction(tx -> tx.add(CONTENT, foreign));
    final Graph content = graph(triple(A, "one"), triple(A_Z, "new"));

    draft(A, content);

    final ReadableGraph slice = read(tx -> versioning.readSlice(tx, A).triples());
    assertThat(slice.size()).isEqualTo(2);
    assertThat(slice.contains(triple(A_Z, "new"))).isTrue();
    assertThat(slice.contains(triple(A_Z, "old"))).isFalse();
    assertThat(countIn(CONTENT)).isEqualTo(2);
  }

  @Test
  void aPublishReplacesThePublishedFragmentsWithThoseOfTheNewVersion() {
    draft(A, graph(triple(A, "one"), triple(A_X, "x"), triple(A_Y, "y")));
    publish(A);
    draft(A, graph(triple(A, "two"), triple(A_X, "x")));

    publish(A);

    assertThat(indexed(A, VocabVersioning.PUBLISHED_FRAGMENT)).containsExactly(A_X);
    assertThat(holdsSubject(VersioningGraphs.PUBLISHED, A_Y)).isFalse();
    assertThat(read(tx -> versioning.readPublished(tx, A)).size()).isEqualTo(2);
  }

  @Test
  void aPublishThatNamesAFragmentReplacesWhatWasWrittenOnItInThePublishedViewPastTheLibrary() {
    final Graph foreign = graph(triple(A_Y, "foreign"));
    transactor.inTransaction(tx -> tx.add(VersioningGraphs.PUBLISHED, foreign));
    draft(A, graph(triple(A, "one"), triple(A_Y, "y")));

    publish(A);

    final ReadableGraph published = read(tx -> versioning.readPublished(tx, A));
    assertThat(published.size()).isEqualTo(2);
    assertThat(published.contains(triple(A_Y, "y"))).isTrue();
    assertThat(countIn(VersioningGraphs.PUBLISHED)).isEqualTo(2);
  }

  @Test
  void aPublishReplacesAPublishedFragmentThatHasNoIndexEntry() {
    draft(A, graph(triple(A, "one"), triple(A_X, "v1")));
    publish(A);
    final Graph index = new SimpleGraph();
    index.add(A, VocabVersioning.PUBLISHED_FRAGMENT, A_X);
    transactor.inTransaction(tx -> tx.remove(VersioningGraphs.HISTORY, index));
    draft(A, graph(triple(A, "one"), triple(A_X, "v2")));

    publish(A);

    final ReadableGraph published = read(tx -> versioning.readPublished(tx, A));
    assertThat(published.contains(triple(A_X, "v2"))).isTrue();
    assertThat(published.contains(triple(A_X, "v1"))).isFalse();
    assertThat(countIn(VersioningGraphs.PUBLISHED)).isEqualTo(2);
  }

  @Test
  void aDeleteClearsTheDraftAndPublishedIndexAndItsTombstoneCarriesTheVersionFragments() {
    final Graph content = graph(triple(A, "one"), triple(A_X, "x"));
    draft(A, content);
    publish(A);
    final IRI cid = generator.generateIri(A, content);

    final IRI tombstone = transactor.inTransaction(
        tx -> versioning.delete(tx, A, versioning.readSlice(tx, A).token(), PublishDetails.NONE).entry());

    assertThat(indexed(A, VocabVersioning.DRAFT_FRAGMENT)).isEmpty();
    assertThat(indexed(A, VocabVersioning.PUBLISHED_FRAGMENT)).isEmpty();
    assertThat(countIn(CONTENT)).isZero();
    assertThat(countIn(VersioningGraphs.PUBLISHED)).isZero();
    assertThat(indexed(tombstone, VocabVersioning.VERSION_FRAGMENT))
        .containsExactly(new SimpleIRI(cid.getIRIString() + "#x"));
    assertThat(read(tx -> versioning.readVersion(tx, cid).orElseThrow()).size()).isEqualTo(2);
    assertThat(read(tx -> versioning.readVersion(tx, A, 1).orElseThrow()).size()).isEqualTo(2);
  }

  @Test
  void aPublishOfContentAlreadyStoredRecordsTheVersionFragmentsOnItsOwnEntry() {
    final Graph contentOfA = graph(triple(A, "same"), triple(A_X, "part"));
    final Graph contentOfB = graph(triple(B, "same"), triple(B_X, "part"));
    draft(A, contentOfA);
    draft(B, contentOfB);
    final IRI cid = generator.generateIri(A, contentOfA);
    assertThat(generator.generateIri(B, contentOfB)).isEqualTo(cid);
    publish(A);

    final RecordingTx recorded = transactor.inTransaction(tx -> {
      final RecordingTx recording = new RecordingTx(tx);
      versioning.publish(recording, List.of(versioning.readSlice(recording, B).token()), PublishDetails.NONE);
      return recording;
    });
    final IRI entryOfB = read(tx -> versioning.history(tx, B).getLast().iri());

    assertThat(recorded.addedTo(VersioningGraphs.CONTENT_STORE)).isEmpty();
    assertThat(indexed(entryOfB, VocabVersioning.VERSION_FRAGMENT))
        .containsExactly(new SimpleIRI(cid.getIRIString() + "#x"));
    assertThat(read(tx -> tx.match(VersioningGraphs.HISTORY, cid, null, null)).isEmpty()).isTrue();
    assertThat(read(tx -> versioning.readVersion(tx, B, 1).orElseThrow()).size()).isEqualTo(2);
  }

  @Test
  void aVersionFragmentOutsideTheEntrysCidIsRejectedAsMalformed() {
    draft(A, graph(triple(A, "one"), triple(A_X, "x")));
    final IRI entry = transactor
        .inTransaction(tx -> versioning.publish(tx, List.of(versioning.readSlice(tx, A).token()), PublishDetails.NONE))
        .entries()
        .get(A);
    final Graph foreign = new SimpleGraph();
    foreign.add(entry, VocabVersioning.VERSION_FRAGMENT, B_X);
    transactor.inTransaction(tx -> tx.add(VersioningGraphs.HISTORY, foreign));

    assertThatThrownBy(() -> read(tx -> versioning.history(tx, A))).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("malformed history entry");
  }

  @Test
  void aMalformedFragmentIndexIsRejected() {
    final Graph index = new SimpleGraph();
    index.add(A, VocabVersioning.DRAFT_FRAGMENT, B_X);
    transactor.inTransaction(tx -> tx.add(VersioningGraphs.HISTORY, index));

    assertThatThrownBy(() -> read(tx -> versioning.readSlice(tx, A))).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fragment index");
  }

  @Test
  void noOperationReadsAWholeGraph() {
    final BlankNode node = rdf.createBlankNode();
    final Graph content = graph(triple(A, "one"), triple(A_X, "x"), triple(node, "deep"));
    content.add(A, P, node);
    final IRI cid = generator.generateIri(A, content);

    final RecordingTx recorded = transactor.inTransaction(tx -> {
      final RecordingTx recording = new RecordingTx(tx);
      versioning.bindContentGraph(recording);
      final RevisionToken first = versioning.replaceSlice(recording, A, content,
          versioning.readSlice(recording, A).token());
      final PublishResult published = versioning.publish(recording, List.of(first), PublishDetails.NONE);
      versioning.readPublished(recording, A);
      versioning.readVersion(recording, cid);
      versioning.readVersion(recording, A, 1);
      versioning.history(recording, A);
      versioning.publishState(recording, A);
      versioning.delete(recording, A, published.tokens().get(A), PublishDetails.NONE);
      return recording;
    });

    assertThat(recorded.exported).isEmpty();
    assertThat(recorded.counted).isEmpty();
    assertThat(recorded.unboundPatterns).isEmpty();
  }

  private Set<IRI> indexed(final IRI base, final IRI predicate) {
    return read(tx -> tx.match(VersioningGraphs.HISTORY, base, predicate, null)
        .stream()
        .map(t -> (IRI) t.getObject())
        .collect(Collectors.toSet()));
  }

  private boolean holdsSubject(final IRI graph, final IRI subject) {
    return transactor.inTransaction(tx -> tx.contains(graph, subject, null, null));
  }

  private long countIn(final IRI graph) {
    return transactor.inTransaction(tx -> tx.count(graph));
  }

  private PublishState publishStateOf(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.publishState(tx, proxy));
  }

  private <T> T read(final Function<DatasetTx, T> work) {
    return transactor.inTransaction(work);
  }

  private Triple triple(final BlankNodeOrIRI subject, final String value) {
    return rdf.createTriple(subject, P, rdf.createLiteral(value));
  }

  private static Graph graph(final Triple... triples) {
    final Graph graph = new SimpleGraph();
    for (final Triple triple : triples) {
      graph.add(triple);
    }
    return graph;
  }

  private void draft(final IRI proxy, final ReadableGraph content) {
    transactor
        .inTransaction(tx -> versioning.replaceSlice(tx, proxy, content, versioning.readSlice(tx, proxy).token()));
  }

  private void publish(final IRI proxy) {
    transactor.inTransaction(
        tx -> versioning.publish(tx, List.of(versioning.readSlice(tx, proxy).token()), PublishDetails.NONE));
  }

  /** Passes every call to the transaction it wraps and records the graphs exported and added. */
  private static final class RecordingTx implements DatasetTx {

    private final DatasetTx delegate;
    private final List<IRI> exported = new ArrayList<>();
    private final List<String> counted = new ArrayList<>();
    /** Every {@code contains}/{@code match} whose subject and object are both wildcards. */
    private final List<String> unboundPatterns = new ArrayList<>();
    private final List<Map.Entry<IRI, Triple>> added = new ArrayList<>();

    RecordingTx(final DatasetTx delegate) {
      this.delegate = delegate;
    }

    List<Triple> addedTo(final IRI graph) {
      return added.stream().filter(entry -> entry.getKey().equals(graph)).map(Map.Entry::getValue).toList();
    }

    @Override
    public long add(final IRI namedGraph, final ReadableGraph triples) {
      triples.stream().forEach(triple -> added.add(Map.entry(namedGraph, triple)));
      return delegate.add(namedGraph, triples);
    }

    @Override
    public long remove(final IRI namedGraph, final ReadableGraph triples) {
      return delegate.remove(namedGraph, triples);
    }

    @Override
    public void clear(final IRI namedGraph) {
      delegate.clear(namedGraph);
    }

    @Override
    public ReadableGraph export(final IRI namedGraph) {
      exported.add(namedGraph);
      return delegate.export(namedGraph);
    }

    @Override
    public long count(final IRI namedGraph) {
      counted.add(namedGraph.ntriplesString());
      return delegate.count(namedGraph);
    }

    @Override
    public long count() {
      counted.add("all graphs");
      return delegate.count();
    }

    @Override
    public boolean contains(final IRI namedGraph, final BlankNodeOrIRI subject, final IRI predicate,
        final RDFTerm object) {
      recordUnbound("contains", namedGraph, subject, predicate, object);
      return delegate.contains(namedGraph, subject, predicate, object);
    }

    @Override
    public ReadableGraph match(final IRI namedGraph, final BlankNodeOrIRI subject, final IRI predicate,
        final RDFTerm object) {
      recordUnbound("match", namedGraph, subject, predicate, object);
      return delegate.match(namedGraph, subject, predicate, object);
    }

    private void recordUnbound(final String operation, final IRI namedGraph, final BlankNodeOrIRI subject,
        final IRI predicate, final RDFTerm object) {
      if (subject == null && object == null) {
        unboundPatterns.add(operation + " " + namedGraph.ntriplesString() + " "
            + (predicate == null ? "*" : predicate.ntriplesString()));
      }
    }

    @Override
    public void update(final String sparql) {
      delegate.update(sparql);
    }

    @Override
    public void update(final String sparql, final Map<String, RDFTerm> bindings) {
      delegate.update(sparql, bindings);
    }

    @Override
    public Stream<BindingSet> select(final String sparql) {
      return delegate.select(sparql);
    }

    @Override
    public Stream<BindingSet> select(final String sparql, final Map<String, RDFTerm> bindings) {
      return delegate.select(sparql, bindings);
    }

    @Override
    public ReadableGraph construct(final String sparql) {
      return delegate.construct(sparql);
    }

    @Override
    public ReadableGraph construct(final String sparql, final Map<String, RDFTerm> bindings) {
      return delegate.construct(sparql, bindings);
    }

    @Override
    public boolean ask(final String sparql) {
      return delegate.ask(sparql);
    }

    @Override
    public boolean ask(final String sparql, final Map<String, RDFTerm> bindings) {
      return delegate.ask(sparql, bindings);
    }
  }
}
