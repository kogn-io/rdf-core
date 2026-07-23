// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

/**
 * One {@code sh:resultMessage} literal, with its language tag preserved.
 *
 * <p>A SHACL shape may carry the same {@code sh:message} once per language. All of them
 * reach the caller in {@link ShaclResult#messages()}; selecting one is the caller's
 * decision, not this port's — see {@link ShaclResult} for why no selection helper is
 * offered here.</p>
 *
 * <h2>Untagged messages</h2>
 *
 * <p>{@code language} is deliberately nullable: {@code null} models a plain literal
 * carrying no language tag, which {@link #isUntagged()} reports. A blank tag is
 * <em>rejected</em> rather than treated as "untagged" — an empty string looks like a
 * legal tag to case-insensitive comparisons and would let "no language" pass silently
 * for "some language".</p>
 *
 * @param text the message text; must not be {@code null}
 * @param language the language tag as it appears in the shapes graph (e.g. {@code "de"},
 *     {@code "en-GB"}), or {@code null} for a plain literal without a tag. Tags are kept
 *     as-is; case is not normalised.
 */
public record ShaclMessage(String text, String language) {

  /**
   * Validates the message.
   *
   * @throws IllegalArgumentException if {@code text} is {@code null}, or if
   *     {@code language} is non-{@code null} but blank
   */
  public ShaclMessage {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    if (language != null && language.isBlank()) {
      throw new IllegalArgumentException("language must not be blank; use null for an untagged message");
    }
  }

  /**
   * Creates a message without a language tag.
   *
   * @param text the message text; must not be {@code null}
   * @return an untagged message
   * @throws IllegalArgumentException if {@code text} is {@code null}
   */
  public static ShaclMessage untagged(String text) {
    return new ShaclMessage(text, null);
  }

  /**
   * Returns whether this message carries no language tag.
   *
   * @return {@code true} if {@link #language()} is {@code null}
   */
  public boolean isUntagged() {
    return language == null;
  }
}
