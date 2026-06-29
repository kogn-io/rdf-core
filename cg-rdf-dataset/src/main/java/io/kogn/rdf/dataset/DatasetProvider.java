// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

/**
 * Factory for obtaining dataset operation instances per storage path.
 *
 * <p>Provides named-graph-level access to a dataset for a given storage path.
 * Implementations are expected to cache instances per path so that repeated
 * calls for the same path return the same service object.</p>
 *
 * <p>The storage path is an opaque identifier that maps to the underlying
 * persistence location, e.g. a relative file-system path under the configured
 * data directory.</p>
 *
 * @see GraphStore
 * @see SparqlUpdate
 * @see DatasetTransactor
 */
public interface DatasetProvider {

  /**
   * Returns a {@link GraphStore} for the given storage path.
   *
   * <p>The instance is created on first access and cached for subsequent calls.</p>
   *
   * @param storagePath path relative to the configured data directory
   * @return the GraphStore for named-graph operations
   */
  GraphStore getGraphStore(String storagePath);

  /**
   * Returns a {@link SparqlUpdate} for the given storage path.
   *
   * <p>The instance is created on first access and cached for subsequent calls.</p>
   *
   * @param storagePath path relative to the configured data directory
   * @return the SparqlUpdate for SPARQL UPDATE and ASK operations
   */
  SparqlUpdate getSparqlUpdate(String storagePath);

  /**
   * Returns a {@link DatasetTransactor} for the given storage path.
   *
   * <p>The instance is created on first access and cached for subsequent calls.</p>
   *
   * @param storagePath path relative to the configured data directory
   * @return the DatasetTransactor for atomic unit-of-work operations
   */
  DatasetTransactor getDatasetTransactor(String storagePath);
}
