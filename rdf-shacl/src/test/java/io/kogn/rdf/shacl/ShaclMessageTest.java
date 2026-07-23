// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class ShaclMessageTest {

  @Test
  void taggedMessageKeepsItsTag() {
    ShaclMessage message = new ShaclMessage("Name fehlt", "de-at");

    assertThat(message.text()).isEqualTo("Name fehlt");
    assertThat(message.language()).isEqualTo("de-at");
    assertThat(message.isUntagged()).isFalse();
  }

  /**
   * Pins the reason tags are lower-cased: BCP 47 tags are case-insensitive, so a shapes
   * graph writing {@code @DE} names the same language as one writing {@code @de}. Passing
   * the case through would make the obvious selection — {@code "de".equals(language())} —
   * silently miss the message, in the one operation this type exists to enable.
   */
  @Test
  void languageTagIsLowerCased() {
    assertThat(new ShaclMessage("Name fehlt", "DE").language()).isEqualTo("de");
    assertThat(new ShaclMessage("Name fehlt", "de-AT").language()).isEqualTo("de-at");
    assertThat(new ShaclMessage("Colour", "EN-gb").language()).isEqualTo("en-gb");
  }

  /** Follows from the lower-casing: tags differing only in case compare equal. */
  @Test
  void tagsDifferingOnlyInCaseAreEqual() {
    assertThat(new ShaclMessage("Name fehlt", "DE")).isEqualTo(new ShaclMessage("Name fehlt", "de"));
  }

  /** Lower-casing must not make the tag unparseable — only its case changes. */
  @Test
  void lowerCasedTagStillParsesToTheSameLocale() {
    ShaclMessage message = new ShaclMessage("Name fehlt", "de-AT");

    assertThat(Locale.forLanguageTag(message.language())).isEqualTo(Locale.forLanguageTag("de-AT"));
  }

  @Test
  void untaggedMessageHasNullLanguage() {
    ShaclMessage message = ShaclMessage.untagged("Name is required");

    assertThat(message.language()).isNull();
    assertThat(message.isUntagged()).isTrue();
  }

  @Test
  void nullTextIsRejected() {
    assertThatThrownBy(() -> new ShaclMessage(null, "de")).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Pins the reason {@code null} rather than {@code ""} models "untagged": an empty
   * string looks like a legal tag to a case-insensitive comparison, so letting it
   * through would make "no language" silently pass for "some language".
   */
  @Test
  void blankLanguageIsRejectedRatherThanTreatedAsUntagged() {
    assertThatThrownBy(() -> new ShaclMessage("Name is required", "")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ShaclMessage("Name is required", "  ")).isInstanceOf(IllegalArgumentException.class);
  }
}
