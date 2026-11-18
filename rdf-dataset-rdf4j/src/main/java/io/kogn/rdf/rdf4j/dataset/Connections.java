// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;

/**
 * Opens the connection of a content adapter, refusing a repository that is not initialized.
 *
 * <p>RDF4J's {@code SailRepository.getConnection()} initializes a repository that is not
 * initialized, and {@code shutDown()} only resets that state — so without this check a port used
 * after its repository was shut down silently starts the store again: a {@code MemoryStore} comes
 * back empty, a {@code NativeStore} locks its directory again. RDF4J does not tell "shut down"
 * from "never initialized", so a never-initialized repository is refused as well; the caller
 * initializes it.</p>
 *
 * <p>The refusal is an RDF4J {@link RepositoryException}, thrown from an RDF4J call, so each caller
 * keeps it inside its own translation region and it reaches the consumer as that port's neutral
 * failure type ({@link StorageErrors}, {@link SparqlErrors}, the export's own catch). The check
 * and the {@code getConnection()} after it are not atomic: a {@code shutDown()} in between still
 * lets RDF4J initialize the repository again. A repository that refuses a second initialization
 * itself, as the hosting adapter builds them, closes that window.</p>
 */
final class Connections {

  private Connections() {
  }

  /**
   * Opens a connection to {@code repository}, provided it is initialized.
   *
   * @param repository the repository to connect to
   * @return a new connection; the caller closes it
   * @throws RepositoryException if the repository is not initialized — shut down, or never
   *     initialized — or opening the connection fails
   */
  static RepositoryConnection open(final Repository repository) {
    if (!repository.isInitialized()) {
      throw new RepositoryException("the repository is not initialized: it was shut down, or never initialized");
    }
    return repository.getConnection();
  }
}
