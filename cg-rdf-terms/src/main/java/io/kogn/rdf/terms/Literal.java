// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

import java.util.Optional;

/**
 * Represents a Literal value in RDF.
 *
 * <p>Literals contain textual or numeric values and may have an associated language tag
 * or datatype IRI.</p>
 *
 * <h2>Equality</h2>
 *
 * <p>Equality is defined component-wise and is independent of the implementing class.
 * Following the <a href="https://commons.apache.org/proper/commons-rdf/">Commons RDF</a>
 * contract, an implementation's {@link Object#equals(Object)} <strong>must</strong>
 * return {@code true} if and only if the other object is also a {@code Literal} and all
 * three of the following hold:</p>
 *
 * <ul>
 *   <li>their {@link #getLexicalForm()} are equal {@link String}s;</li>
 *   <li>their {@link #getDatatype()} IRIs are equal (see {@link IRI});</li>
 *   <li>their {@link #getLanguageTag()} are equal {@link java.util.Optional}s — both
 *       absent, or both present and equal (language tags are compared as-is; case is
 *       not normalised here).</li>
 * </ul>
 *
 * <p>{@link Object#hashCode()} <strong>must</strong> be derived from the same three
 * components so that equal literals share a hash code.</p>
 */
public interface Literal extends RDFTerm {

  /**
   * Returns the lexical form of the literal (the actual string value).
   *
   * @return the lexical form
   */
  String getLexicalForm();

  /**
   * Returns the datatype IRI of this literal.
   *
   * @return the datatype IRI (e.g., xsd:string, xsd:integer)
   */
  IRI getDatatype();

  /**
   * Returns the language tag if present.
   *
   * @return Optional containing the language tag (e.g., "en", "de"), or empty if not present
   */
  Optional<String> getLanguageTag();
}
