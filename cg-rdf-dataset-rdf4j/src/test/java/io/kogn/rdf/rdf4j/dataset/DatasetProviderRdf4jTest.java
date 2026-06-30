// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;

/**
 * Contract tests for {@link DatasetProviderRdf4j}.
 *
 * <p>Uses a minimal in-process {@link Rdf4jRepositorySource} test double backed
 * by {@code MemoryStore} — no {@code cg-rdf-api} or {@code cg-rdf-cid} on the
 * classpath. The tested contract:</p>
 * <ul>
 *   <li>Same cached service instance per path for each of GraphStore, SparqlUpdate,
 *       DatasetTransactor.</li>
 *   <li>Distinct instances for distinct paths.</li>
 *   <li>Eviction (repository deletion) drops cached services; the next access
 *       creates a fresh instance.</li>
 * </ul>
 */
class DatasetProviderRdf4jTest {

  /**
   * Minimal {@link Rdf4jRepositorySource} backed by in-memory RDF4J stores.
   *
   * <p>Caches one repository per storage path. Exposes a {@link #deleteRepository}
   * method that mirrors the eviction contract — it shuts down the repository,
   * removes it from the cache, and fires all registered listeners — so that
   * {@link DatasetProviderRdf4j#evict} is exercised in the eviction test.</p>
   */
  private static class TestRepositorySource implements Rdf4jRepositorySource {

    private final Map<String, Repository> cache = new ConcurrentHashMap<>();
    private final List<Consumer<String>> listeners = new ArrayList<>();

    @Override
    public Repository getOrCreateRepository(final String storagePath) {
      return cache.computeIfAbsent(storagePath, _ -> {
        final SailRepository repo = new SailRepository(new MemoryStore());
        repo.init();
        return repo;
      });
    }

    @Override
    public void onRepositoryEvicted(final Consumer<String> listener) {
      listeners.add(listener);
    }

    /** Simulates repository deletion: shuts down, removes, fires listeners. */
    void deleteRepository(final String storagePath) {
      final Repository repo = cache.remove(storagePath);
      if (repo != null && repo.isInitialized()) {
        repo.shutDown();
      }
      listeners.forEach(l -> l.accept(storagePath));
    }

    void closeAll() {
      cache.values().forEach(repo -> {
        if (repo.isInitialized()) {
          repo.shutDown();
        }
      });
      cache.clear();
    }
  }

  private TestRepositorySource source;
  private DatasetProviderRdf4j provider;

  @BeforeEach
  void setUp() {
    source = new TestRepositorySource();
    provider = new DatasetProviderRdf4j(source);
  }

  @AfterEach
  void tearDown() {
    source.closeAll();
  }

  @Test
  @DisplayName("getGraphStore returns the same cached instance for the same path")
  void getGraphStore_samePath_returnsCachedInstance() {
    final GraphStore first = provider.getGraphStore("repo-a");

    assertThat(provider.getGraphStore("repo-a")).isSameAs(first);
  }

  @Test
  @DisplayName("getSparqlQuery returns the same cached instance for the same path")
  void getSparqlQuery_samePath_returnsCachedInstance() {
    final SparqlQuery first = provider.getSparqlQuery("repo-a");

    assertThat(provider.getSparqlQuery("repo-a")).isSameAs(first);
  }

  @Test
  @DisplayName("getSparqlUpdate returns the same cached instance for the same path")
  void getSparqlUpdate_samePath_returnsCachedInstance() {
    final SparqlUpdate first = provider.getSparqlUpdate("repo-a");

    assertThat(provider.getSparqlUpdate("repo-a")).isSameAs(first);
  }

  @Test
  @DisplayName("getDatasetTransactor returns the same cached instance for the same path")
  void getDatasetTransactor_samePath_returnsCachedInstance() {
    final DatasetTransactor first = provider.getDatasetTransactor("repo-a");

    assertThat(provider.getDatasetTransactor("repo-a")).isSameAs(first);
  }

  @Test
  @DisplayName("different paths yield distinct GraphStore instances")
  void getGraphStore_differentPaths_returnDistinctInstances() {
    assertThat(provider.getGraphStore("repo-a")).isNotSameAs(provider.getGraphStore("repo-b"));
  }

  @Test
  @DisplayName("eviction drops all cached services so fresh instances are created on next access")
  void deleteRepository_evictsCachedServices() {
    final GraphStore firstStore = provider.getGraphStore("repo-a");
    final SparqlQuery firstQuery = provider.getSparqlQuery("repo-a");
    final SparqlUpdate firstUpdate = provider.getSparqlUpdate("repo-a");
    final DatasetTransactor firstTransactor = provider.getDatasetTransactor("repo-a");

    source.deleteRepository("repo-a");

    assertThat(provider.getGraphStore("repo-a")).isNotSameAs(firstStore);
    assertThat(provider.getSparqlQuery("repo-a")).isNotSameAs(firstQuery);
    assertThat(provider.getSparqlUpdate("repo-a")).isNotSameAs(firstUpdate);
    assertThat(provider.getDatasetTransactor("repo-a")).isNotSameAs(firstTransactor);
  }
}
