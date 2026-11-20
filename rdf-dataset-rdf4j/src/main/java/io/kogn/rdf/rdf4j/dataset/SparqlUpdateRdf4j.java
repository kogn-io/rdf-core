// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.util.Map;
import java.util.Objects;

import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.terms.RDFTerm;

/**
 * RDF4J-based implementation of {@link SparqlUpdate}.
 *
 * <p>Each operation opens a dedicated {@link RepositoryConnection} and closes
 * it immediately after execution.</p>
 */
public class SparqlUpdateRdf4j implements SparqlUpdate {

  private final Repository repository;

  /**
   * Creates a SPARQL update port backed by the given RDF4J repository.
   *
   * @param repository the repository to open connections against; must be
   *     initialized — an operation on a repository that is not (shut down, or never initialized)
   *     fails, it does not initialize the repository again
   * @throws NullPointerException if {@code repository} is {@code null}
   */
  public SparqlUpdateRdf4j(final Repository repository) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
  }

  @Override
  public void update(final String sparql) {
    update(sparql, Map.of());
  }

  @Override
  public void update(final String sparql, final Map<String, RDFTerm> bindings) {
    SparqlErrors.requireArguments(sparql, bindings);
    try (RepositoryConnection conn = SparqlErrors.translating(() -> Connections.open(repository))) {
      final Update operation = SparqlErrors
          .bound(SparqlErrors.translating(() -> conn.prepareUpdate(QueryLanguage.SPARQL, sparql)), bindings);
      SparqlErrors.executing(operation::execute);
    }
  }
}
