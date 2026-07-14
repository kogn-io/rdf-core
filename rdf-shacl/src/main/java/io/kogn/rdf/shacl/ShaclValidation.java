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
   */
  ShaclReport validate(ReadableGraph data, ReadableGraph shapes, ValidationOptions options);
}
