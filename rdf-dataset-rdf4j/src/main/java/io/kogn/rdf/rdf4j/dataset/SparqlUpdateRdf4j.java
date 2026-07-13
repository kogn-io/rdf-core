// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import io.kogn.rdf.dataset.SparqlUpdate;

/**
 * RDF4J-based implementation of {@link SparqlUpdate}.
 *
 * <p>Each operation opens a dedicated {@link RepositoryConnection} and closes
 * it immediately after execution, following the same pattern used throughout
 * the existing RDF4J service layer (e.g. {@code CollectionStoreRdf4j}).</p>
 */
public class SparqlUpdateRdf4j implements SparqlUpdate {

  private final Repository repository;

  /**
   * Creates a SPARQL update port backed by the given RDF4J repository.
   *
   * @param repository the repository to open connections against
   */
  public SparqlUpdateRdf4j(final Repository repository) {
    this.repository = repository;
  }

  @Override
  public void update(final String sparql) {
    try (RepositoryConnection conn = repository.getConnection()) {
      conn.prepareUpdate(QueryLanguage.SPARQL, sparql).execute();
    }
  }
}
