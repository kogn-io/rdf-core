// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset.hosting;

/**
 * Backend-neutral store configuration for a {@link DatasetLifecycle}.
 *
 * <p>Carries only the knobs that are meaningful regardless of the backing
 * engine. Engine-specific details (index specification, on-disk storage root,
 * concrete store implementation) are <strong>not</strong> part of this record —
 * they are supplied to the backend implementation directly, so that no
 * backend vocabulary leaks into the neutral port layer.</p>
 *
 * @param persistence whether the store must survive process restarts
 * @param fullTextSearch whether full-text indexing is required; the full-text
 *     <em>search</em> port is delivered separately, so a backend may reject
 *     {@code true} until that capability lands
 */
public record DatasetStoreConfig(Persistence persistence, boolean fullTextSearch) {

  /** How durably a dataset's contents must be stored. */
  public enum Persistence {
    /** Contents survive process restarts (on-disk store). */
    PERSISTENT,
    /** Contents live only for the lifetime of the store (in-memory). */
    IN_MEMORY
  }

  /**
   * Validates the configuration.
   *
   * @throws IllegalArgumentException if {@code persistence} is {@code null}
   */
  public DatasetStoreConfig {
    if (persistence == null) {
      throw new IllegalArgumentException("persistence must not be null");
    }
  }

  /**
   * Returns the default configuration: a persistent store without full-text
   * search.
   *
   * @return a persistent, non-FTS configuration
   */
  public static DatasetStoreConfig persistentDefault() {
    return new DatasetStoreConfig(Persistence.PERSISTENT, false);
  }
}
