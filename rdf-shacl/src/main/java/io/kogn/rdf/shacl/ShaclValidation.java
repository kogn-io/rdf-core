// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

import io.kogn.rdf.terms.ReadableGraph;

/**
 * Backend-neutral, non-transactional SHACL validation: validates a candidate data graph
 * against a set of SHACL shapes and returns a structured report.
 *
 * <p>This port is stateless and store-independent — it operates purely on
 * {@link ReadableGraph} inputs, so a consumer that fully controls its own write path can
 * validate a candidate graph <em>before</em> writing it, without requiring the store
 * itself to be schema-constrained. It is the leaner sibling of a transactional,
 * write-path-enforced SHACL sail: no coupling to a dataset's commit path.</p>
 *
 * <h2>Unsupported SHACL Core and SHACL-SPARQL constructs are rejected, never skipped</h2>
 *
 * <p>An implementation whose backend cannot evaluate a construct of SHACL Core, or a constraint
 * of SHACL-SPARQL ({@code sh:sparql} or a SHACL-SPARQL constraint component), used in the
 * shapes graph throws {@link ShaclValidationException}; it must not hand out a report that
 * conforms because such a constraint was never checked. Which constructs a backend rejects is
 * part of its implementation's documentation. The RDF4J implementation ({@code rdf-shacl-rdf4j})
 * rejects repetition paths ({@code sh:zeroOrMorePath}, {@code sh:oneOrMorePath},
 * {@code sh:zeroOrOnePath}) at any nesting depth, {@code sh:xone}, and SHACL-SPARQL constraint
 * components ({@code sh:validator}, {@code sh:nodeValidator}, {@code sh:propertyValidator});
 * {@code sh:sparql} constraints are supported.</p>
 *
 * <p>The promise covers only these two parts of SHACL. Constructs outside them may stay
 * <em>inert</em> — accepted without error and without effect, so a shape that relies on one
 * can yield a conforming report: SHACL Advanced Features such as custom target types and
 * {@code sh:expression}, SHACL-JS ({@code sh:js}, {@code sh:jsValidator}, ...), any other
 * {@code sh:} predicate the backend does not know, and vendor vocabularies such as DASH or a
 * backend's own extensions. Which of them a backend ignores is, again, part of its
 * implementation's documentation.</p>
 */
public interface ShaclValidation {

  /**
   * Validates {@code data} against {@code shapes}.
   *
   * @param data the candidate data graph to validate; must not be {@code null}
   * @param shapes the SHACL shapes graph to validate against; must not be {@code null}
   * @param options validation options (e.g. RDFS subclass reasoning); must not be
   *     {@code null}
   * @return the validation report; never {@code null}
   * @throws NullPointerException if {@code data}, {@code shapes} or {@code options} is
   *     {@code null}
   * @throws ShaclValidationException if no report can be produced — e.g. the shapes graph
   *     cannot be parsed as SHACL, a shape uses a construct the backend does not support, or
   *     one of the two graphs holds a term the backend rejects — as opposed to a normal run
   *     that reports non-conformance through {@link ShaclReport#conforms()}. Implementations
   *     must wrap every such failure in this neutral type, keeping the original as
   *     {@linkplain Throwable#getCause() cause}, rather than letting a backend-specific
   *     exception reach the caller. This covers handing the graphs to the backend as much as
   *     the validation itself: a caller sees one exception type either way.
   */
  ShaclReport validate(ReadableGraph data, ReadableGraph shapes, ValidationOptions options);
}
