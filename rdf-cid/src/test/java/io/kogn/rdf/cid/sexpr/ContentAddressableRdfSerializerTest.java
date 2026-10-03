// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
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
 * <p>{@code ContentAddressedIriGeneratorSexprTest} already covers determinism and distinctness
 * through the port a consumer actually calls; this class is left with only what that test
 * cannot reach — the raw serialized bytes, needed to tell apart two skolem names it has no way
 * to inspect from the outside, and to see that the base IRI never reaches them — plus the
 * internal namespace the base is mapped into for canonicalization.</p>
 */
class ContentAddressableRdfSerializerTest {

  private ContentAddressableRdfSerializer serializer;
  private RDF rdf;

  @BeforeEach
  void setUp() {
    RdfDatasetCanonicalizer canonicalizer = new RdfDatasetCanonicalizer();
    serializer = new ContentAddressableRdfSerializer(canonicalizer);
    rdf = new SimpleRdf();
  }

  @Test
  @DisplayName("sibling BlankNodes with identical local data get different skolem names")
  void siblingBlankNodesWithIdenticalLocalDataGetDifferentSkolemNames() {
    // Given: two sibling BlankNodes, both reachable via the same predicate and both holding
    // the same local triple — the same depth-1 neighbourhood, so a skolem naming keyed off a
    // hash of that neighbourhood would give both the same name.
    IRI resource = rdf.createIRI("http://example.org/r");
    IRI p = rdf.createIRI("http://example.org/p");
    IRI v = rdf.createIRI("http://example.org/v");
    BlankNode x = rdf.createBlankNode("x");
    BlankNode y = rdf.createBlankNode("y");

    List<Triple> triples = new ArrayList<>();
    triples.add(rdf.createTriple(resource, p, x));
    triples.add(rdf.createTriple(resource, p, y));
    triples.add(rdf.createTriple(x, v, rdf.createLiteral("1")));
    triples.add(rdf.createTriple(y, v, rdf.createLiteral("1")));

    // When: serialize and inspect the bytes that were hashed
    ContentAddressableRdfSerializer.ContentAddressableResult result = serializer.serializeWithUrn(resource, triples);
    String sexpr = new String(result.sexprBytes(), StandardCharsets.ISO_8859_1);

    // Then: the two BlankNodes were serialized under two distinct skolem names, not merged
    // into one. Each skolem name is a netstring field ("<byte-length>:urn:skolem:..."); read
    // the declared length to slice out exactly the value, rather than a fixed-width guess
    // that could run into the next field.
    Set<String> skolemNames = new HashSet<>();
    Matcher matcher = Pattern.compile("(\\d+):urn:skolem:_:c14n\\d+").matcher(sexpr);
    while (matcher.find()) {
      int length = Integer.parseInt(matcher.group(1));
      int valueStart = matcher.end(1) + 1;
      skolemNames.add(sexpr.substring(valueStart, valueStart + length));
    }
    assertThat(skolemNames).as("two structurally identical siblings must not collapse onto one skolem name").hasSize(2);
  }

  @Test
  @DisplayName("the base IRI and its fragment IRIs never reach the hashed bytes, in any position")
  void baseIriNeverReachesTheHashedBytes() {
    String base = "http://example.org/draft";
    IRI self = rdf.createIRI(base);
    IRI part = rdf.createIRI(base + "#part");

    List<Triple> triples = new ArrayList<>();
    triples.add(rdf.createTriple(self, rdf.createIRI("http://example.org/hasPart"), part));
    triples.add(rdf.createTriple(part, rdf.createIRI(base + "#relation"), self));

    String sexpr = new String(serializer.serializeWithUrn(self, triples).sexprBytes(), StandardCharsets.UTF_8);

    assertThat(sexpr).doesNotContain(base).contains("1:S").contains("1:F4:part").contains("1:F8:relation");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"s", "s#", "s#part", "e:x", "e:e:x", "", "x"})
  @DisplayName("a foreign IRI inside the internal canonicalization namespace goes in as written, posing as nothing")
  void foreignIriInTheCanonicalizationNamespaceGoesInAsWritten(String suffix) {
    IRI self = rdf.createIRI("http://example.org/doc");
    IRI p = rdf.createIRI("http://example.org/p");
    String lookalike = ContentAddressableRdfSerializer.CANONICALIZATION_SELF_PREFIX + suffix;

    ContentAddressableRdfSerializer.ContentAddressableResult foreign = serializer.serializeWithUrn(self,
        List.of(rdf.createTriple(self, p, rdf.createIRI(lookalike))));

    assertThat(new String(foreign.sexprBytes(), StandardCharsets.UTF_8)).as("the foreign IRI goes in as written")
        .contains("1:I" + lookalike.length() + ":" + lookalike);
    assertThat(foreign.urn()).as("not the resource itself")
        .isNotEqualTo(serializer.serializeWithUrn(self, List.of(rdf.createTriple(self, p, self))).urn());
    assertThat(foreign.urn()).as("not a fragment of the resource")
        .isNotEqualTo(serializer
            .serializeWithUrn(self, List.of(rdf.createTriple(self, p, rdf.createIRI("http://example.org/doc#part"))))
            .urn());
  }

  @Test
  @DisplayName("a base IRI that itself lies in the internal canonicalization namespace is still the base")
  void baseInsideTheCanonicalizationNamespace() {
    String namespace = ContentAddressableRdfSerializer.CANONICALIZATION_SELF_PREFIX;
    IRI self = rdf.createIRI(namespace + "e:x");
    IRI p = rdf.createIRI("http://example.org/p");

    List<Triple> triples = List.of(rdf.createTriple(self, p, self),
        rdf.createTriple(self, p, rdf.createIRI(namespace + "x")));
    String sexpr = new String(serializer.serializeWithUrn(self, triples).sexprBytes(), StandardCharsets.UTF_8);

    assertThat(sexpr).as("the base goes in as the placeholder").contains("1:S").doesNotContain(namespace + "e:x");
    assertThat(sexpr).as("its foreign neighbour as written")
        .contains("1:I" + (namespace + "x").length() + ":" + namespace + "x");
  }

  @Test
  @DisplayName("a literal typed with the base or a fragment of it carries the placeholder, not the base")
  void datatypeOfTheBaseGoesInAsPlaceholder() {
    String base = "http://example.org/draft";
    IRI self = rdf.createIRI(base);
    IRI p = rdf.createIRI("http://example.org/p");

    List<Triple> triples = List.of(rdf.createTriple(self, p, rdf.createLiteral("1", rdf.createIRI(base + "#unit"))),
        rdf.createTriple(self, p, rdf.createLiteral("2", self)));
    String sexpr = new String(serializer.serializeWithUrn(self, triples).sexprBytes(), StandardCharsets.UTF_8);

    assertThat(sexpr).doesNotContain(base).contains("1:L1:11:F4:unit").contains("1:L1:21:S");
  }
}
