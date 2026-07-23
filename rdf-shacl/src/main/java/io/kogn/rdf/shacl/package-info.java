/**
 * Backend-neutral SHACL validation port.
 *
 * <p>{@link io.kogn.rdf.shacl.ShaclValidation} validates a candidate
 * {@link io.kogn.rdf.terms.ReadableGraph} against a SHACL shapes graph and returns a
 * neutral {@link io.kogn.rdf.shacl.ShaclReport} — no backend-specific type appears on
 * this port. Implementations live in adapter modules (e.g. {@code rdf-shacl-rdf4j}).</p>
 *
 * <ul>
 *   <li>{@link io.kogn.rdf.shacl.ShaclValidation} — the port itself</li>
 *   <li>{@link io.kogn.rdf.shacl.ValidationOptions} — per-call options (RDFS subclass
 *       reasoning)</li>
 *   <li>{@link io.kogn.rdf.shacl.ShaclReport} — the validation outcome</li>
 *   <li>{@link io.kogn.rdf.shacl.ShaclResult} — a single reported result</li>
 *   <li>{@link io.kogn.rdf.shacl.ShaclMessage} — one {@code sh:resultMessage} with its
 *       language tag</li>
 *   <li>{@link io.kogn.rdf.shacl.Severity} — {@code sh:Violation} / {@code sh:Warning} /
 *       {@code sh:Info}</li>
 * </ul>
 *
 * <h2>Scope: standalone, non-transactional validation only</h2>
 *
 * <p>This module deliberately covers the single-consumer / controlled-write case: one
 * adapter owns every write and wants to validate a candidate graph before writing it.
 * It has no dependency on the dataset ports and no coupling to a store's commit path —
 * a future transactional, write-path-enforced variant is a separate concern that would
 * reuse {@link io.kogn.rdf.shacl.ShaclReport} but is not part of this module.</p>
 */
package io.kogn.rdf.shacl;
