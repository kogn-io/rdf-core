// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

import java.util.Optional;
import java.util.Set;

import io.kogn.rdf.terms.RDFTerm;

/**
 * A set of bindings from variable names to RDF terms.
 *
 * <p>BindingSets are typically returned as results from SPARQL tuple queries,
 * where each BindingSet represents one row in the result set.</p>
 *
 * <p>Example: For a query like "SELECT ?s ?p WHERE { ?s ?p ?o }", each result
 * row is represented as a BindingSet with bindings for "s" and "p".</p>
 */
public interface BindingSet {

  /**
   * Returns all variable names that have bindings in this set.
   *
   * @return a set of variable names (without the '?' prefix)
   */
  Set<String> getBindingNames();

  /**
   * Returns the value bound to the given variable name.
   *
   * @param bindingName the variable name (without the '?' prefix)
   * @return the bound RDF term, or empty if no binding exists
   */
  Optional<RDFTerm> getValue(String bindingName);

  /**
   * Checks whether this set has a binding for the given variable name.
   *
   * @param bindingName the variable name (without the '?' prefix)
   * @return true if a binding exists, false otherwise
   */
  boolean hasBinding(String bindingName);

  /**
   * Returns the number of bindings in this set.
   *
   * @return the number of bindings
   */
  int size();
}
