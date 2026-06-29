package io.kogn.rdf.rdf4j.dataset;

import java.util.function.Consumer;

import org.eclipse.rdf4j.repository.Repository;

/**
 * Provides access to a cached RDF4J {@link Repository} keyed by storage path
 * and supports cache-eviction notification.
 *
 * <p>Implemented by {@code GraphServiceProviderRdf4j} (in {@code cg-rdf-rdf4j})
 * so that {@link DatasetProviderRdf4j} can share the repository cache without
 * creating a compile-time dependency on the full service layer. This interface
 * is the seam that breaks what would otherwise be a module cycle.</p>
 *
 * <p>Consumers call {@link #getOrCreateRepository(String)} to obtain (or lazily
 * create) the backing store for a given path, and register an eviction callback
 * via {@link #onRepositoryEvicted(Consumer)} to drop stale service instances
 * when the underlying repository is deleted.</p>
 */
public interface Rdf4jRepositorySource {

  /**
   * Returns the cached {@link Repository} for the given storage path, creating
   * it on first access.
   *
   * @param storagePath relative path identifying the store
   * @return the cached or newly created repository
   */
  Repository getOrCreateRepository(String storagePath);

  /**
   * Registers a listener that is invoked whenever a repository is evicted
   * (deleted) for the given storage path.
   *
   * @param listener receives the evicted storage path
   */
  void onRepositoryEvicted(Consumer<String> listener);
}
