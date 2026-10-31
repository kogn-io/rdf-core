// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleGraph;
import io.kogn.rdf.terms.SimpleIRI;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabXsd;

/**
 * The slice operations of a versioned dataset (ADR-0022), run against a real
 * {@code DatasetTx} over an in-memory RDF4J store.
 */
class ResourceVersioningSliceTest {

  private static final IRI CONTENT = new SimpleIRI("https://example.org/graph/content");
  private static final IRI A = new SimpleIRI("https://example.org/res/A");
  private static final IRI A_FRAGMENT = new SimpleIRI("https://example.org/res/A#part");
  private static final IRI B = new SimpleIRI("https://example.org/res/B");
  private static final IRI AB = new SimpleIRI("https://example.org/res/AB");
  private static final IRI P = new SimpleIRI("https://example.org/vocab/p");
  private static final IRI Q = new SimpleIRI("https://example.org/vocab/q");

  private final SimpleRdf rdf = new SimpleRdf();
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
  void aProxyIriWithoutDraftReadsEmptyWithAToken() {
    final DraftSlice slice = read(A);

    assertThat(slice.triples().isEmpty()).isTrue();
    assertThat(slice.token().proxyIri()).isEqualTo(A);
  }

  @Test
  void aFirstWriteOnAnEmptyDraftIsAcceptedAndReadBack() {
    final Graph content = graph();
    content.add(A, P, rdf.createLiteral("one"));

    write(A, content, read(A).token());

    assertThat(read(A).triples().contains(rdf.createTriple(A, P, rdf.createLiteral("one")))).isTrue();
  }

  @Test
  void theSliceHoldsFragmentSubjectsEvenWithoutALinkFromTheProxyIri() {
    final Graph content = graph();
    content.add(A, P, rdf.createLiteral("one"));
    content.add(A_FRAGMENT, P, rdf.createLiteral("part"));

    write(A, content, read(A).token());

    assertThat(read(A).triples().size()).isEqualTo(2);
  }

  @Test
  void theSliceFollowsBlankNodesTransitively() {
    final BlankNode first = rdf.createBlankNode("first");
    final BlankNode second = rdf.createBlankNode("second");
    final Graph content = graph();
    content.add(A, P, first);
    content.add(first, Q, second);
    content.add(second, Q, rdf.createLiteral("deep"));

    write(A, content, read(A).token());

    assertThat(read(A).triples().size()).isEqualTo(3);
  }

  @Test
  void aProxyIriWhoseNameOnlyStartsWithAnothersIsNotInItsSlice() {
    final Graph other = graph();
    other.add(AB, P, rdf.createLiteral("other"));
    write(AB, other, read(AB).token());

    assertThat(read(A).triples().isEmpty()).isTrue();
  }

  @Test
  void replacingASliceLeavesOtherSlicesAlone() {
    final Graph first = graph();
    first.add(A, P, rdf.createLiteral("a"));
    write(A, first, read(A).token());
    final Graph second = graph();
    second.add(B, P, rdf.createLiteral("b"));
    write(B, second, read(B).token());

    final Graph replacement = graph();
    replacement.add(A, P, rdf.createLiteral("a2"));
    write(A, replacement, read(A).token());

    assertThat(read(A).triples().size()).isEqualTo(1);
    assertThat(read(A).triples().contains(rdf.createTriple(A, P, rdf.createLiteral("a2")))).isTrue();
    assertThat(read(B).triples().contains(rdf.createTriple(B, P, rdf.createLiteral("b")))).isTrue();
  }

  @Test
  void replacingRemovesTheOldBlankNodeClosure() {
    final Graph first = graph();
    first.add(A, P, rdf.createBlankNode("old"));
    first.add(rdf.createBlankNode("old"), Q, rdf.createLiteral("x"));
    write(A, first, read(A).token());

    final Graph replacement = graph();
    replacement.add(A, P, rdf.createLiteral("plain"));
    write(A, replacement, read(A).token());

    assertThat(contentCount()).isEqualTo(1);
  }

  @Test
  void anEmptyContentEmptiesTheDraftButRaisesTheToken() {
    final Graph content = graph();
    content.add(A, P, rdf.createLiteral("one"));
    final RevisionToken first = write(A, content, read(A).token());

    final RevisionToken second = write(A, graph(), first);

    assertThat(read(A).triples().isEmpty()).isTrue();
    assertThat(second).isNotEqualTo(first);
    assertThat(read(A).token()).isEqualTo(second);
  }

  @Test
  void everyWriteRaisesTheTokenAndAReadReturnsTheLatest() {
    final RevisionToken initial = read(A).token();
    final RevisionToken first = write(A, graph(), initial);
    final RevisionToken second = write(A, graph(), first);

    assertThat(initial).isNotEqualTo(first);
    assertThat(first).isNotEqualTo(second);
    assertThat(read(A).token()).isEqualTo(second);
    assertThat(historyCounters()).isEqualTo(1);
  }

  @Test
  void aStaleTokenIsReportedAndChangesNothing() {
    final RevisionToken stale = read(A).token();
    final Graph content = graph();
    content.add(A, P, rdf.createLiteral("one"));
    write(A, content, stale);
    final Graph competing = graph();
    competing.add(A, P, rdf.createLiteral("two"));

    assertThatThrownBy(() -> write(A, competing, stale)).isInstanceOfSatisfying(RevisionConflictException.class,
        e -> assertThat(e.proxyIri()).isEqualTo(A));

    assertThat(read(A).triples().contains(rdf.createTriple(A, P, rdf.createLiteral("one")))).isTrue();
    assertThat(read(A).triples().size()).isEqualTo(1);
  }

  @Test
  void aTokenOfAnotherProxyIriIsRejected() {
    final RevisionToken tokenOfB = read(B).token();

    assertThatThrownBy(() -> write(A, graph(), tokenOfB)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aForeignSubjectIsRejectedAndChangesNothing() {
    final Graph content = graph();
    content.add(A, P, rdf.createLiteral("one"));
    content.add(B, P, rdf.createLiteral("foreign"));
    final RevisionToken token = read(A).token();

    assertThatThrownBy(() -> write(A, content, token)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(B.getIRIString());

    assertThat(read(A).token()).isEqualTo(token);
    assertThat(contentCount()).isZero();
  }

  @Test
  void anUnreachableBlankNodeSubjectIsRejected() {
    final Graph content = graph();
    content.add(rdf.createBlankNode("loose"), P, rdf.createLiteral("x"));

    assertThatThrownBy(() -> write(A, content, read(A).token())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBlankNodeReachableFromTwoProxyIrisIsRejectedAndChangesNothing() {
    final BlankNode shared = rdf.createBlankNode("shared");
    final Graph first = graph();
    first.add(A, P, shared);
    first.add(shared, Q, rdf.createLiteral("x"));
    write(A, first, read(A).token());
    final BlankNode reread = (BlankNode) read(A).triples().stream(A, P, null).findFirst().orElseThrow().getObject();
    final Graph second = graph();
    second.add(B, P, reread);
    final RevisionToken token = read(B).token();

    assertThatThrownBy(() -> write(B, second, token)).isInstanceOf(AmbiguousBlankNodeException.class);

    assertThat(read(B).triples().isEmpty()).isTrue();
    assertThat(read(B).token()).isEqualTo(token);
    assertThat(read(A).triples().size()).isEqualTo(2);
  }

  @Test
  void aProxyIriWithAFragmentIsRejected() {
    assertThatThrownBy(() -> read(A_FRAGMENT)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anAbortedTransactionLeavesNeitherTriplesNorCounterBehind() {
    final Graph content = graph();
    content.add(A, P, rdf.createLiteral("one"));
    final RevisionToken token = read(A).token();

    assertThatThrownBy(() -> transactor.inTransaction(tx -> {
      versioning.replaceSlice(tx, A, content, token);
      throw new IllegalStateException("abort");
    })).hasMessage("abort");

    assertThat(contentCount()).isZero();
    assertThat(historyCounters()).isZero();
    assertThat(read(A).token()).isEqualTo(token);
  }

  @Test
  void theSliceOperationsBindTheContentGraph() {
    read(A);

    final ResourceVersioning other = new ResourceVersioningNamedGraphs(new SimpleIRI("https://example.org/graph/o"));
    assertThatThrownBy(() -> transactor.inTransaction(tx -> other.readSlice(tx, A)))
        .isInstanceOf(ContentGraphMismatchException.class);
  }

  @Test
  void nullArgumentsAreRejected() {
    final RevisionToken token = read(A).token();
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.readSlice(tx, null)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.replaceSlice(tx, A, null, token)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.replaceSlice(tx, A, graph(), null)))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aBlankNodeOfTheReplacedSliceThatAnotherSliceUsesIsRejectedEvenIfTheNewContentDropsIt() {
    final BlankNode shared = rdf.createBlankNode("shared");
    final Graph existing = graph();
    existing.add(A, P, shared);
    existing.add(shared, Q, rdf.createLiteral("x"));
    existing.add(B, P, shared);
    transactor.inTransaction(tx -> tx.add(CONTENT, existing));
    final Graph replacement = graph();
    replacement.add(A, P, rdf.createLiteral("plain"));
    final RevisionToken token = read(A).token();
    final long before = contentCount();

    assertThatThrownBy(() -> write(A, replacement, token)).isInstanceOf(AmbiguousBlankNodeException.class);

    assertThat(contentCount()).isEqualTo(before);
    assertThat(read(B).triples().size()).isEqualTo(2);
    assertThat(read(A).token()).isEqualTo(token);
  }

  @Test
  void twoConcurrentFirstWritesOfOneProxyIriDoNotBothCommit() throws InterruptedException {
    assertThat(concurrentWriteOutcome(read(A).token())).isInstanceOf(ConcurrencyConflictException.class);
  }

  @Test
  void twoConcurrentFollowUpWritesOfOneProxyIriDoNotBothCommit() throws InterruptedException {
    final RevisionToken first = write(A, graph(), read(A).token());

    assertThat(concurrentWriteOutcome(first)).isInstanceOf(ConcurrencyConflictException.class);
  }

  @Test
  void multipleRevisionCountersAreRejectedAsMalformed() {
    final Graph history = graph();
    history.add(A, VocabVersioning.REVISION, rdf.createLiteral("1", VocabXsd.INTEGER));
    history.add(A, VocabVersioning.REVISION, rdf.createLiteral("2", VocabXsd.INTEGER));
    transactor.inTransaction(tx -> tx.add(VersioningGraphs.HISTORY, history));

    assertThatThrownBy(() -> read(A)).isInstanceOf(IllegalStateException.class).hasMessageContaining("counters");
  }

  @Test
  void aRevisionCounterOfTheWrongDatatypeIsRejectedAsMalformed() {
    recordCounter(rdf.createLiteral("1"));

    assertThatThrownBy(() -> read(A)).isInstanceOf(IllegalStateException.class).hasMessageContaining("malformed");
  }

  @Test
  void aNegativeRevisionCounterIsRejectedAsMalformed() {
    recordCounter(rdf.createLiteral("-1", VocabXsd.INTEGER));

    assertThatThrownBy(() -> read(A)).isInstanceOf(IllegalStateException.class).hasMessageContaining("malformed");
  }

  /**
   * Lets a second transaction read {@code token}'s state, commits a competing write of A in
   * between, then lets the second transaction write with the token it read; returns what the
   * second transaction ended with.
   */
  private Throwable concurrentWriteOutcome(final RevisionToken token) throws InterruptedException {
    final CountDownLatch slowHasRead = new CountDownLatch(1);
    final CountDownLatch competitorCommitted = new CountDownLatch(1);
    final Graph competing = graph();
    competing.add(A, P, rdf.createLiteral("competitor"));
    final Graph slowContent = graph();
    slowContent.add(A, P, rdf.createLiteral("slow"));
    final AtomicReference<Throwable> competitorFailure = new AtomicReference<>();
    final Thread competitor = new Thread(() -> {
      try {
        slowHasRead.await();
        write(A, competing, token);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (final RuntimeException e) {
        competitorFailure.set(e);
      } finally {
        competitorCommitted.countDown();
      }
    });
    competitor.start();

    Throwable outcome = null;
    try {
      transactor.inTransaction(tx -> {
        versioning.readSlice(tx, A);
        slowHasRead.countDown();
        awaitCommitted(competitorCommitted);
        return versioning.replaceSlice(tx, A, slowContent, token);
      });
    } catch (final RuntimeException e) {
      outcome = e;
    }
    competitor.join();
    assertThat(competitorFailure.get()).isNull();
    return outcome;
  }

  private void awaitCommitted(final CountDownLatch latch) {
    try {
      latch.await();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void recordCounter(final RDFTerm value) {
    final Graph history = graph();
    history.add(A, VocabVersioning.REVISION, value);
    transactor.inTransaction(tx -> tx.add(VersioningGraphs.HISTORY, history));
  }

  private Graph graph() {
    return new SimpleGraph();
  }

  private DraftSlice read(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.readSlice(tx, proxy));
  }

  private RevisionToken write(final IRI proxy, final ReadableGraph content, final RevisionToken token) {
    return transactor.inTransaction(tx -> versioning.replaceSlice(tx, proxy, content, token));
  }

  private long contentCount() {
    return transactor.inTransaction(tx -> tx.count(CONTENT));
  }

  private long historyCounters() {
    return transactor.inTransaction(tx -> tx.count(VersioningGraphs.HISTORY));
  }
}
