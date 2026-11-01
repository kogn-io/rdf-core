// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.cid.CanonicalizationResourceLimitExceededException;
import io.kogn.rdf.cid.ContentAddressedIriGenerator;
import io.kogn.rdf.cid.ContentAddressedIriGeneratorSexpr;
import io.kogn.rdf.cid.ContentAddressingException;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleGraph;
import io.kogn.rdf.terms.SimpleIRI;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabXsd;

/**
 * The publish of a versioned dataset (ADR-0022, ADR-0023), run against a real
 * {@code DatasetTx} over an in-memory RDF4J store.
 */
class ResourceVersioningPublishTest {

  private static final IRI CONTENT = new SimpleIRI("https://example.org/graph/content");
  private static final IRI A = new SimpleIRI("https://example.org/res/A");
  private static final IRI B = new SimpleIRI("https://example.org/res/B");
  private static final IRI P = new SimpleIRI("https://example.org/vocab/p");
  private static final IRI Q = new SimpleIRI("https://example.org/vocab/q");
  private static final IRI ALICE = new SimpleIRI("https://example.org/people/alice");
  /** 36 hubs of 6 indistinguishable leaves: the cost bound is 10 160 640, just over the limit of ni-rdf/1 §4.3. */
  private static final int HUBS = 36;
  private static final int LEAVES = 6;
  private static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");

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
    versioning = new ResourceVersioningNamedGraphs(CONTENT, generator, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    repository.shutDown();
  }

  @Test
  void aFirstPublishStoresTheVersionShowsItPublishedAndWritesTheEntry() {
    draft(A, literalTriples(A, "one"));
    final IRI cid = generator.generateIri(A, literalTriples(A, "one"));

    final PublishResult result = publish(PublishDetails.NONE, A);

    final IRI entry = result.entries().get(A);
    assertThat(entry.getIRIString()).startsWith("urn:uuid:");
    assertThat(export(VersioningGraphs.CONTENT_STORE).contains(rdf.createTriple(cid, P, rdf.createLiteral("one"))))
        .isTrue();
    assertThat(export(VersioningGraphs.PUBLISHED).contains(rdf.createTriple(A, P, rdf.createLiteral("one")))).isTrue();
    final ReadableGraph history = export(VersioningGraphs.HISTORY);
    assertThat(history.contains(rdf.createTriple(A, VocabVersioning.ENTRY, entry))).isTrue();
    assertThat(objectOf(history, entry, VocabVersioning.SEQUENCE)).isEqualTo(integer(1));
    assertThat(objectOf(history, entry, VocabVersioning.CONTENT)).isEqualTo(cid);
    assertThat(objectOf(history, entry, VocabVersioning.PROCEDURE)).isEqualTo(rdf.createLiteral("ni-rdf/1"));
    assertThat(objectOf(history, entry, VocabDct.CREATED))
        .isEqualTo(rdf.createLiteral("2026-10-31T09:00:00Z", VocabXsd.DATETIME));
    assertThat(history.stream(entry, VocabDct.CREATOR, null).count()).isZero();
    assertThat(history.stream(entry, VocabDct.DESCRIPTION, null).count()).isZero();
  }

  @Test
  void creatorAndCommentAreRecordedWhenGiven() {
    draft(A, literalTriples(A, "one"));

    final IRI entry = publish(PublishDetails.NONE.withCreator(ALICE).withComment("first cut"), A).entries().get(A);

    final ReadableGraph history = export(VersioningGraphs.HISTORY);
    assertThat(objectOf(history, entry, VocabDct.CREATOR)).isEqualTo(ALICE);
    assertThat(objectOf(history, entry, VocabDct.DESCRIPTION)).isEqualTo(rdf.createLiteral("first cut"));
  }

  @Test
  void thePublishedViewEqualsTheVersionWithTheProxyIriInsteadOfTheCid() {
    final BlankNodeDraft shape = blankNodeDraft(A, 1);
    draft(A, shape.graph());

    publish(PublishDetails.NONE, A);

    final IRI cid = generator.generateIri(A, shape.graph());
    assertThat(generator.generateIri(A, export(VersioningGraphs.PUBLISHED))).isEqualTo(cid);
    assertThat(generator.generateIri(cid, export(VersioningGraphs.CONTENT_STORE))).isEqualTo(cid);
  }

  @Test
  void theDraftStaysAfterPublish() {
    draft(A, literalTriples(A, "one"));
    final long before = export(CONTENT).size();

    publish(PublishDetails.NONE, A);

    assertThat(export(CONTENT).size()).isEqualTo(before);
    assertThat(export(CONTENT).contains(rdf.createTriple(A, P, rdf.createLiteral("one")))).isTrue();
  }

  @Test
  void publishingAnUnchangedDraftReturnsTheLatestEntryAndWritesNothing() {
    draft(A, literalTriples(A, "one"));
    final PublishResult first = publish(PublishDetails.NONE, A);
    final long history = export(VersioningGraphs.HISTORY).size();
    final RevisionToken token = first.tokens().get(A);

    final PublishResult second = transactor
        .inTransaction(tx -> versioning.publish(tx, List.of(token), PublishDetails.NONE.withComment("again")));

    assertThat(second.entries().get(A)).isEqualTo(first.entries().get(A));
    assertThat(second.tokens().get(A)).isEqualTo(token);
    assertThat(export(VersioningGraphs.HISTORY).size()).isEqualTo(history);
  }

  @Test
  void aDraftThatChangesAndReturnsMakesThreeEntriesAndStoresTheVersionOnce() {
    draft(A, literalTriples(A, "one"));
    final IRI first = publish(PublishDetails.NONE, A).entries().get(A);
    draft(A, literalTriples(A, "two"));
    final IRI second = publish(PublishDetails.NONE, A).entries().get(A);
    draft(A, literalTriples(A, "one"));
    final IRI third = publish(PublishDetails.NONE, A).entries().get(A);

    final ReadableGraph history = export(VersioningGraphs.HISTORY);
    assertThat(List.of(first, second, third)).doesNotHaveDuplicates();
    assertThat(objectOf(history, third, VocabVersioning.SEQUENCE)).isEqualTo(integer(3));
    assertThat(objectOf(history, third, VocabVersioning.CONTENT))
        .isEqualTo(objectOf(history, first, VocabVersioning.CONTENT));
    assertThat(export(VersioningGraphs.CONTENT_STORE).size()).isEqualTo(2);
    assertThat(export(VersioningGraphs.PUBLISHED).contains(rdf.createTriple(A, P, rdf.createLiteral("one")))).isTrue();
    assertThat(export(VersioningGraphs.PUBLISHED).size()).isEqualTo(1);
  }

  @Test
  void versionsOfOneDraftDoNotShareItsBlankNodes() {
    draft(A, blankNodeDraft(A, 1).graph());
    publish(PublishDetails.NONE, A);
    draft(A, blankNodeDraft(A, 2).graph());
    publish(PublishDetails.NONE, A);

    final ReadableGraph store = export(VersioningGraphs.CONTENT_STORE);
    final IRI first = generator.generateIri(A, blankNodeDraft(A, 1).graph());
    final IRI second = generator.generateIri(A, blankNodeDraft(A, 2).graph());
    assertThat(generator.generateIri(first, closureOf(store, first))).isEqualTo(first);
    assertThat(generator.generateIri(second, closureOf(store, second))).isEqualTo(second);
  }

  @Test
  void aDraftOfFragmentSubjectsOnlyIsStoredOnceWhenItComesBack() {
    final IRI fragment = new SimpleIRI(A.getIRIString() + "#p1");
    final BlankNode node = rdf.createBlankNode("x");
    final Graph first = new SimpleGraph();
    first.add(fragment, P, node);
    first.add(node, Q, integer(1));
    draft(A, first);
    publish(PublishDetails.NONE, A);
    draft(A, literalTriples(A, "other"));
    publish(PublishDetails.NONE, A);
    draft(A, first);
    publish(PublishDetails.NONE, A);

    assertThat(export(VersioningGraphs.CONTENT_STORE).size()).isEqualTo(3);
  }

  @Test
  void severalProxyIrisArePublishedTogetherAndEqualContentIsStoredOnce() {
    draft(A, literalTriples(A, "same"));
    draft(B, literalTriples(B, "same"));

    final PublishResult result = publish(PublishDetails.NONE, A, B);

    assertThat(result.entries()).containsOnlyKeys(A, B);
    assertThat(result.entries().get(A)).isNotEqualTo(result.entries().get(B));
    assertThat(export(VersioningGraphs.CONTENT_STORE).size()).isEqualTo(1);
    assertThat(export(VersioningGraphs.PUBLISHED).size()).isEqualTo(2);
  }

  @Test
  void theResultHoldsItsProxyIrisInTheOrderTheyWerePassed() {
    final IRI c = new SimpleIRI("https://example.org/res/C");
    draft(A, literalTriples(A, "a"));
    draft(B, literalTriples(B, "b"));
    draft(c, literalTriples(c, "c"));

    final PublishResult result = publish(PublishDetails.NONE, c, A, B);

    assertThat(List.copyOf(result.entries().keySet())).containsExactly(c, A, B);
    assertThat(List.copyOf(result.tokens().keySet())).containsExactly(c, A, B);
  }

  @Test
  void aStaleTokenOfOneProxyIriPublishesNone() {
    final RevisionToken a = draft(A, literalTriples(A, "one"));
    final RevisionToken staleB = readToken(B);
    draft(B, literalTriples(B, "two"));

    assertThatThrownBy(
        () -> transactor.inTransaction(tx -> versioning.publish(tx, List.of(a, staleB), PublishDetails.NONE)))
        .isInstanceOf(RevisionConflictException.class);

    assertNothingPublished();
  }

  @Test
  void anAbortedTransactionLeavesPublishedViewContentStoreAndHistoryUnchanged() {
    final RevisionToken a = draft(A, literalTriples(A, "one"));
    final long history = export(VersioningGraphs.HISTORY).size();

    assertThatThrownBy(() -> transactor.inTransaction(tx -> {
      versioning.publish(tx, List.of(a), PublishDetails.NONE);
      throw new IllegalStateException("abort");
    })).hasMessage("abort");

    assertThat(export(VersioningGraphs.HISTORY).size()).isEqualTo(history);
    assertNothingPublished();
  }

  @Test
  void publishRaisesTheTokenSoTheOldOneIsStale() {
    final RevisionToken old = draft(A, literalTriples(A, "one"));

    final RevisionToken next = transactor.inTransaction(tx -> versioning.publish(tx, List.of(old), PublishDetails.NONE))
        .tokens()
        .get(A);

    assertThatThrownBy(() -> publishWith(old)).isInstanceOf(RevisionConflictException.class);
    assertThatThrownBy(
        () -> transactor.inTransaction(tx -> versioning.replaceSlice(tx, A, literalTriples(A, "x"), old)))
        .isInstanceOf(RevisionConflictException.class);
    final RevisionToken written = transactor
        .inTransaction(tx -> versioning.replaceSlice(tx, A, literalTriples(A, "x"), next));
    assertThat(written).isNotEqualTo(next);
  }

  @Test
  void aDraftOfAnotherProcedureIsPublishedAgain() {
    draft(A, literalTriples(A, "one"));
    publish(PublishDetails.NONE, A);
    final ResourceVersioning other = new ResourceVersioningNamedGraphs(CONTENT, new Tagged(generator, "ni-rdf/2"),
        Clock.fixed(NOW, ZoneOffset.UTC));

    final PublishResult result = transactor
        .inTransaction(tx -> other.publish(tx, List.of(other.readSlice(tx, A).token()), PublishDetails.NONE));

    assertThat(objectOf(export(VersioningGraphs.HISTORY), result.entries().get(A), VocabVersioning.SEQUENCE))
        .isEqualTo(integer(2));
  }

  @Test
  void anEmptyDraftIsRejectedAndNothingIsWritten() {
    final RevisionToken token = readToken(A);

    assertThatThrownBy(() -> publishWith(token)).isInstanceOf(IllegalArgumentException.class);

    assertNothingPublished();
  }

  @Test
  void twoTokensForOneProxyIriAreRejected() {
    final RevisionToken token = draft(A, literalTriples(A, "one"));

    assertThatThrownBy(() -> publishWith(token, token)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aDraftOverTheCostLimitIsRejectedAndNothingIsWritten() {
    final Graph dense = new SimpleGraph();
    for (int h = 0; h < HUBS; h++) {
      final BlankNode hub = rdf.createBlankNode("h" + h);
      dense.add(A, P, hub);
      for (int l = 0; l < LEAVES; l++) {
        dense.add(hub, Q, rdf.createBlankNode("l" + h + "_" + l));
      }
    }
    final RevisionToken token = draft(A, dense);

    assertThatThrownBy(() -> publishWith(token)).isInstanceOf(CanonicalizationResourceLimitExceededException.class);

    assertNothingPublished();
  }

  @Test
  void aDraftWithTheReservedIriIsRejectedByTheGeneratorAndNothingIsWritten() {
    final Graph reserved = literalTriples(A, "one");
    reserved.add(A, Q, new SimpleIRI("urn:uuid:171650ec-eda4-47fd-9053-6a34696171c1"));
    final RevisionToken token = draft(A, reserved);

    assertThatThrownBy(() -> publishWith(token)).isInstanceOf(IllegalArgumentException.class)
        .isNotInstanceOf(ContentAddressingException.class);

    assertNothingPublished();
  }

  @Test
  void aFailureToDeriveTheIdentifierIsReportedAndNothingIsWritten() {
    final ResourceVersioning failing = new ResourceVersioningNamedGraphs(CONTENT, new Failing(),
        Clock.fixed(NOW, ZoneOffset.UTC));
    final RevisionToken token = draft(A, literalTriples(A, "one"));

    assertThatThrownBy(() -> transactor.inTransaction(tx -> failing.publish(tx, List.of(token), PublishDetails.NONE)))
        .isInstanceOf(ContentAddressingException.class);

    assertNothingPublished();
  }

  @Test
  void aDatasetBoundToAnotherContentGraphRejectsThePublish() {
    final RevisionToken token = draft(A, literalTriples(A, "one"));
    final ResourceVersioning elsewhere = new ResourceVersioningNamedGraphs(
        new SimpleIRI("https://example.org/graph/other"), generator, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> transactor.inTransaction(tx -> elsewhere.publish(tx, List.of(token), PublishDetails.NONE)))
        .isInstanceOf(ContentGraphMismatchException.class);
  }

  @Test
  void aLatestEntryThatDoesNotReadBackIsAnIllegalState() {
    final RevisionToken token = draft(A, literalTriples(A, "one"));
    final IRI broken = new SimpleIRI("urn:uuid:00000000-0000-0000-0000-000000000000");
    final Graph entry = new SimpleGraph();
    entry.add(A, VocabVersioning.ENTRY, broken);
    transactor.inTransaction(tx -> tx.add(VersioningGraphs.HISTORY, entry));

    assertThatThrownBy(() -> publishWith(token)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("history entry");
  }

  @Test
  void aHistoryWithAGapInTheSequenceIsAnIllegalState() {
    final RevisionToken token = draft(A, literalTriples(A, "one"));
    final IRI entryIri = new SimpleIRI("urn:uuid:00000000-0000-0000-0000-000000000001");
    final Graph entry = new SimpleGraph();
    entry.add(A, VocabVersioning.ENTRY, entryIri);
    entry.add(entryIri, VocabVersioning.SEQUENCE, integer(2));
    entry.add(entryIri, VocabVersioning.CONTENT, new SimpleIRI("ni:///sha3-256;x"));
    entry.add(entryIri, VocabVersioning.PROCEDURE, rdf.createLiteral("ni-rdf/1"));
    transactor.inTransaction(tx -> tx.add(VersioningGraphs.HISTORY, entry));

    assertThatThrownBy(() -> publishWith(token)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gapless");
  }

  @Test
  void aNullArgumentIsRejected() {
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.publish(tx, null, PublishDetails.NONE)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.publish(tx, List.of(), null)))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void noTokensPublishNothing() {
    final PublishResult result = transactor.inTransaction(tx -> versioning.publish(tx, List.of(), PublishDetails.NONE));

    assertThat(result.entries()).isEmpty();
    assertNothingPublished();
  }

  private void assertNothingPublished() {
    assertThat(export(VersioningGraphs.CONTENT_STORE).isEmpty()).isTrue();
    assertThat(export(VersioningGraphs.PUBLISHED).isEmpty()).isTrue();
    assertThat(export(VersioningGraphs.HISTORY).stream(null, VocabVersioning.SEQUENCE, null).count()).isZero();
  }

  private record BlankNodeDraft(Graph graph) {
  }

  /** A draft of {@code proxy} with a blank node whose description depends on {@code value}. */
  private BlankNodeDraft blankNodeDraft(final IRI proxy, final int value) {
    final BlankNode node = rdf.createBlankNode("n" + value);
    final Graph graph = new SimpleGraph();
    graph.add(proxy, P, node);
    graph.add(node, Q, integer(value));
    return new BlankNodeDraft(graph);
  }

  /** The triples of {@code subject} in {@code graph} plus the blank nodes reachable from them. */
  private ReadableGraph closureOf(final ReadableGraph graph, final IRI subject) {
    final Graph closure = new SimpleGraph();
    final java.util.Deque<RDFTerm> pending = new java.util.ArrayDeque<>();
    pending.add(subject);
    while (!pending.isEmpty()) {
      final RDFTerm term = pending.poll();
      for (final Triple triple : graph.stream().filter(t -> t.getSubject().equals(term)).toList()) {
        if (!closure.contains(triple)) {
          closure.add(triple);
          if (triple.getObject() instanceof BlankNode) {
            pending.add(triple.getObject());
          }
        }
      }
    }
    return closure;
  }

  private Literal integer(final long value) {
    return rdf.createLiteral(Long.toString(value), VocabXsd.INTEGER);
  }

  private Graph literalTriples(final IRI proxy, final String value) {
    final Graph graph = new SimpleGraph();
    graph.add(proxy, P, rdf.createLiteral(value));
    return graph;
  }

  private RDFTerm objectOf(final ReadableGraph graph, final IRI subject, final IRI predicate) {
    final List<RDFTerm> found = graph.stream(subject, predicate, null).map(Triple::getObject).toList();
    assertThat(found).hasSize(1);
    return found.getFirst();
  }

  private RevisionToken readToken(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.readSlice(tx, proxy).token());
  }

  private RevisionToken draft(final IRI proxy, final ReadableGraph content) {
    return transactor
        .inTransaction(tx -> versioning.replaceSlice(tx, proxy, content, versioning.readSlice(tx, proxy).token()));
  }

  private PublishResult publish(final PublishDetails details, final IRI... proxies) {
    return transactor.inTransaction(tx -> versioning.publish(tx,
        List.of(proxies).stream().map(proxy -> versioning.readSlice(tx, proxy).token()).toList(), details));
  }

  private PublishResult publishWith(final RevisionToken... tokens) {
    return transactor.inTransaction(tx -> versioning.publish(tx, List.of(tokens), PublishDetails.NONE));
  }

  private ReadableGraph export(final IRI graph) {
    return transactor.inTransaction(tx -> copy(tx, graph));
  }

  private static ReadableGraph copy(final DatasetTx tx, final IRI graph) {
    final Graph copy = new SimpleGraph();
    for (final Triple triple : tx.export(graph).stream().toList()) {
      copy.add(triple);
    }
    return copy;
  }

  /** A generator with another procedure tag, deriving the same names. */
  private record Tagged(ContentAddressedIriGenerator delegate, String tag) implements ContentAddressedIriGenerator {

    @Override
    public IRI generateIri(final IRI base, final ReadableGraph graph) {
      return delegate.generateIri(base, graph);
    }

    @Override
    public String procedureTag() {
      return tag;
    }
  }

  /** A generator that cannot derive any identifier. */
  private static final class Failing implements ContentAddressedIriGenerator {

    @Override
    public IRI generateIri(final IRI base, final ReadableGraph graph) {
      throw new ContentAddressingException("cannot derive", null);
    }

    @Override
    public String procedureTag() {
      return "ni-rdf/1";
    }
  }
}
