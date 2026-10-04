// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

import java.util.List;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;

/**
 * A single SHACL validation result, corresponding to one {@code sh:ValidationResult}.
 *
 * <h2>Messages are handed over whole</h2>
 *
 * <p>A shape may carry {@code sh:message} once per language. <em>Every</em>
 * {@code sh:resultMessage} of this result reaches the caller in {@link #messages()},
 * language tag intact; this port picks none of them. Choosing a language is policy — a
 * fallback chain is a deployment decision (which language was requested, which the
 * deployment defaults to, whether an untagged literal beats a foreign-tagged one) and
 * belongs where that context exists, which is not here.</p>
 *
 * <p>{@code messages} is empty when the shape produced no message at all:
 * {@code sh:message} is optional in SHACL, so a result without one is reachable and
 * carries no error of its own.</p>
 *
 * <h2>The order of {@code messages} carries no meaning</h2>
 *
 * <p>RDF is unordered, and the order here is whatever the backend's validation report
 * yields — in practice an artifact of the parse order of the shapes file. Reordering two
 * {@code sh:message} lines may reorder this list. Select by
 * {@link ShaclMessage#language()}; do not read {@code messages().get(0)} as "the"
 * message.</p>
 *
 * @param focusNode the node that failed validation, as the term it is in the data graph
 *     (IRI, blank node or literal — its kind is preserved); must not be {@code null}
 * @param path the {@code sh:resultPath} that caused this result, rendered in SPARQL
 *     property path syntax (an IRI as {@code <iri>}, composed with {@code /}, {@code |},
 *     {@code ^}, {@code *}, {@code +}, {@code ?}), or {@code null} if the shape that
 *     produced it carries no path (e.g. a node shape)
 * @param severity the severity of this result, mapped onto the closed {@link Severity}
 *     enum (a severity IRI other than {@code sh:Warning}/{@code sh:Info} maps to
 *     {@link Severity#VIOLATION}); must not be {@code null}
 * @param severityIri the {@code sh:resultSeverity} IRI as reported, so a custom severity
 *     keeps its identity even though {@code severity} cannot express it; must not be
 *     {@code null}
 * @param messages every {@code sh:resultMessage} of this result, in no meaningful order;
 *     must not be {@code null}, possibly empty
 */
public record ShaclResult(RDFTerm focusNode, String path, Severity severity, IRI severityIri,
    List<ShaclMessage> messages) {

  /**
   * Validates and defensively copies the result.
   *
   * @throws IllegalArgumentException if {@code focusNode}, {@code severity},
   *     {@code severityIri} or {@code messages} is {@code null}
   * @throws NullPointerException if {@code messages} contains {@code null}
   */
  public ShaclResult {
    if (focusNode == null) {
      throw new IllegalArgumentException("focusNode must not be null");
    }
    if (severity == null) {
      throw new IllegalArgumentException("severity must not be null");
    }
    if (severityIri == null) {
      throw new IllegalArgumentException("severityIri must not be null");
    }
    if (messages == null) {
      throw new IllegalArgumentException("messages must not be null");
    }
    messages = List.copyOf(messages);
  }
}
