// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

import java.util.Locale;

/**
 * One {@code sh:resultMessage} literal, with its language tag preserved.
 *
 * <p>A SHACL shape may carry the same {@code sh:message} once per language. All of them
 * reach the caller in {@link ShaclResult#messages()}; selecting one is the caller's
 * decision, not this port's — see {@link ShaclResult} for why no selection helper is
 * offered here.</p>
 *
 * <h2>Tags are lower-cased, because selecting by tag is the point</h2>
 *
 * <p>BCP 47 defines language tags as case-insensitive, so {@code "de"}, {@code "DE"} and
 * {@code "dE"} name the same language — but a shapes graph may write any of them, and
 * RDF4J hands the tag on unchanged. Handing that through would mean every caller doing
 * the obvious thing ({@code "de".equals(message.language())}) silently misses a message
 * tagged {@code @DE}, in the one operation this type exists to enable.</p>
 *
 * <p>{@code language} is therefore normalised to lower case ({@link Locale#ROOT}), which
 * RDF 1.1 names the canonical form, so equality on this record and a plain
 * {@code equals} on the tag both mean what they look like. Only case changes:
 * {@code "de-AT"} is stored as {@code "de-at"}, which
 * {@link java.util.Locale#forLanguageTag(String)} parses identically. Subtags are not
 * otherwise rewritten and no tag is validated against the BCP 47 registry.</p>
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
 * @param language the language tag of the message (e.g. {@code "de"}, {@code "en-gb"}),
 *     lower-cased on construction, or {@code null} for a plain literal without a tag
 */
public record ShaclMessage(String text, String language) {

  /**
   * Validates the message and lower-cases the language tag.
   *
   * @throws IllegalArgumentException if {@code text} is {@code null}, or if
   *     {@code language} is non-{@code null} but blank
   */
  public ShaclMessage {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    if (language != null) {
      if (language.isBlank()) {
        throw new IllegalArgumentException("language must not be blank; use null for an untagged message");
      }
      language = language.toLowerCase(Locale.ROOT);
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
