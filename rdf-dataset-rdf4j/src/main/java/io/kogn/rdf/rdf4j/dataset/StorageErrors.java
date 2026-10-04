// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.util.function.Supplier;

import io.kogn.rdf.dataset.DatasetStorageException;

/**
 * Translates RDF4J storage failures of the graph operations into the neutral
 * {@link DatasetStorageException}.
 *
 * <p>The graph-operation counterpart of {@link SparqlErrors}, with the same translation region:
 * only RDF4J calls go into {@link #translating(Supplier)} — opening the connection, mutating,
 * reading, committing, closing, and reading a result to the end. The catch is
 * {@link RuntimeException}, not RDF4J's own {@code RDF4JException}, because a sail may signal
 * through a foreign unchecked exception that the repository connection passes on unwrapped.
 * What makes the blanket catch safe is what the callers keep <em>out</em> of it: converting the
 * caller's terms to RDF4J values, and converting RDF4J results (including the rejection of an
 * RDF 1.2 triple term) back, both run outside, so a {@code null} argument or a defect in this
 * library is never disguised as a storage failure.</p>
 *
 * <p>Regions must not nest: a failure already translated would be wrapped a second time.</p>
 */
final class StorageErrors {

  private StorageErrors() {
  }

  /**
   * Runs an RDF4J call of a graph operation, translating its failures.
   *
   * @param <T> the type of the call's result
   * @param backendCall the RDF4J call; must contain nothing but RDF4J calls
   * @return whatever the call returns
   * @throws DatasetStorageException if the call fails
   */
  static <T> T translating(final Supplier<T> backendCall) {
    try {
      return backendCall.get();
    } catch (final RuntimeException e) {
      throw storageFailure(e);
    }
  }

  /**
   * Runs an RDF4J call without a result, translating its failures like
   * {@link #translating(Supplier)}.
   *
   * @param backendCall the RDF4J call; must contain nothing but RDF4J calls
   * @throws DatasetStorageException if the call fails
   */
  static void running(final Runnable backendCall) {
    translating(() -> {
      backendCall.run();
      return null;
    });
  }

  /**
   * Wraps a backend failure in the neutral type, keeping it as cause.
   *
   * @param failure the backend's failure
   * @return the neutral exception to throw
   */
  static DatasetStorageException storageFailure(final RuntimeException failure) {
    return new DatasetStorageException(failure.getMessage(), failure);
  }
}
