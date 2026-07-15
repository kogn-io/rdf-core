// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

/**
 * Backend-neutral options for a single {@link ShaclValidation#validate} run.
 *
 * <h2>Reasoning needs axioms to reason over</h2>
 *
 * <p>{@link #rdfsSubClassReasoning} reasons over the {@code rdfs:subClassOf} axioms it is
 * <em>given</em>; it does not derive them. If no such axiom is present in the graphs passed
 * to {@link ShaclValidation#validate}, enabling the option is a <strong>silent
 * no-op</strong>: the shape still never fires and the report comes back conforming. The
 * failure mode is therefore a false green, not an error — so a shape targeting an abstract
 * superclass passes quietly when the axioms were forgotten.</p>
 *
 * <p>Which input graph must carry the axioms is backend-specific and documented by the
 * implementation; the RDF4J adapter accepts them in either the {@code data} or the
 * {@code shapes} graph.</p>
 *
 * @param rdfsSubClassReasoning whether validation should reason across
 *     {@code rdfs:subClassOf} when matching shape targets (e.g. {@code sh:targetClass})
 *     against instance types. Disabled by default: a shape targeting a superclass does
 *     not fire on instances typed only with a subclass unless this is enabled. Real,
 *     load-bearing option for consumers whose shapes target an abstract superclass while
 *     instances only carry a subclass type. Requires the corresponding axioms in the input
 *     graphs — see above
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
