// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.rdf4j.repository.Repository;

import io.kogn.rdf.dataset.DatasetProvider;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import lombok.extern.slf4j.Slf4j;

/**
 * RDF4J-based implementation of {@link DatasetProvider}.
 *
 * <p>Delegates repository access to a {@link Rdf4jRepositorySource} so that
 * dataset-level services and the existing graph-level services (in
 * {@code cg-rdf-rdf4j}) operate on the same underlying RDF4J store. This
 * avoids opening two NativeStore instances on the same directory, which would
 * fail with a lock error.</p>
 *
 * <p>Service instances ({@link GraphStoreRdf4j}, {@link SparqlUpdateRdf4j},
 * {@link DatasetTransactorRdf4j}) are created on first access and cached per
 * storage path, mirroring the cache strategy of the repository source.</p>
 *
 * <p>In the closed {@code cg-rdf-rdf4j} module the repository source is
 * {@code GraphServiceProviderRdf4j}, which implements
 * {@link Rdf4jRepositorySource}. Alternative implementations can supply any
 * other repository source without pulling in the full service layer.</p>
 */
@Slf4j
public class DatasetProviderRdf4j implements DatasetProvider {

  private final Rdf4jRepositorySource repositorySource;
  private final Map<String, GraphStoreRdf4j> graphStoreCache = new ConcurrentHashMap<>();
  private final Map<String, SparqlQueryRdf4j> sparqlQueryCache = new ConcurrentHashMap<>();
  private final Map<String, SparqlUpdateRdf4j> sparqlUpdateCache = new ConcurrentHashMap<>();
  private final Map<String, DatasetTransactorRdf4j> transactorCache = new ConcurrentHashMap<>();

  /**
   * Creates a provider backed by the given repository source.
   *
   * <p>Registers an eviction callback so that cached dataset services are
   * dropped whenever the underlying repository is deleted, preventing stale
   * wrappers bound to a shut-down {@link Repository}.</p>
   *
   * @param repositorySource provides and caches RDF4J repositories by path
   */
  public DatasetProviderRdf4j(final Rdf4jRepositorySource repositorySource) {
    this.repositorySource = repositorySource;
    repositorySource.onRepositoryEvicted(this::evict);
  }

  /**
   * Drops the cached dataset services for {@code storagePath}. Invoked when
   * the repository source evicts the underlying repository so that the next
   * access recreates services against a fresh {@link Repository}.
   */
  private void evict(final String storagePath) {
    graphStoreCache.remove(storagePath);
    sparqlQueryCache.remove(storagePath);
    sparqlUpdateCache.remove(storagePath);
    transactorCache.remove(storagePath);
  }

  @Override
  public GraphStore getGraphStore(final String storagePath) {
    return graphStoreCache.computeIfAbsent(storagePath, path -> {
      log.info("Creating GraphStore for storage path: {}", path);
      final Repository repository = repositorySource.getOrCreateRepository(path);
      return new GraphStoreRdf4j(repository);
    });
  }

  @Override
  public SparqlQuery getSparqlQuery(final String storagePath) {
    return sparqlQueryCache.computeIfAbsent(storagePath, path -> {
      log.info("Creating SparqlQuery for storage path: {}", path);
      final Repository repository = repositorySource.getOrCreateRepository(path);
      return new SparqlQueryRdf4j(repository);
    });
  }

  @Override
  public SparqlUpdate getSparqlUpdate(final String storagePath) {
    return sparqlUpdateCache.computeIfAbsent(storagePath, path -> {
      log.info("Creating SparqlUpdate for storage path: {}", path);
      final Repository repository = repositorySource.getOrCreateRepository(path);
      return new SparqlUpdateRdf4j(repository);
    });
  }

  @Override
  public DatasetTransactor getDatasetTransactor(final String storagePath) {
    return transactorCache.computeIfAbsent(storagePath, path -> {
      log.info("Creating DatasetTransactor for storage path: {}", path);
      final Repository repository = repositorySource.getOrCreateRepository(path);
      return new DatasetTransactorRdf4j(repository);
    });
  }
}
