// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

/**
 * SPARQL write port.
 *
 * <p>Covers write operations via the SPARQL 1.1 Update language. This port does
 * not read data; for {@code SELECT}, {@code CONSTRUCT} and {@code ASK} queries
 * see {@link SparqlQuery}, and for writes that participate in an atomic
 * unit-of-work see {@link DatasetTx}.</p>
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
}
