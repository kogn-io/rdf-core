// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset.hosting;

import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.Sail;

/**
 * A {@link SailRepository} that can be initialized once and never again after it was shut down.
 *
 * <p>RDF4J's {@code getConnection()} initializes a repository that is not initialized, and
 * {@code shutDown()} only resets that state. The content adapters check {@code isInitialized()}
 * before they connect, but a {@code shutDown()} between that check and the connection — a
 * {@link DatasetLifecycleRdf4j#shutDownAll()} or a {@code delete} racing a retained port — would
 * still start the store again: an in-memory store empty, a native store locking its directory
 * again, a deleted directory coming back. This repository refuses that second initialization with
 * a {@link RepositoryException}, which the adapters translate like any other storage failure.</p>
 */
final class OneShotSailRepository extends SailRepository {

  private volatile boolean shutDown;

  /**
   * Creates a repository over {@code sail}.
   *
   * @param sail the store to wrap
   */
  OneShotSailRepository(final Sail sail) {
    super(sail);
  }

  @Override
  protected void initializeInternal() {
    if (shutDown) {
      throw new RepositoryException("the repository was shut down and cannot be initialized again");
    }
    super.initializeInternal();
  }

  @Override
  protected void shutDownInternal() {
    shutDown = true;
    super.shutDownInternal();
  }
}
