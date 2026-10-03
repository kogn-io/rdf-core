// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.apicatalog.rdf.canon.RdfCanon;

import io.kogn.rdf.cid.CanonicalizationResourceLimitExceededException;
import io.kogn.rdf.cid.ContentAddressingException;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.Triple;

/**
 * Labels the blank nodes of a graph with RDF Dataset Canonicalization (RDFC-1.0, W3C
 * Recommendation), as ni-rdf/1 §4.3 and §4.4 prescribe.
 *
 * <p>Before RDFC-1.0 runs, the graph is checked against the resource limit of ni-rdf/1 §4.3:
 * a graph whose <em>core</em> — the blank nodes RDFC-1.0 can only tell apart by trying every
 * order of several mutually indistinguishable neighbours — holds more than
 * {@value #MAX_CORE_SIZE} blank nodes is rejected without being canonicalized. The criterion
 * depends on the graph alone, so every conforming implementation accepts and rejects the same
 * graphs; RDFC-1.0 itself then runs without any time or permutation limit. The implementation
 * is {@code com.apicatalog:titanium-rdfc}, with SHA-256 as hash algorithm and every triple in
 * the default graph.</p>
 *
 * @see <a href="https://www.w3.org/TR/rdf-canon/">RDF Dataset Canonicalization (RDFC-1.0)</a>
 */
public class RdfDatasetCanonicalizer {

  /** The largest core (ni-rdf/1 §4.3) a graph may have and still be canonicalized. */
  public static final int MAX_CORE_SIZE = 6;

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
   * @throws CanonicalizationResourceLimitExceededException if the core of the graph holds more
   *         than {@value #MAX_CORE_SIZE} blank nodes; the message starts with
   *         {@code [RESOURCE_LIMIT]}
   * @throws ContentAddressingException if canonicalization otherwise fails; the underlying
   *         failure is kept as cause
   */
  public Map<String, String> canonicalIdentifiers(Collection<Triple> triples) {
    int core = BlankNodeCore.size(triples);
    if (core > MAX_CORE_SIZE) {
      throw new CanonicalizationResourceLimitExceededException(
          "[RESOURCE_LIMIT] The graph's core holds " + core + " blank nodes, more than the " + MAX_CORE_SIZE
              + " ni-rdf/1 admits: canonicalizing it would mean trying every order of too many "
              + "mutually indistinguishable blank nodes",
          null);
    }
    try {
      return canonicalize(triples);
    } catch (RuntimeException e) {
      throw new ContentAddressingException("RDFC-1.0 canonicalization failed", e);
    }
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
