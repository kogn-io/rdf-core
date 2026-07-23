// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

import java.util.List;

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
 * @param focusNode the string representation of the node that failed validation (an
 *     IRI or blank node identifier); must not be {@code null}
 * @param path the string representation of the {@code sh:resultPath} that caused this
 *     result, or {@code null} if the shape that produced it carries no path (e.g. a
 *     node shape)
 * @param severity the severity of this result; must not be {@code null}
 * @param messages every {@code sh:resultMessage} of this result, in no meaningful order;
 *     must not be {@code null}, possibly empty
 */
public record ShaclResult(String focusNode, String path, Severity severity, List<ShaclMessage> messages) {

  /**
   * Validates and defensively copies the result.
   *
   * @throws IllegalArgumentException if {@code focusNode}, {@code severity} or
   *     {@code messages} is {@code null}
   * @throws NullPointerException if {@code messages} contains {@code null}
   */
  public ShaclResult {
    if (focusNode == null) {
      throw new IllegalArgumentException("focusNode must not be null");
    }
    if (severity == null) {
      throw new IllegalArgumentException("severity must not be null");
    }
    if (messages == null) {
      throw new IllegalArgumentException("messages must not be null");
    }
    messages = List.copyOf(messages);
  }
}
