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
 * to inspect from the outside.</p>
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
    ContentAddressableRdfSerializer.ContentAddressableResult result = serializer.serializeWithUrn(triples);
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
}
