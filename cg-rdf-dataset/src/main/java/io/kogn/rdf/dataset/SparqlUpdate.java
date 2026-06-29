package io.kogn.rdf.dataset;

/**
 * SPARQL Update and ASK port.
 *
 * <p>Covers write operations via SPARQL 1.1 Update language and boolean queries
 * via SPARQL ASK. Neither method returns RDF data; for SELECT or CONSTRUCT results
 * see {@link DatasetTx}.</p>
 */
public interface SparqlUpdate {

  /**
   * Executes a SPARQL 1.1 Update operation against the dataset.
   *
   * <p>Typical operations include {@code INSERT DATA}, {@code DELETE DATA},
   * {@code INSERT/DELETE WHERE}, and {@code CLEAR}. The operation is executed
   * atomically if the underlying store supports it.</p>
   *
   * @param sparql the SPARQL Update string; must not be {@code null} or empty
   * @throws IllegalArgumentException if the SPARQL string is syntactically invalid
   */
  void update(String sparql);

  /**
   * Executes a SPARQL ASK query and returns its boolean result.
   *
   * <p>Returns {@code true} if the query pattern matches at least one solution in
   * the dataset, {@code false} otherwise.</p>
   *
   * @param sparql the SPARQL ASK query string; must not be {@code null} or empty
   * @return {@code true} if the pattern has at least one match
   * @throws IllegalArgumentException if the SPARQL string is syntactically invalid
   */
  boolean ask(String sparql);
}
