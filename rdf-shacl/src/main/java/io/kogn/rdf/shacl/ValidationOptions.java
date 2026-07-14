// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

/**
 * Backend-neutral options for a single {@link ShaclValidation#validate} run.
 *
 * @param rdfsSubClassReasoning whether validation should reason across
 *     {@code rdfs:subClassOf} when matching shape targets (e.g. {@code sh:targetClass})
 *     against instance types. Disabled by default: a shape targeting a superclass does
 *     not fire on instances typed only with a subclass unless this is enabled. Real,
 *     load-bearing option for consumers whose shapes target an abstract superclass while
 *     instances only carry a subclass type.
 */
public record ValidationOptions(boolean rdfsSubClassReasoning) {

  /**
   * Returns the default options: {@link #rdfsSubClassReasoning} disabled.
   *
   * @return the default validation options
   */
  public static ValidationOptions defaults() {
    return new ValidationOptions(false);
  }
}
