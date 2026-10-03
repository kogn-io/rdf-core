// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Unit tests for {@link ContentAddressableRdfSerializer}.
 *
 * <p>{@code ContentAddressedIriGeneratorSexprTest} and the ni-rdf/1 vectors already cover
 * determinism and distinctness through the port a consumer actually calls; this class is left
 * with what needs the raw hashed bytes — two blank node labels, the base IRI never reaching
 * them, language tags lower-cased before the labels are issued — and with the reserved IRI.</p>
 */
class ContentAddressableRdfSerializerTest {

  private static final String RESERVED = ContentAddressableRdfSerializer.RESERVED_IRI;

  private final ContentAddressableRdfSerializer serializer = new ContentAddressableRdfSerializer(
      new RdfDatasetCanonicalizer());
  private final RDF rdf = new SimpleRdf();
  private final IRI self = rdf.createIRI("http://example.org/doc");
  private final IRI p = rdf.createIRI("http://example.org/p");

  @Test
  @DisplayName("sibling blank nodes with identical local data get different labels")
  void siblingBlankNodesWithIdenticalLocalDataGetDifferentLabels() {
    // Given: two sibling blank nodes, both reachable via the same predicate and both holding
    // the same local triple — the same neighbourhood, so a labelling keyed off a hash of that
    // neighbourhood alone would give both the same label.
    IRI v = rdf.createIRI("http://example.org/v");
    BlankNode x = rdf.createBlankNode("x");
    BlankNode y = rdf.createBlankNode("y");

    List<Triple> triples = new ArrayList<>();
    triples.add(rdf.createTriple(self, p, x));
    triples.add(rdf.createTriple(self, p, y));
    triples.add(rdf.createTriple(x, v, rdf.createLiteral("1")));
    triples.add(rdf.createTriple(y, v, rdf.createLiteral("1")));

    // When: serialize and inspect the bytes that were hashed
    String hashed = hashed(self, triples);

    // Then: the two blank nodes went in under two distinct labels, not merged into one
    Set<String> labels = new HashSet<>();
    Matcher matcher = Pattern.compile("1:B5:(c14n\\d)").matcher(hashed);
    while (matcher.find()) {
      labels.add(matcher.group(1));
    }
    assertThat(labels).as("two structurally identical siblings must not collapse onto one label")
        .containsExactlyInAnyOrder("c14n0", "c14n1");
  }

  @Test
  @DisplayName("the base IRI and its fragment IRIs never reach the hashed bytes, in any position")
  void baseIriNeverReachesTheHashedBytes() {
    String base = "http://example.org/draft";
    IRI draft = rdf.createIRI(base);
    IRI part = rdf.createIRI(base + "#part");

    List<Triple> triples = List.of(rdf.createTriple(draft, rdf.createIRI("http://example.org/hasPart"), part),
        rdf.createTriple(part, rdf.createIRI(base + "#relation"), draft));

    assertThat(hashed(draft, triples)).doesNotContain(base)
        .doesNotContain(RESERVED)
        .contains("1:S")
        .contains("1:F4:part")
        .contains("1:F8:relation");
  }

  @Test
  @DisplayName("a literal typed with the base or a fragment of it carries the placeholder, not the base")
  void datatypeOfTheBaseGoesInAsPlaceholder() {
    String base = "http://example.org/draft";
    IRI draft = rdf.createIRI(base);

    List<Triple> triples = List.of(rdf.createTriple(draft, p, rdf.createLiteral("1", rdf.createIRI(base + "#unit"))),
        rdf.createTriple(draft, p, rdf.createLiteral("2", draft)));

    assertThat(hashed(draft, triples)).doesNotContain(base).contains("1:L1:11:F4:unit0:").contains("1:L1:21:S0:");
  }

  @ParameterizedTest(name = "<R{0}>")
  @ValueSource(strings = {"", "#", "#part", "#a#b"})
  @DisplayName("an IRI whose base is the reserved IRI is rejected, not escaped")
  void iriWithTheReservedBaseIsRejected(String suffix) {
    List<Triple> triples = List.of(rdf.createTriple(self, p, rdf.createIRI(RESERVED + suffix)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> serializer.serializeWithIri(self, triples))
        .withMessageStartingWith("[RESERVED_IRI]")
        .withMessageContaining(RESERVED + suffix);
  }

  @ParameterizedTest(name = "<R{0}>")
  @ValueSource(strings = {"x", "/x", "?q=1", ":x"})
  @DisplayName("an IRI that merely starts with the reserved IRI is an ordinary IRI and goes in as written")
  void iriThatOnlyStartsWithTheReservedIriGoesInAsWritten(String suffix) {
    String lookalike = RESERVED + suffix;

    String hashed = hashed(self, List.of(rdf.createTriple(self, p, rdf.createIRI(lookalike))));

    assertThat(hashed).contains("1:I" + lookalike.length() + ":" + lookalike);
  }

  @Test
  @DisplayName("a base IRI that merely starts with the reserved IRI is an ordinary base")
  void baseThatOnlyStartsWithTheReservedIri() {
    IRI base = rdf.createIRI(RESERVED + "x");

    String hashed = hashed(base, List.of(rdf.createTriple(base, p, base)));

    assertThat(hashed).as("the base goes in as the placeholder").doesNotContain(RESERVED).contains("(1:S1:I");
  }

  @Test
  @DisplayName("language tags are lower-cased before the blank node labels are issued")
  void languageTagsAreLowerCasedBeforeCanonicalization() {
    // The labels RDFC-1.0 issues depend on the language tags next to a blank node. Lower-casing
    // only while serializing would let "EN" and "en" swap two labels for some values, so a few
    // dozen are tried.
    for (int i = 0; i < 32; i++) {
      assertThat(hashed(self, twoTaggedBlankNodes(i, "EN-GB"))).as("value %d", i)
          .isEqualTo(hashed(self, twoTaggedBlankNodes(i, "en-gb")));
    }
  }

  @Test
  @DisplayName("two spellings of one language tag are one literal, and one triple")
  void twoSpellingsOfOneLanguageTagCollapse() {
    List<Triple> both = List.of(rdf.createTriple(self, p, rdf.createLiteral("x", "EN")),
        rdf.createTriple(self, p, rdf.createLiteral("x", "en")));
    List<Triple> one = List.of(rdf.createTriple(self, p, rdf.createLiteral("x", "en")));

    assertThat(hashed(self, both)).isEqualTo(hashed(self, one));
  }

  @Test
  @DisplayName("only A-Z are lower-cased in a language tag, nothing else")
  void onlyAsciiLettersAreLowerCased() {
    assertThat(Terms.lowerCaseLanguageTag("DE-CH-x-ÄÖ1")).isEqualTo("de-ch-x-ÄÖ1");
  }

  private List<Triple> twoTaggedBlankNodes(int value, String tag) {
    BlankNode tagged = rdf.createBlankNode("tagged");
    BlankNode other = rdf.createBlankNode("other");
    IRI q = rdf.createIRI("http://example.org/q");
    return List.of(rdf.createTriple(self, p, tagged), rdf.createTriple(self, p, other),
        rdf.createTriple(tagged, q, rdf.createLiteral("v" + value, tag)),
        rdf.createTriple(other, q, rdf.createLiteral("v" + value, "de")));
  }

  private String hashed(IRI base, List<Triple> triples) {
    return new String(serializer.serializeWithIri(base, triples).sexprBytes(), StandardCharsets.UTF_8);
  }
}
