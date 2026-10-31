// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.SimpleIRI;

/**
 * The content-graph binding of a versioned dataset (ADR-0023), run against a real
 * {@code DatasetTx} over an in-memory RDF4J store.
 */
class ResourceVersioningNamedGraphsTest {

  private static final IRI CONTENT = new SimpleIRI("https://example.org/graph/content");
  private static final IRI OTHER = new SimpleIRI("https://example.org/graph/other");

  private Repository repository;
  private DatasetTransactor transactor;

  @BeforeEach
  void setUp() {
    repository = new SailRepository(new MemoryStore());
    repository.init();
    transactor = new DatasetTransactorRdf4j(repository);
  }

  @AfterEach
  void tearDown() {
    repository.shutDown();
  }

  @Test
  void firstUseRecordsTheContentGraphInTheConfigGraph() {
    final ResourceVersioning versioning = new ResourceVersioningNamedGraphs(CONTENT);

    transactor.inTransaction(tx -> {
      versioning.bindContentGraph(tx);
      return null;
    });

    assertThat(configCount()).isEqualTo(1);
    final boolean recorded = transactor.inTransaction(
        tx -> tx.contains(VersioningGraphs.CONFIG, VersioningGraphs.CONFIG, VocabVersioning.CONTENT_GRAPH, CONTENT));
    assertThat(recorded).isTrue();
  }

  @Test
  void laterUseWithTheSameContentGraphChangesNothing() {
    bind(new ResourceVersioningNamedGraphs(CONTENT));

    bind(new ResourceVersioningNamedGraphs(new SimpleIRI(CONTENT.getIRIString())));

    assertThat(configCount()).isEqualTo(1);
  }

  @Test
  void laterUseWithAnotherContentGraphIsRejectedAndChangesNothing() {
    bind(new ResourceVersioningNamedGraphs(CONTENT));
    final ResourceVersioning other = new ResourceVersioningNamedGraphs(OTHER);

    assertThatThrownBy(() -> bind(other)).isInstanceOfSatisfying(ContentGraphMismatchException.class, e -> {
      assertThat(e.requested()).isEqualTo(OTHER);
      assertThat(e.recorded()).containsExactly(CONTENT);
      assertThat(e.getMessage()).contains(CONTENT.getIRIString(), OTHER.getIRIString());
    });
    assertThat(configCount()).isEqualTo(1);
  }

  @Test
  void bindingWithinTheSameTransactionIsVisibleToALaterCheck() {
    final ResourceVersioning versioning = new ResourceVersioningNamedGraphs(CONTENT);
    final ResourceVersioning other = new ResourceVersioningNamedGraphs(OTHER);

    assertThatThrownBy(() -> transactor.inTransaction(tx -> {
      versioning.bindContentGraph(tx);
      other.bindContentGraph(tx);
      return null;
    })).isInstanceOf(ContentGraphMismatchException.class);
    assertThat(configCount()).isZero();
  }

  @Test
  void anAbortedTransactionLeavesNoBindingBehind() {
    final ResourceVersioning versioning = new ResourceVersioningNamedGraphs(CONTENT);

    assertThatThrownBy(() -> transactor.inTransaction(tx -> {
      versioning.bindContentGraph(tx);
      throw new IllegalStateException("abort");
    })).hasMessage("abort");

    assertThat(configCount()).isZero();
    bind(new ResourceVersioningNamedGraphs(OTHER));
    assertThat(configCount()).isEqualTo(1);
  }

  @Test
  void aGraphOfTheLibraryIsNoContentGraph() {
    for (final IRI graph : VersioningGraphs.ALL) {
      final IRI copy = new SimpleIRI(graph.getIRIString());
      assertThatThrownBy(() -> new ResourceVersioningNamedGraphs(copy)).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(graph.getIRIString());
    }
  }

  @Test
  void nullIsRejected() {
    assertThatThrownBy(() -> new ResourceVersioningNamedGraphs(null)).isInstanceOf(NullPointerException.class);
    final ResourceVersioning versioning = new ResourceVersioningNamedGraphs(CONTENT);
    assertThatThrownBy(() -> versioning.bindContentGraph(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void reportsItsContentGraph() {
    assertThat(new ResourceVersioningNamedGraphs(CONTENT).contentGraph()).isEqualTo(CONTENT);
  }

  private void bind(final ResourceVersioning versioning) {
    transactor.inTransaction(tx -> {
      versioning.bindContentGraph(tx);
      return null;
    });
  }

  private long configCount() {
    return transactor.inTransaction(tx -> tx.count(VersioningGraphs.CONFIG));
  }
}
