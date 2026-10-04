// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.apicatalog.rdf.canon.RdfCanon;

import io.kogn.rdf.cid.CanonicalizationResourceLimitExceededException;
import io.kogn.rdf.cid.ContentAddressingException;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.Triple;

/**
 * Labels the blank nodes of a graph with RDF Dataset Canonicalization (RDFC-1.0, W3C
 * Recommendation), as ni-rdf/1 §4.3 and §4.4 prescribe.
 *
 * <p>Before RDFC-1.0 runs, the graph is checked against the resource limit of ni-rdf/1 §4.3:
 * a graph whose cost bound — an estimate of the work RDFC-1.0 needs to tell blank nodes apart
 * by trying every order of their mutually indistinguishable neighbours — exceeds
 * {@value #MAX_COST} is rejected without being canonicalized. The criterion
 * depends on the graph alone, so every conforming implementation accepts and rejects the same
 * graphs; RDFC-1.0 itself then runs without any time or permutation limit. The implementation
 * is {@code com.apicatalog:titanium-rdfc}, with SHA-256 as hash algorithm and every triple in
 * the default graph.</p>
 *
 * @see <a href="https://www.w3.org/TR/rdf-canon/">RDF Dataset Canonicalization (RDFC-1.0)</a>
 */
public class RdfDatasetCanonicalizer {

  /** The largest cost bound (ni-rdf/1 §4.3) a graph may have and still be canonicalized. */
  public static final long MAX_COST = 10_000_000L;

  private static final int UPPER_BMP_START = 0xE000;

  private static final String HASH_ALGORITHM = "SHA-256";

  /** Creates a canonicalizer. */
  public RdfDatasetCanonicalizer() {
    // stateless; every call canonicalizes from scratch
  }

  /**
   * Returns the canonical issued identifier of every blank node in the given graph.
   *
   * <p>Blank nodes are told apart by {@link io.kogn.rdf.terms.BlankNode#uniqueReference()}. The
   * graph is taken as it is: mapping the base IRI out and lower-casing language tags (ni-rdf/1
   * §4.1, §4.2) is the caller's job, because both change the labels issued here.</p>
   *
   * @param triples the graph to canonicalize
   * @return each blank node's {@code uniqueReference()} mapped to its canonical issued
   *         identifier — {@code c14n0}, {@code c14n1}, … — without the {@code _:} prefix; empty
   *         for a graph without blank nodes
   * @throws CanonicalizationResourceLimitExceededException if the cost bound of the graph exceeds
   *         {@value #MAX_COST}; the message starts with
   *         {@code [RESOURCE_LIMIT]}
   * @throws ContentAddressingException if the graph has blank nodes and its terms mix a code point
   *         from U+10000 up with one from U+E000 to U+FFFF — titanium-rdfc would label them out of
   *         code point order (titanium-rdf-canon#65), so the name cannot yet be derived
   *         conformantly; or if canonicalization otherwise fails; the underlying
   *         failure is kept as cause
   */
  public Map<String, String> canonicalIdentifiers(Collection<Triple> triples) {
    BigInteger cost = CanonicalizationCost.estimate(triples, MAX_COST);
    if (cost.compareTo(BigInteger.valueOf(MAX_COST)) > 0) {
      throw new CanonicalizationResourceLimitExceededException("[RESOURCE_LIMIT] The graph's cost bound exceeds "
          + MAX_COST + ", the most ni-rdf/1 admits: canonicalizing it would mean trying too many orders of "
          + "mutually indistinguishable blank nodes", null);
    }
    rejectWhereTitanium65Bites(triples);
    try {
      return canonicalize(triples);
    } catch (RuntimeException e) {
      throw new ContentAddressingException("RDFC-1.0 canonicalization failed", e);
    }
  }

  /**
   * TEMPORARY, remove together with the titanium-rdfc upgrade: titanium-rdfc 3.0.0 sorts by UTF-16
   * code unit where RDFC-1.0 requires code point order (filip26/titanium-rdf-canon#65). The two
   * orders differ only when a supplementary code point (a surrogate pair) meets one in
   * U+E000..U+FFFF, and only blank node labels depend on the sort. Rather than mint a
   * non-conforming name, such a graph is rejected.
   */
  private static void rejectWhereTitanium65Bites(Collection<Triple> triples) {
    boolean hasBlankNode = false;
    boolean supplementary = false;
    boolean upperBmp = false;
    for (Triple triple : triples) {
      hasBlankNode |= triple.getSubject() instanceof BlankNode || triple.getObject() instanceof BlankNode;
      for (String text : textsOf(triple)) {
        for (int i = 0; i < text.length(); i += Character.charCount(text.codePointAt(i))) {
          int codePoint = text.codePointAt(i);
          supplementary |= codePoint >= Character.MIN_SUPPLEMENTARY_CODE_POINT;
          upperBmp |= codePoint >= UPPER_BMP_START && codePoint <= Character.MAX_VALUE;
        }
      }
    }
    if (hasBlankNode && supplementary && upperBmp) {
      throw new ContentAddressingException("The graph has blank nodes and mixes code points from U+10000 up with "
          + "code points from U+E000 to U+FFFF; the pinned titanium-rdfc sorts them out of code point order "
          + "(titanium-rdf-canon#65), so the name cannot yet be derived conformantly", null);
    }
  }

  private static List<String> textsOf(Triple triple) {
    List<String> texts = new ArrayList<>();
    if (triple.getSubject() instanceof IRI subject) {
      texts.add(subject.getIRIString());
    }
    texts.add(triple.getPredicate().getIRIString());
    switch (triple.getObject()) {
    case Literal literal -> {
      texts.add(literal.getLexicalForm());
      texts.add(literal.getDatatype().getIRIString());
      literal.getLanguageTag().ifPresent(texts::add);
    }
    case IRI iri -> texts.add(iri.getIRIString());
    default -> {
    }
    }
    return texts;
  }

  private Map<String, String> canonicalize(Collection<Triple> triples) {
    RdfCanon canon = RdfCanon.create(HASH_ALGORITHM);
    for (Triple triple : triples) {
      String subject = Terms.resource(triple.getSubject());
      String predicate = triple.getPredicate().getIRIString();
      switch (triple.getObject()) {
      case Literal literal -> canon.quad(subject, predicate, literal.getLexicalForm(), Terms.datatypeOf(literal),
          literal.getLanguageTag().orElse(null), null, null);
      case IRI iri -> canon.quad(subject, predicate, iri.getIRIString(), null, null, null, null);
      default -> canon.quad(subject, predicate, Terms.resource(triple.getObject()), null, null, null, null);
      }
    }
    // provide(...) runs the canonicalization; only the issued identifiers are of interest here.
    canon.provide(line -> {
    });

    Map<String, String> identifiers = new HashMap<>();
    canon.mapping().forEach((input, canonical) -> identifiers.put(withoutPrefix(input), withoutPrefix(canonical)));
    return identifiers;
  }

  private static String withoutPrefix(String blankNodeId) {
    return blankNodeId.substring(Terms.BLANK_NODE_PREFIX.length());
  }
}
