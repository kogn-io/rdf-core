// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

/**
 * Serializes single RDF terms as <a href="https://www.w3.org/TR/n-triples/">RDF 1.1 N-Triples</a>, library-free.
 *
 * <p>Every method rejects, with an {@link IllegalArgumentException} whose message starts with
 * {@code "N-Triples:"}, a value the grammar cannot represent, instead of emitting invalid output.</p>
 */
final class NTriples {

  private static final int LAST_FORBIDDEN_CONTROL_OR_SPACE = 0x20;
  private static final String FORBIDDEN_IRI_CHARACTERS = "<>\"{}|^`\\";
  private static final String PREFIX = "N-Triples: ";

  private NTriples() {
  }

  /**
   * Serializes an IRI.
   *
   * @param iri the IRI string
   * @return {@code <iri>}
   * @throws IllegalArgumentException if {@code iri} contains a character the {@code IRIREF} production forbids
   */
  static String iri(final String iri) {
    for (int i = 0; i < iri.length(); i++) {
      final char c = iri.charAt(i);
      if (c <= LAST_FORBIDDEN_CONTROL_OR_SPACE || FORBIDDEN_IRI_CHARACTERS.indexOf(c) >= 0) {
        throw new IllegalArgumentException(
            PREFIX + "IRI contains the forbidden character U+" + String.format("%04X", (int) c) + " at index " + i);
      }
    }
    return "<" + iri + ">";
  }

  /**
   * Serializes a blank node label.
   *
   * @param label the blank node identifier
   * @return {@code _:label}
   * @throws IllegalArgumentException if {@code label} is not a valid {@code BLANK_NODE_LABEL}
   */
  static String blankNode(final String label) {
    if (!isBlankNodeLabel(label)) {
      throw new IllegalArgumentException(PREFIX + "not a valid blank node label: " + label);
    }
    return "_:" + label;
  }

  /**
   * Serializes a literal.
   *
   * @param lexicalForm the lexical form
   * @param datatype the datatype IRI string; ignored when {@code languageTag} is not {@code null}
   * @param languageTag the language tag, or {@code null}
   * @return the quoted, escaped lexical form followed by {@code @tag} or {@code ^^<datatype>}
   * @throws IllegalArgumentException if the language tag or the datatype IRI cannot be serialized
   */
  static String literal(final String lexicalForm, final String datatype, final String languageTag) {
    final String quoted = "\"" + escape(lexicalForm) + "\"";
    if (languageTag == null) {
      return quoted + "^^" + iri(datatype);
    }
    if (!isLanguageTag(languageTag)) {
      throw new IllegalArgumentException(PREFIX + "not a valid language tag: " + languageTag);
    }
    return quoted + "@" + languageTag;
  }

  private static String escape(final String lexicalForm) {
    final StringBuilder out = new StringBuilder(lexicalForm.length() + 2);
    for (int i = 0; i < lexicalForm.length(); i++) {
      final char c = lexicalForm.charAt(i);
      switch (c) {
      case '"' -> out.append("\\\"");
      case '\\' -> out.append("\\\\");
      case '\n' -> out.append("\\n");
      case '\r' -> out.append("\\r");
      default -> out.append(c);
      }
    }
    return out.toString();
  }

  private static boolean isLanguageTag(final String tag) {
    // [a-zA-Z]+ ('-' [a-zA-Z0-9]+)*
    int i = 0;
    int run = 0;
    for (; i < tag.length() && isAsciiLetter(tag.charAt(i)); i++) {
      run++;
    }
    if (run == 0) {
      return false;
    }
    while (i < tag.length()) {
      if (tag.charAt(i++) != '-') {
        return false;
      }
      run = 0;
      for (; i < tag.length() && (isAsciiLetter(tag.charAt(i)) || isDigit(tag.charAt(i))); i++) {
        run++;
      }
      if (run == 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean isBlankNodeLabel(final String label) {
    // (PN_CHARS_U | [0-9]) ((PN_CHARS | '.')* PN_CHARS)?
    if (label.isEmpty()) {
      return false;
    }
    final int[] cps = label.codePoints().toArray();
    if (!isNameStart(cps[0]) && !isDigit(cps[0])) {
      return false;
    }
    for (int i = 1; i < cps.length; i++) {
      final boolean last = i == cps.length - 1;
      if (!isNameChar(cps[i]) && (last || cps[i] != '.')) {
        return false;
      }
    }
    return true;
  }

  private static boolean isNameStart(final int cp) {
    return isNameBase(cp) || cp == '_' || cp == ':';
  }

  private static boolean isNameChar(final int cp) {
    return isNameStart(cp) || cp == '-' || isDigit(cp) || cp == 0xB7 || cp >= 0x300 && cp <= 0x36F
        || cp >= 0x203F && cp <= 0x2040;
  }

  private static boolean isNameBase(final int cp) {
    return isAsciiLetter(cp) || cp >= 0xC0 && cp <= 0xD6 || cp >= 0xD8 && cp <= 0xF6 || cp >= 0xF8 && cp <= 0x2FF
        || cp >= 0x370 && cp <= 0x37D || cp >= 0x37F && cp <= 0x1FFF || cp >= 0x200C && cp <= 0x200D
        || cp >= 0x2070 && cp <= 0x218F || cp >= 0x2C00 && cp <= 0x2FEF || cp >= 0x3001 && cp <= 0xD7FF
        || cp >= 0xF900 && cp <= 0xFDCF || cp >= 0xFDF0 && cp <= 0xFFFD || cp >= 0x10000 && cp <= 0xEFFFF;
  }

  private static boolean isAsciiLetter(final int cp) {
    return cp >= 'a' && cp <= 'z' || cp >= 'A' && cp <= 'Z';
  }

  private static boolean isDigit(final int cp) {
    return cp >= '0' && cp <= '9';
  }
}
