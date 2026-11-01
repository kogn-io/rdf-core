// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.SimpleGraph;
import io.kogn.rdf.terms.SimpleIRI;
import io.kogn.rdf.terms.SimpleRdf;

/**
 * Races between transactions on one versioned dataset, run against a disk-backed
 * {@code NativeStore}, beside the {@code MemoryStore} the other tests use. These are
 * end-state and race regression tests, not a pin of the {@code contains} guard of ADR-0008:
 * replacing that guard with an {@code ASK} left the first-write, follow-up-write and bind races green (1000 runs each). Each race is held by latches, not by timing: the slow transaction has read and written
 * before the competitor commits, and commits afterwards (in {@code ResourceVersioningSliceTest} the slow
 * write only comes after the competitor's commit).
 *
 * <p>Only invariants of the same proxy IRI and of equal content are pinned; no race here
 * claims that transactions on disjoint proxy IRIs conflict.</p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ResourceVersioningNativeStoreRaceTest {

  /**
   * Repetitions per race. The latches force the overlap, so a run does not depend on lucky timing;
   * the repetitions only guard against residual scheduling effects. 20 keeps CI about 15 s.
   */
  private static final int REPETITIONS = 20;

  private static final long JOIN_MILLIS = 10_000;

  private static final IRI CONTENT = new SimpleIRI("https://example.org/graph/content");
  private static final IRI OTHER_CONTENT = new SimpleIRI("https://example.org/graph/other");
  private static final IRI A = new SimpleIRI("https://example.org/res/A");
  private static final IRI B = new SimpleIRI("https://example.org/res/B");
  private static final IRI P = new SimpleIRI("https://example.org/vocab/p");

  @TempDir
  private Path storeDir;

  private final SimpleRdf rdf = new SimpleRdf();
  private Repository repository;
  private DatasetTransactor transactor;
  private ResourceVersioning versioning;

  @BeforeEach
  void setUp() {
    repository = new SailRepository(new NativeStore(storeDir.toFile()));
    repository.init();
    transactor = new DatasetTransactorRdf4j(repository);
    versioning = new ResourceVersioningNamedGraphs(CONTENT);
  }

  @AfterEach
  void tearDown() {
    repository.shutDown();
  }

  @RepeatedTest(REPETITIONS)
  void twoConcurrentFirstWritesOfOneProxyIriLeaveTheWinnersContentAndCounterOne() throws InterruptedException {
    final RevisionToken token = read(A).token();

    final Throwable loser = race(tx -> versioning.readSlice(tx, A), tx -> {
      versioning.replaceSlice(tx, A, content(A, "slow"), token);
    }, () -> write(A, content(A, "competitor"), token));

    assertThat(loser).isInstanceOf(ConcurrencyConflictException.class);
    assertThat(read(A).triples().size()).isEqualTo(1);
    assertThat(read(A).triples().contains(rdf.createTriple(A, P, rdf.createLiteral("competitor")))).isTrue();
    assertThat(read(A).token().revision()).isEqualTo(1);
  }

  @RepeatedTest(REPETITIONS)
  void twoConcurrentFollowUpWritesOfOneProxyIriLeaveTheWinnersContentAndCounterTwo() throws InterruptedException {
    final RevisionToken first = write(A, content(A, "first"), read(A).token());

    final Throwable loser = race(tx -> versioning.readSlice(tx, A), tx -> {
      versioning.replaceSlice(tx, A, content(A, "slow"), first);
    }, () -> write(A, content(A, "competitor"), first));

    assertThat(loser).isInstanceOf(ConcurrencyConflictException.class);
    assertThat(read(A).triples().size()).isEqualTo(1);
    assertThat(read(A).triples().contains(rdf.createTriple(A, P, rdf.createLiteral("competitor")))).isTrue();
    assertThat(read(A).token().revision()).isEqualTo(2);
  }

  @RepeatedTest(REPETITIONS)
  void twoConcurrentBindsOfAnUnboundDatasetToDifferentContentGraphsCommitOnlyOne() throws InterruptedException {
    final ResourceVersioning other = new ResourceVersioningNamedGraphs(OTHER_CONTENT);

    final Throwable loser = race(tx -> versioning.bindContentGraph(tx), tx -> {
    }, () -> transactor.inTransaction(tx -> {
      other.bindContentGraph(tx);
      return null;
    }));

    assertThat(loser).isInstanceOf(ConcurrencyConflictException.class);
    final List<IRI> bound = transactor.inTransaction(tx -> tx.export(VersioningGraphs.CONFIG)
        .stream(VersioningGraphs.CONFIG, VocabVersioning.CONTENT_GRAPH, null)
        .map(triple -> (IRI) triple.getObject())
        .toList());
    assertThat(bound).containsExactly(OTHER_CONTENT);
    assertThatThrownBy(() -> read(A)).isInstanceOf(ContentGraphMismatchException.class);
  }

  @RepeatedTest(REPETITIONS)
  void twoConcurrentPublishesOfOneProxyIriWithOneTokenPublishOnce() throws InterruptedException {
    final RevisionToken token = write(A, content(A, "text"), read(A).token());

    final Throwable loser = race(tx -> versioning.readSlice(tx, A),
        tx -> versioning.publish(tx, List.of(token), PublishDetails.NONE),
        () -> transactor.inTransaction(tx -> versioning.publish(tx, List.of(token), PublishDetails.NONE)));

    assertThat(loser).isInstanceOf(ConcurrencyConflictException.class);
    assertThat(historyOf(A)).hasSize(1);
    assertThat(stateOf(A)).isEqualTo(PublishState.PUBLISHED_UNCHANGED);
    assertThat(read(A).token().revision()).isEqualTo(2);
  }

  @RepeatedTest(REPETITIONS)
  void twoConcurrentPublishesOfEqualContentStoreTheVersionOnceAndCommitOnlyOne() throws InterruptedException {
    final RevisionToken tokenA = write(A, contentWithBlankNode(A, "a"), read(A).token());
    final RevisionToken tokenB = write(B, contentWithBlankNode(B, "b"), read(B).token());

    final Throwable loser = race(tx -> versioning.readSlice(tx, A),
        tx -> versioning.publish(tx, List.of(tokenA), PublishDetails.NONE),
        () -> transactor.inTransaction(tx -> versioning.publish(tx, List.of(tokenB), PublishDetails.NONE)));

    assertThat(loser).isInstanceOf(ConcurrencyConflictException.class);
    assertThat(stateOf(B)).isEqualTo(PublishState.PUBLISHED_UNCHANGED);
    assertThat(stateOf(A)).isEqualTo(PublishState.NEVER_PUBLISHED);
    // pins one store write per publish in the race (two triples; a second write would count four);
    // dedupe across sequential publishes is pinned in ResourceVersioningPublishTest
    assertThat(count(VersioningGraphs.CONTENT_STORE)).isEqualTo(2);
  }

  /**
   * Runs {@code slowWork} in a transaction, lets {@code competitor} run and commit in another
   * thread once the slow transaction has done its work, then lets the slow transaction commit.
   *
   * @return what the slow transaction ended with, {@code null} if it committed
   */
  private Throwable race(final Consumer<DatasetTx> slowRead, final Consumer<DatasetTx> slowWrite,
      final Runnable competitor) throws InterruptedException {
    final CountDownLatch slowIsReady = new CountDownLatch(1);
    final CountDownLatch competitorCommitted = new CountDownLatch(1);
    final AtomicReference<Throwable> competitorFailure = new AtomicReference<>();
    final Thread thread = new Thread(() -> {
      try {
        slowIsReady.await();
        competitor.run();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (final RuntimeException e) {
        competitorFailure.set(e);
      } finally {
        competitorCommitted.countDown();
      }
    });
    thread.start();

    Throwable outcome = null;
    try {
      transactor.inTransaction(tx -> {
        try {
          slowRead.accept(tx);
          slowWrite.accept(tx);
        } finally {
          slowIsReady.countDown();
        }
        awaitLatch(competitorCommitted);
        return null;
      });
    } catch (final RuntimeException e) {
      outcome = e;
    }
    thread.join(JOIN_MILLIS);
    assertThat(thread.isAlive()).isFalse();
    assertThat(competitorFailure.get()).isNull();
    return outcome;
  }

  private static void awaitLatch(final CountDownLatch latch) {
    try {
      if (!latch.await(JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("competitor did not finish");
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Graph content(final IRI proxy, final String text) {
    final Graph graph = new SimpleGraph();
    graph.add(proxy, P, rdf.createLiteral(text));
    return graph;
  }

  private Graph contentWithBlankNode(final IRI proxy, final String label) {
    final BlankNode node = rdf.createBlankNode(label);
    final Graph graph = new SimpleGraph();
    graph.add(proxy, P, node);
    graph.add(node, P, rdf.createLiteral("same"));
    return graph;
  }

  private List<HistoryEntry> historyOf(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.history(tx, proxy));
  }

  private PublishState stateOf(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.publishState(tx, proxy));
  }

  private long count(final IRI graph) {
    return transactor.inTransaction(tx -> tx.count(graph));
  }

  private DraftSlice read(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.readSlice(tx, proxy));
  }

  private RevisionToken write(final IRI proxy, final Graph content, final RevisionToken token) {
    return transactor.inTransaction(tx -> versioning.replaceSlice(tx, proxy, content, token));
  }
}
