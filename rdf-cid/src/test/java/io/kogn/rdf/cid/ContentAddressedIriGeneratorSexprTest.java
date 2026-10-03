// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.cid.sexpr.ContentAddressableRdfSerializer;
import io.kogn.rdf.cid.sexpr.RdfDatasetCanonicalizer;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
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
  private static final String NI_PREFIX = "ni:///sha3-256;";
  private static final String RESERVED = "urn:uuid:171650ec-eda4-47fd-9053-6a34696171c1";

  private ContentAddressedIriGenerator generator;
  private RDF rdf;

  @BeforeEach
  void setUp() {
    rdf = new SimpleRdf();
    generator = new ContentAddressedIriGeneratorSexpr(rdf);
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
    @DisplayName("a blank node and an IRI or a literal spelling out its canonical label")
    void blankNodeAndIriOrLiteralSpellingOutItsCanonicalLabel() {
      Graph withBlankNode = graph();
      withBlankNode.add(rdf.createIRI(EX + "r"), rdf.createIRI(EX + "p"), rdf.createBlankNode("b"));

      // RDFC-1.0 labels the single blank node c14n0, so this IRI and this literal spell out
      // exactly the label the blank node is serialized under.
      Graph withLookalikeIri = graph();
      withLookalikeIri.add(rdf.createIRI(EX + "r"), rdf.createIRI(EX + "p"), rdf.createIRI("c14n0"));
      Graph withLookalikeLiteral = graph();
      withLookalikeLiteral.add(rdf.createIRI(EX + "r"), rdf.createIRI(EX + "p"), rdf.createLiteral("c14n0"));

      IRI blankNode = generator.generateIri(rdf.createIRI(EX + "r"), withBlankNode);
      assertThat(blankNode).as("a blank node is not an IRI that spells its label")
          .isNotEqualTo(generator.generateIri(rdf.createIRI(EX + "r"), withLookalikeIri));
      assertThat(blankNode).as("a blank node is not a string that spells its label")
          .isNotEqualTo(generator.generateIri(rdf.createIRI(EX + "r"), withLookalikeLiteral));
    }

    @Test
    @DisplayName("a fragment of the base and the base itself as subject")
    void fragmentSubjectAndBaseSubject() {
      IRI cid1 = cidOfSubject(rdf.createIRI(EX + "doc"));
      IRI cid2 = cidOfSubject(rdf.createIRI(EX + "doc#x"));

      assertThat(cid1).as("<doc#x> describes a part of <doc>, not <doc>").isNotEqualTo(cid2);
    }

    @Test
    @DisplayName("an empty fragment of the base and the base itself as subject")
    void emptyFragmentSubjectAndBaseSubject() {
      IRI cid1 = cidOfSubject(rdf.createIRI(EX + "doc"));
      IRI cid2 = cidOfSubject(rdf.createIRI(EX + "doc#"));

      assertThat(cid1).as("<doc#> is a fragment IRI with an empty fragment, not <doc>").isNotEqualTo(cid2);
    }

    @Test
    @DisplayName("a reference to the resource itself and a reference to another IRI")
    void selfReferenceAndForeignReference() {
      IRI subject = rdf.createIRI(EX + "doc");
      Graph selfReference = graph();
      selfReference.add(subject, rdf.createIRI(EX + "sameAs"), subject);
      Graph foreignReference = graph();
      foreignReference.add(subject, rdf.createIRI(EX + "sameAs"), rdf.createIRI(EX + "other"));

      assertThat(generator.generateIri(subject, selfReference)).as("pointing at itself is not pointing elsewhere")
          .isNotEqualTo(generator.generateIri(subject, foreignReference));
    }

    @Test
    @DisplayName("a reference to the resource itself and a literal spelling out its IRI")
    void selfReferenceAndLiteralWithTheSameSpelling() {
      IRI subject = rdf.createIRI(EX + "doc");
      Graph selfReference = graph();
      selfReference.add(subject, rdf.createIRI(EX + "sameAs"), subject);
      Graph lookalikeLiteral = graph();
      lookalikeLiteral.add(subject, rdf.createIRI(EX + "sameAs"), rdf.createLiteral(EX + "doc"));

      assertThat(generator.generateIri(subject, selfReference))
          .as("a self-reference is not a string that looks like one")
          .isNotEqualTo(generator.generateIri(subject, lookalikeLiteral));
    }

    @Test
    @DisplayName("a literal typed with a fragment of the resource and one typed with the same fragment of another IRI")
    void ownFragmentDatatypeAndForeignFragmentDatatype() {
      IRI cid1 = cidOf(rdf.createLiteral("x", rdf.createIRI(EX + "resource#dt")));
      IRI cid2 = cidOf(rdf.createLiteral("x", rdf.createIRI(EX + "other#dt")));

      assertThat(cid1).as("the resource's own datatype is not someone else's").isNotEqualTo(cid2);
    }

    @Test
    @DisplayName("a literal typed with the resource itself and one typed with its empty fragment")
    void selfDatatypeAndEmptyFragmentDatatype() {
      IRI cid1 = cidOf(rdf.createLiteral("x", rdf.createIRI(EX + "resource")));
      IRI cid2 = cidOf(rdf.createLiteral("x", rdf.createIRI(EX + "resource#")));

      assertThat(cid1).as("^^<base> is not ^^<base#>").isNotEqualTo(cid2);
    }

    @Test
    @DisplayName("a reference to a fragment of the resource and the same fragment of another IRI")
    void ownFragmentReferenceAndForeignFragmentReference() {
      IRI subject = rdf.createIRI(EX + "doc");
      Graph ownFragment = graph();
      ownFragment.add(subject, rdf.createIRI(EX + "part"), rdf.createIRI(EX + "doc#a"));
      Graph foreignFragment = graph();
      foreignFragment.add(subject, rdf.createIRI(EX + "part"), rdf.createIRI(EX + "other#a"));

      assertThat(generator.generateIri(subject, ownFragment)).as("<doc#a> is a part of <doc>, <other#a> is not")
          .isNotEqualTo(generator.generateIri(subject, foreignFragment));
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
      IRI cid1 = generator.generateIri(rdf.createIRI(EX + "resource"), nestedGraph("100", "bn1", "bn2"));
      IRI cid2 = generator.generateIri(rdf.createIRI(EX + "resource"), nestedGraph("100", "totally", "different"));

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
    @DisplayName("regardless of the subject IRI — the resource's own IRI is not its content")
    void regardlessOfTheSubjectIri() {
      Graph g1 = graph();
      g1.add(rdf.createIRI(EX + "person/1"), rdf.createIRI(EX + "name"), rdf.createLiteral("Bob"));
      Graph g2 = graph();
      g2.add(rdf.createIRI(EX + "person/2"), rdf.createIRI(EX + "name"), rdf.createLiteral("Bob"));

      assertThat(generator.generateIri(rdf.createIRI(EX + "person/1"), g1))
          .as("the same description under two names is the same content")
          .isEqualTo(generator.generateIri(rdf.createIRI(EX + "person/2"), g2));
    }

    @Test
    @DisplayName("regardless of the subject IRI, under fragments of the base as well")
    void regardlessOfTheSubjectIriUnderFragments() {
      Graph g1 = graph();
      g1.add(rdf.createIRI(EX + "a#ac-3"), rdf.createIRI(EX + "name"), rdf.createLiteral("criterion"));
      Graph g2 = graph();
      g2.add(rdf.createIRI(EX + "b#ac-3"), rdf.createIRI(EX + "name"), rdf.createLiteral("criterion"));

      assertThat(generator.generateIri(rdf.createIRI(EX + "a"), g1))
          .isEqualTo(generator.generateIri(rdf.createIRI(EX + "b"), g2));
    }

    @Test
    @DisplayName("regardless of the subject IRI, also where a literal is typed with a fragment of it")
    void regardlessOfTheSubjectIriInADatatype() {
      Graph g1 = graph();
      g1.add(rdf.createIRI(EX + "a"), rdf.createIRI(EX + "value"), rdf.createLiteral("x", rdf.createIRI(EX + "a#dt")));
      Graph g2 = graph();
      g2.add(rdf.createIRI(EX + "b"), rdf.createIRI(EX + "value"), rdf.createLiteral("x", rdf.createIRI(EX + "b#dt")));

      assertThat(generator.generateIri(rdf.createIRI(EX + "a"), g1)).as("the datatype is a position like any other")
          .isEqualTo(generator.generateIri(rdf.createIRI(EX + "b"), g2));
    }

    @Test
    @DisplayName("regardless of the subject IRI, also where blank nodes hang off it")
    void regardlessOfTheSubjectIriWithBlankNodes() {
      // Blank node canonicalization (RDFC-1.0) hashes the IRIs next to a blank node into the
      // order its canonical labels are handed out in. Were the subject IRI to reach the
      // canonicalizer, two blank nodes could swap labels between one subject and another and
      // the identifier would move with the name after all. One subject could be lucky, so a
      // few dozen are tried.
      IRI first = rdf.createIRI(EX + "subject/0");
      IRI expected = generator.generateIri(first, twoBlankNodesUnder(first));
      for (int i = 1; i < 32; i++) {
        IRI subject = rdf.createIRI(EX + "subject/" + i);
        assertThat(generator.generateIri(subject, twoBlankNodesUnder(subject))).as("under %s", subject)
            .isEqualTo(expected);
      }
    }

    @Test
    @DisplayName("regardless of the subject IRI, also where a blank node holds a literal typed with a fragment of it")
    void regardlessOfTheSubjectIriInABlankNodesDatatype() {
      // Same reasoning as above, one step further out: the datatype IRI of a literal on a blank
      // node feeds that node's canonical label too, so the datatype must be mapped before
      // canonicalization just like the subject.
      IRI expected = generator.generateIri(rdf.createIRI(EX + "subject/0"),
          twoBlankNodesWithAnOwnDatatypeUnder(EX + "subject/0"));
      for (int i = 1; i < 32; i++) {
        String base = EX + "subject/" + i;
        assertThat(generator.generateIri(rdf.createIRI(base), twoBlankNodesWithAnOwnDatatypeUnder(base)))
            .as("under %s", base)
            .isEqualTo(expected);
      }
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

      assertThat(generator.generateIri(subject, forwards)).isEqualTo(generator.generateIri(subject, backwards));
    }
  }

  @Nested
  @DisplayName("a resource can carry its own identifier and still be verified")
  class SelfAddressing {

    @Test
    @DisplayName("renaming the resource to its own identifier leaves the identifier unchanged")
    void renamingTheResourceToItsIdentifierVerifies() {
      String draft = EX + "tmp";
      IRI self = rdf.createIRI(draft);
      IRI part = rdf.createIRI(draft + "#part");
      BlankNode meta = rdf.createBlankNode("meta");

      Graph graph = graph();
      graph.add(self, rdf.createIRI(EX + "title"), rdf.createLiteral("Draft"));
      graph.add(self, rdf.createIRI(EX + "hasPart"), part);
      graph.add(self, rdf.createIRI(EX + "sameAs"), self);
      graph.add(self, rdf.createIRI(EX + "meta"), meta);
      graph.add(meta, rdf.createIRI(EX + "about"), self);
      graph.add(meta, rdf.createIRI(EX + "about"), part);
      graph.add(part, rdf.createIRI(EX + "label"), rdf.createLiteral("Part"));
      graph.add(part, rdf.createIRI(EX + "of"), self);
      graph.add(part, rdf.createIRI(draft + "#relation"), part);
      graph.add(part, rdf.createIRI(EX + "size"), rdf.createLiteral("3", rdf.createIRI(draft + "#unit")));

      IRI cid = generator.generateIri(self, graph);
      Graph published = renameBase(graph, draft, cid.getIRIString());

      assertThat(published.stream()
          .flatMap(t -> Stream.of(t.getSubject(), t.getPredicate(), t.getObject()))
          .map(term -> term instanceof Literal literal ? literal.getDatatype() : term)
          .filter(IRI.class::isInstance)
          .map(term -> ((IRI) term).getIRIString())).as("nothing of the draft IRI left")
          .noneMatch(iri -> iri.startsWith(draft));
      assertThat(generator.generateIri(cid, published)).as("a reader re-derives the identifier the resource carries")
          .isEqualTo(cid);
    }
  }

  @Nested
  @DisplayName("the identifier is an ni: name in its canonical form")
  class Format {

    @Test
    @DisplayName("ni:///sha3-256; then unpadded base64url, nothing else")
    void niSha3256UnpaddedBase64Url() {
      String cid = nestedGraphCid("100").getIRIString();

      // 32 digest bytes base64url-encode to 43 characters once the "=" padding is dropped; no
      // authority, no query (ni-rdf/1 §4.8).
      assertThat(cid).matches("ni:///sha3-256;[A-Za-z0-9_-]{43}");
    }
  }

  @Nested
  @DisplayName("a graph it cannot address is rejected, not silently reduced")
  class Preconditions {

    @Test
    @DisplayName("null graph")
    void nullGraph() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "doc"), null));
    }

    @Test
    @DisplayName("empty graph")
    void emptyGraph() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "doc"), graph()))
          .withMessageStartingWith("[EMPTY_GRAPH]");
    }

    @Test
    @DisplayName("the base is checked before the graph — the first failing precondition is reported")
    void baseBeforeGraph() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "doc#a"), graph()))
          .withMessageStartingWith("[INVALID_BASE]");
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(RESERVED), graph()))
          .withMessageStartingWith("[RESERVED_IRI]");
    }

    @Test
    @DisplayName("a reserved IRI in the graph is rejected before a foreign subject")
    void reservedIriBeforeForeignSubject() {
      Graph graph = graph();
      graph.add(rdf.createIRI(EX + "other"), rdf.createIRI(EX + "p"), rdf.createIRI(RESERVED + "#x"));

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "doc"), graph))
          .withMessageStartingWith("[RESERVED_IRI]");
    }

    @Test
    @DisplayName("the reserved IRI as predicate or datatype, or with an empty fragment, is rejected too")
    void reservedIriInEveryPosition() {
      IRI doc = rdf.createIRI(EX + "doc");
      Graph asPredicate = graph();
      asPredicate.add(doc, rdf.createIRI(RESERVED), rdf.createLiteral("x"));
      Graph asDatatype = graph();
      asDatatype.add(doc, rdf.createIRI(EX + "p"), rdf.createLiteral("x", rdf.createIRI(RESERVED + "#")));

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(doc, asPredicate))
          .withMessageStartingWith("[RESERVED_IRI]");
      assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> generator.generateIri(doc, asDatatype))
          .withMessageStartingWith("[RESERVED_IRI]");
    }

    @Test
    @DisplayName("a term kind that is no RDF 1.1 IRI, blank node or literal")
    void unsupportedTermKind() {
      Graph graph = graph();
      RDFTerm unknownKind = () -> "unsupported";
      graph.add(rdf.createIRI(EX + "r"), rdf.createIRI(EX + "p"), unknownKind);

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "r"), graph))
          .withMessageStartingWith("[UNSUPPORTED_TERM]");
    }

    @Test
    @DisplayName("an IRI string that reads as a blank node label is no absolute IRI, in any position")
    void blankNodeLookingIri() {
      IRI doc = rdf.createIRI(EX + "doc");
      Graph asSubject = graph();
      asSubject.add(rdf.createIRI("_:b0"), rdf.createIRI(EX + "p"), rdf.createLiteral("x"));
      Graph asPredicate = graph();
      asPredicate.add(doc, rdf.createIRI("_:p"), rdf.createLiteral("x"));
      Graph asObject = graph();
      asObject.add(doc, rdf.createIRI(EX + "p"), rdf.createIRI("_:b0"));
      Graph asDatatype = graph();
      asDatatype.add(doc, rdf.createIRI(EX + "p"), rdf.createLiteral("x", rdf.createIRI("_:t")));

      Graph plain = graph();
      plain.add(doc, rdf.createIRI(EX + "p"), rdf.createLiteral("x"));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI("_:doc"), plain))
          .withMessageStartingWith("[UNSUPPORTED_TERM]");

      for (Graph graph : List.of(asSubject, asPredicate, asObject, asDatatype)) {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> generator.generateIri(doc, graph))
            .withMessageStartingWith("[UNSUPPORTED_TERM]");
      }
    }

    @Test
    @DisplayName("null base — which resource is meant is the caller's to say")
    void nullBase() {
      Graph graph = graph();
      graph.add(rdf.createIRI(EX + "doc"), rdf.createIRI(EX + "value"), rdf.createLiteral("1"));

      assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> generator.generateIri(null, graph));
    }

    @Test
    @DisplayName("a base carrying a fragment — a fragment names a part of a resource, not the resource")
    void baseWithFragment() {
      Graph graph = graph();
      graph.add(rdf.createIRI(EX + "doc#a"), rdf.createIRI(EX + "value"), rdf.createLiteral("1"));

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "doc#a"), graph))
          .withMessageStartingWith("[INVALID_BASE]")
          .withMessageContaining(EX + "doc#a");
    }

    @Test
    @DisplayName("a graph describing another resource than the base names")
    void graphOfAnotherResource() {
      Graph graph = graph();
      graph.add(rdf.createIRI(EX + "other"), rdf.createIRI(EX + "value"), rdf.createLiteral("1"));

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "doc"), graph))
          .withMessageStartingWith("[FOREIGN_SUBJECT]")
          .withMessageContaining(EX + "other");
    }

    @Test
    @DisplayName("a base IRI that is a prefix of a subject but not its base")
    void baseThatIsOnlyAStringPrefixOfTheSubject() {
      Graph graph = graph();
      graph.add(rdf.createIRI(EX + "doc2"), rdf.createIRI(EX + "value"), rdf.createLiteral("1"));

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "doc"), graph))
          .withMessageContaining(EX + "doc2");
    }

    @Test
    @DisplayName("a hash-namespace term is addressed under the base the caller names, not one guessed from it")
    void hashNamespaceTermUnderTheNamedBase() {
      // <ns#Person> has the base <ns>, so describing it means hashing under <ns>. The caller
      // has to say so; naming the term itself as the base is rejected, being a fragment IRI.
      IRI ns = rdf.createIRI(EX + "ns");
      Graph graph = graph();
      graph.add(rdf.createIRI(EX + "ns#Person"), rdf.createIRI(EX + "label"), rdf.createLiteral("Person"));

      assertThat(generator.generateIri(ns, graph).getIRIString()).startsWith(NI_PREFIX);
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "ns#Person"), graph));
    }

    @Test
    @DisplayName("two IRI subjects with different bases — which of the two would the identifier be for?")
    void twoIriSubjectsWithDifferentBases() {
      Graph graph = graph();
      graph.add(rdf.createIRI(EX + "one"), rdf.createIRI(EX + "value"), rdf.createLiteral("1"));
      graph.add(rdf.createIRI(EX + "two"), rdf.createIRI(EX + "value"), rdf.createLiteral("2"));

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "one"), graph))
          .withMessageContaining(EX + "one")
          .withMessageContaining(EX + "two");
    }

    @Test
    @DisplayName("a fragment IRI subject of another base")
    void fragmentSubjectOfAnotherBase() {
      Graph graph = graph();
      graph.add(rdf.createIRI(EX + "one"), rdf.createIRI(EX + "value"), rdf.createLiteral("1"));
      graph.add(rdf.createIRI(EX + "two#part"), rdf.createIRI(EX + "value"), rdf.createLiteral("2"));

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "one"), graph))
          .withMessageContaining(EX + "one")
          .withMessageContaining(EX + "two#part");
    }

    @Test
    @DisplayName("the base and fragments of that base as subjects are one resource and accepted")
    void baseAndItsFragmentsAreAccepted() {
      Graph graph = graph();
      graph.add(rdf.createIRI(EX + "doc"), rdf.createIRI(EX + "value"), rdf.createLiteral("1"));
      graph.add(rdf.createIRI(EX + "doc#a"), rdf.createIRI(EX + "value"), rdf.createLiteral("2"));
      graph.add(rdf.createIRI(EX + "doc#b"), rdf.createIRI(EX + "value"), rdf.createLiteral("3"));

      assertThat(generator.generateIri(rdf.createIRI(EX + "doc"), graph).getIRIString()).startsWith(NI_PREFIX);
    }

    @Test
    @DisplayName("fragments of one base without the base itself as subject are accepted")
    void onlyFragmentSubjectsAreAccepted() {
      Graph graph = graph();
      graph.add(rdf.createIRI(EX + "doc#a"), rdf.createIRI(EX + "value"), rdf.createLiteral("1"));
      graph.add(rdf.createIRI(EX + "doc#b"), rdf.createIRI(EX + "value"), rdf.createLiteral("2"));

      assertThat(generator.generateIri(rdf.createIRI(EX + "doc"), graph).getIRIString()).startsWith(NI_PREFIX);
    }

    @Test
    @DisplayName("a blank node component hanging off no subject of the base is still rejected")
    void freeStandingBlankNodeComponentNextToFragments() {
      Graph graph = graph();
      graph.add(rdf.createIRI(EX + "doc#a"), rdf.createIRI(EX + "value"), rdf.createLiteral("1"));
      graph.add(rdf.createBlankNode("orphan"), rdf.createIRI(EX + "value"), rdf.createLiteral("2"));

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "doc"), graph))
          .withMessageStartingWith("[UNREACHABLE]")
          .withMessageContaining("not reachable");
    }

    @Test
    @DisplayName("no IRI subject at all")
    void noIriSubject() {
      Graph graph = graph();
      graph.add(rdf.createBlankNode("only"), rdf.createIRI(EX + "value"), rdf.createLiteral("1"));

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "doc"), graph))
          .withMessageStartingWith("[NO_ROOT]")
          .withMessageContaining("no subject");
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
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> generator.generateIri(rdf.createIRI(EX + "r"), withOrphan))
          .withMessageStartingWith("[UNREACHABLE]")
          .withMessageContaining("not reachable");
    }
  }

  @Nested
  @DisplayName("a derivation failure is reported as ContentAddressingException, not a raw error")
  class DerivationFailure {

    @Test
    @DisplayName("a failure inside canonicalization surfaces with its cause")
    void failureInsideCanonicalizationKeepsItsCause() {
      IllegalStateException broken = new IllegalStateException("broken");
      RdfDatasetCanonicalizer failing = new RdfDatasetCanonicalizer() {
        @Override
        public Map<String, String> canonicalIdentifiers(Collection<Triple> triples) {
          throw broken;
        }
      };
      ContentAddressedIriGenerator failingGenerator = new ContentAddressedIriGeneratorSexpr(rdf,
          new ContentAddressableRdfSerializer(failing, rdf));

      assertThatExceptionOfType(ContentAddressingException.class)
          .isThrownBy(() -> failingGenerator.generateIri(rdf.createIRI(EX + "resource"), nestedGraph("1", "t", "e")))
          .withCause(broken);
    }

    @Test
    @DisplayName("a legal graph whose cost bound exceeds the limit surfaces as a resource-limit failure, not a generic one")
    void costBeyondTheLimit() {
      // K_7: seven blank nodes, every ordered pair linked by the same predicate, all hanging off
      // one IRI subject — a cost bound far above the 10 000 000 ni-rdf/1 §4.3 admits.
      Graph graph = graph();
      IRI subject = rdf.createIRI(EX + "symmetric");
      IRI predicate = rdf.createIRI(EX + "link");
      int n = 7;
      List<BlankNode> nodes = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        BlankNode node = rdf.createBlankNode("n" + i);
        nodes.add(node);
        graph.add(subject, predicate, node);
      }
      for (BlankNode from : nodes) {
        for (BlankNode to : nodes) {
          if (from != to) {
            graph.add(from, predicate, to);
          }
        }
      }

      assertThatExceptionOfType(CanonicalizationResourceLimitExceededException.class)
          .isThrownBy(() -> generator.generateIri(subject, graph))
          .withMessageStartingWith("[RESOURCE_LIMIT]");
    }
  }

  // Helpers

  private Graph graph() {
    return rdf.createGraph();
  }

  private IRI nestedGraphCid(String value) {
    return generator.generateIri(rdf.createIRI(EX + "resource"), nestedGraph(value, "table", "entry"));
  }

  private IRI cidOfSubject(IRI subject) {
    Graph graph = graph();
    graph.add(subject, rdf.createIRI(EX + "value"), rdf.createLiteral("v"));
    return generator.generateIri(rdf.createIRI(EX + "doc"), graph);
  }

  /** Two structurally different blank nodes under one subject, so their canonical order matters. */
  private Graph twoBlankNodesUnder(IRI subject) {
    Graph graph = graph();
    BlankNode first = rdf.createBlankNode("first");
    BlankNode second = rdf.createBlankNode("second");
    graph.add(subject, rdf.createIRI(EX + "has"), first);
    graph.add(subject, rdf.createIRI(EX + "has"), second);
    graph.add(first, rdf.createIRI(EX + "value"), rdf.createLiteral("1"));
    graph.add(second, rdf.createIRI(EX + "value"), rdf.createLiteral("2"));
    return graph;
  }

  /**
   * Two structurally different blank nodes under {@code base}, one of them holding a literal
   * typed with {@code <base#unit>}, so their canonical order could hang on that datatype.
   */
  private Graph twoBlankNodesWithAnOwnDatatypeUnder(String base) {
    Graph graph = graph();
    IRI subject = rdf.createIRI(base);
    BlankNode first = rdf.createBlankNode("first");
    BlankNode second = rdf.createBlankNode("second");
    graph.add(subject, rdf.createIRI(EX + "has"), first);
    graph.add(subject, rdf.createIRI(EX + "has"), second);
    graph.add(first, rdf.createIRI(EX + "value"), rdf.createLiteral("1", rdf.createIRI(base + "#unit")));
    graph.add(second, rdf.createIRI(EX + "value"),
        rdf.createLiteral("1", rdf.createIRI(VocabXsd.INTEGER.getIRIString())));
    return graph;
  }

  /**
   * Rewrites every occurrence of {@code from}, and of its fragment IRIs, to {@code to}, in every
   * position, a literal's datatype included.
   */
  private Graph renameBase(Graph source, String from, String to) {
    Graph renamed = graph();
    source.stream()
        .forEach(t -> renamed.add((BlankNodeOrIRI) rename(t.getSubject(), from, to),
            (IRI) rename(t.getPredicate(), from, to), rename(t.getObject(), from, to)));
    return renamed;
  }

  private RDFTerm rename(RDFTerm term, String from, String to) {
    if (term instanceof Literal literal && literal.getLanguageTag().isEmpty()) {
      return rdf.createLiteral(literal.getLexicalForm(), (IRI) rename(literal.getDatatype(), from, to));
    }
    if (term instanceof IRI iri) {
      String value = iri.getIRIString();
      if (value.equals(from)) {
        return rdf.createIRI(to);
      }
      if (value.startsWith(from + "#")) {
        return rdf.createIRI(to + value.substring(from.length()));
      }
    }
    return term;
  }

  private IRI cidOf(RDFTerm object) {
    Graph graph = graph();
    IRI resource = rdf.createIRI(EX + "resource");
    graph.add(resource, rdf.createIRI(EX + "value"), object);
    return generator.generateIri(resource, graph);
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
