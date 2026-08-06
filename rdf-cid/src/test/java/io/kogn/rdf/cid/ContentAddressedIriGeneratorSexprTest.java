// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.cid.sexpr.ContentAddressableRdfSerializer;
import io.kogn.rdf.cid.sexpr.RdfDatasetCanonicalizer;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabXsd;

/**
 * Tests {@link ContentAddressedIriGeneratorSexpr} through the port a consumer actually calls.
 *
 * <p>The centre of gravity is {@link Distinctness}: an identifier that two different graphs
 * share is worse than no identifier at all, because deduplication and integrity checks both
 * read "same identifier" as "same content". Each case there is one way two graphs can differ
 * — and every one of them was a real collision before, the whole point of the class being to
 * keep them from coming back.</p>
 */
class ContentAddressedIriGeneratorSexprTest {

  private static final String EX = "http://example.org/";

  private ContentAddressedIriGenerator generator;
  private RDF rdf;

  @BeforeEach
  void setUp() {
    rdf = new SimpleRdf();
    generator = new ContentAddressedIriGeneratorSexpr(rdf,
        new ContentAddressableRdfSerializer(new RdfDatasetCanonicalizer(rdf), rdf));
  }

  @Nested
  @DisplayName("different content gets different identifiers")
  class Distinctness {

    @Test
    @DisplayName("literals differing only in datatype")
    void literalsDifferingOnlyInDatatype() {
      IRI cid1 = cidOf(rdf.createLiteral("100", rdf.createIRI(VocabXsd.INTEGER.getIRIString())));
      IRI cid2 = cidOf(rdf.createLiteral("100", rdf.createIRI(VocabXsd.DECIMAL.getIRIString())));

      assertThat(cid1).as("xsd:integer 100 is not xsd:decimal 100").isNotEqualTo(cid2);
    }

    @Test
    @DisplayName("literals differing only in language tag")
    void literalsDifferingOnlyInLanguageTag() {
      IRI cid1 = cidOf(rdf.createLiteral("Bank", "en"));
      IRI cid2 = cidOf(rdf.createLiteral("Bank", "de"));

      assertThat(cid1).as("an English Bank is not a German Bank").isNotEqualTo(cid2);
    }

    @Test
    @DisplayName("an IRI object and a literal spelling out that same IRI")
    void iriObjectAndLiteralWithTheSameSpelling() {
      IRI cid1 = cidOf(rdf.createIRI(EX + "x"));
      IRI cid2 = cidOf(rdf.createLiteral(EX + "x"));

      assertThat(cid1).as("a link is not a string that looks like one").isNotEqualTo(cid2);
    }

    @Test
    @DisplayName("graphs differing only in their subject IRI")
    void graphsDifferingOnlyInSubjectIri() {
      Graph g1 = graph();
      g1.add(rdf.createIRI(EX + "person/1"), rdf.createIRI(EX + "name"), rdf.createLiteral("Bob"));
      Graph g2 = graph();
      g2.add(rdf.createIRI(EX + "person/2"), rdf.createIRI(EX + "name"), rdf.createLiteral("Bob"));

      assertThat(generator.generateIri(g1)).as("two people named Bob are two graphs")
          .isNotEqualTo(generator.generateIri(g2));
    }

    @Test
    @DisplayName("graphs differing only in a nested blank node's value")
    void graphsDifferingOnlyDeepInsideABlankNodeChain() {
      assertThat(nestedGraphCid("100")).isNotEqualTo(nestedGraphCid("200"));
    }
  }

  @Nested
  @DisplayName("the same content gets the same identifier")
  class Stability {

    @Test
    @DisplayName("regardless of how often it is asked")
    void regardlessOfHowOftenItIsAsked() {
      assertThat(nestedGraphCid("100")).isEqualTo(nestedGraphCid("100"));
    }

    @Test
    @DisplayName("regardless of the blank node labels — this is what makes re-imports detectable")
    void regardlessOfBlankNodeLabels() {
      IRI cid1 = generator.generateIri(nestedGraph("100", "bn1", "bn2"));
      IRI cid2 = generator.generateIri(nestedGraph("100", "totally", "different"));

      assertThat(cid1).isEqualTo(cid2);
    }

    @Test
    @DisplayName("regardless of the case of the language tag — RDF 1.1 compares them case-insensitively")
    void regardlessOfLanguageTagCase() {
      IRI cid1 = cidOf(rdf.createLiteral("Bank", "en"));
      IRI cid2 = cidOf(rdf.createLiteral("Bank", "EN"));

      assertThat(cid1).as("\"Bank\"@en and \"Bank\"@EN are the same RDF literal").isEqualTo(cid2);
    }

    @Test
    @DisplayName("regardless of the order the triples were added in")
    void regardlessOfTripleOrder() {
      IRI subject = rdf.createIRI(EX + "resource");
      IRI a = rdf.createIRI(EX + "a");
      IRI b = rdf.createIRI(EX + "b");

      Graph forwards = graph();
      forwards.add(subject, a, rdf.createLiteral("1"));
      forwards.add(subject, b, rdf.createLiteral("2"));

      Graph backwards = graph();
      backwards.add(subject, b, rdf.createLiteral("2"));
      backwards.add(subject, a, rdf.createLiteral("1"));

      assertThat(generator.generateIri(forwards)).isEqualTo(generator.generateIri(backwards));
    }
  }

  @Nested
  @DisplayName("golden vectors — the identifier itself, not just its relation to other identifiers")
  class GoldenVectors {

    // Every other test in this class checks a *relationship* between two identifiers (equal,
    // not equal, matches this regex). None of them notices if the derivation itself moves: a
    // renamed header field, a swapped kind tag, a different 256-bit digest algorithm would
    // still leave every relative assertion green. These three pin the actual value, so a
    // silent shift in the derivation shows up here.
    //
    // A failure here is a breaking change for every already-minted identifier (ADR-0014) and
    // is not fixed by updating the expected string to match the new output — that only hides
    // the break. Regenerate the expectation only when the change to the derivation is the
    // point of the commit, and say so in the commit message.

    @Test
    @DisplayName("a flat graph with one literal")
    void aFlatGraphWithOneLiteral() {
      Graph graph = graph();
      graph.add(rdf.createIRI(EX + "golden/1"), rdf.createIRI(EX + "name"), rdf.createLiteral("Golden Vector"));

      assertThat(generator.generateIri(graph).getIRIString())
          .isEqualTo("urn:cid:3phz7ycilpfxgkyjzpsckoji353chwzbdoxstrddkeh5gun33zdq");
    }

    @Test
    @DisplayName("a graph with a typed literal and a language-tagged literal")
    void aGraphWithATypedLiteralAndALanguageTaggedLiteral() {
      Graph graph = graph();
      IRI subject = rdf.createIRI(EX + "golden/2");
      graph.add(subject, rdf.createIRI(EX + "count"),
          rdf.createLiteral("42", rdf.createIRI(VocabXsd.INTEGER.getIRIString())));
      graph.add(subject, rdf.createIRI(EX + "label"), rdf.createLiteral("Golden", "en"));

      assertThat(generator.generateIri(graph).getIRIString())
          .isEqualTo("urn:cid:i2hk6tpaiulbvreirtbdp7zdrjctdas6khqw46ogianc3osmqcya");
    }

    @Test
    @DisplayName("a graph with a nested blank node chain")
    void aGraphWithANestedBlankNodeChain() {
      Graph graph = graph();
      IRI resource = rdf.createIRI(EX + "golden/3");
      BlankNode table = rdf.createBlankNode("t");
      BlankNode entry = rdf.createBlankNode("e");
      graph.add(resource, rdf.createIRI(EX + "hasTable"), table);
      graph.add(table, rdf.createIRI(EX + "hasEntry"), entry);
      graph.add(entry, rdf.createIRI(EX + "hasValue"),
          rdf.createLiteral("7", rdf.createIRI(VocabXsd.DECIMAL.getIRIString())));

      assertThat(generator.generateIri(graph).getIRIString())
          .isEqualTo("urn:cid:n7ztj2tfhw5fkccvy5yuuwtlajaphuq7gxfwyntwo4n5dfmor4zq");
    }
  }

  @Nested
  @DisplayName("the identifier is a syntactically valid urn:cid:")
  class Format {

    @Test
    @DisplayName("urn:cid: prefix, unpadded lower-case Base32, nothing else")
    void urnCidPrefixUnpaddedLowerCaseBase32() {
      String cid = nestedGraphCid("100").getIRIString();

      assertThat(cid).startsWith("urn:cid:");
      // 32 digest bytes Base32-encode to 52 characters once the "====" padding is dropped;
      // a padded value would not be a valid URN namespace-specific string.
      assertThat(cid).matches("urn:cid:[a-z2-7]{52}");
    }
  }

  @Nested
  @DisplayName("a graph it cannot address is rejected, not silently reduced")
  class Preconditions {

    @Test
    @DisplayName("null graph")
    void nullGraph() {
      assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> generator.generateIri(null));
    }

    @Test
    @DisplayName("empty graph")
    void emptyGraph() {
      assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> generator.generateIri(graph()));
    }

    @Test
    @DisplayName("two IRI subjects — which of the two would the identifier be for?")
    void twoIriSubjects() {
      Graph graph = graph();
      graph.add(rdf.createIRI(EX + "one"), rdf.createIRI(EX + "value"), rdf.createLiteral("1"));
      graph.add(rdf.createIRI(EX + "two"), rdf.createIRI(EX + "value"), rdf.createLiteral("2"));

      assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> generator.generateIri(graph))
          .withMessageContaining("2");
    }

    @Test
    @DisplayName("no IRI subject at all")
    void noIriSubject() {
      Graph graph = graph();
      graph.add(rdf.createBlankNode("only"), rdf.createIRI(EX + "value"), rdf.createLiteral("1"));

      assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> generator.generateIri(graph))
          .withMessageContaining("0");
    }

    @Test
    @DisplayName("a free-standing blank node component would drop out of the digest unnoticed")
    void freeStandingBlankNodeComponent() {
      Graph reachableOnly = graph();
      reachableOnly.add(rdf.createIRI(EX + "r"), rdf.createIRI(EX + "v"), rdf.createLiteral("1"));

      Graph withOrphan = graph();
      withOrphan.add(rdf.createIRI(EX + "r"), rdf.createIRI(EX + "v"), rdf.createLiteral("1"));
      withOrphan.add(rdf.createBlankNode("orphan"), rdf.createIRI(EX + "w"), rdf.createLiteral("2"));

      // The orphan triple is legal RDF but reachable from no IRI subject. Hashing only the
      // reachable part would hand both graphs the same identifier without saying so.
      assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> generator.generateIri(withOrphan))
          .withMessageContaining("not reachable");
    }
  }

  // Helpers

  private Graph graph() {
    return rdf.createGraph();
  }

  private IRI nestedGraphCid(String value) {
    return generator.generateIri(nestedGraph(value, "table", "entry"));
  }

  private IRI cidOf(RDFTerm object) {
    Graph graph = graph();
    graph.add(rdf.createIRI(EX + "resource"), rdf.createIRI(EX + "value"), object);
    return generator.generateIri(graph);
  }

  /** A resource whose measurement hangs off two chained blank nodes. */
  private Graph nestedGraph(String value, String tableLabel, String entryLabel) {
    Graph graph = graph();

    IRI resource = rdf.createIRI(EX + "resource");
    BlankNode table = rdf.createBlankNode(tableLabel);
    BlankNode entry = rdf.createBlankNode(entryLabel);

    graph.add(resource, rdf.createIRI(EX + "hasTable"), table);
    graph.add(table, rdf.createIRI(EX + "hasEntry"), entry);
    graph.add(entry, rdf.createIRI(EX + "hasUnit"), rdf.createIRI(EX + "gram"));
    graph.add(entry, rdf.createIRI(EX + "hasValue"),
        rdf.createLiteral(value, rdf.createIRI(VocabXsd.DECIMAL.getIRIString())));

    return graph;
  }
}
