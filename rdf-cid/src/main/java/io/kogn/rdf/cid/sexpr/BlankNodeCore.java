// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.apicatalog.rdf.nquads.NQuadsWriter;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.Triple;

/**
 * The resource limit of ni-rdf/1 §4.3: the size of the <em>core</em>, the blank nodes RDFC-1.0
 * can only tell apart by trying orders of several mutually indistinguishable neighbours.
 *
 * <p>The criterion depends on the graph alone, so it is computed here before RDFC-1.0 runs
 * rather than read off a time or permutation budget of the canonicalizer.</p>
 */
final class BlankNodeCore {

  private static final String HASH_ALGORITHM = "SHA-256";
  private static final String SELF = "_:a";
  private static final String OTHER = "_:z";
  private static final String OUTGOING = "o";
  private static final String INCOMING = "s";
  private static final int GROUP_THAT_NEEDS_PERMUTING = 2;

  private BlankNodeCore() {
  }

  /**
   * Returns the number of blank nodes in the core of the given graph (ni-rdf/1 §4.3, steps
   * 1–4).
   *
   * @param triples the graph {@code G'}: base already mapped out, language tags already
   *        lower-cased
   * @return the size of the core, {@code 0} for a graph without symmetric blank node structure
   */
  static int size(Collection<Triple> triples) {
    Map<String, List<Triple>> mentions = mentionsByBlankNode(triples);
    Map<String, String> hashes = new HashMap<>();
    mentions.forEach((id, quads) -> hashes.put(id, hashFirstDegreeQuads(id, quads)));

    Map<String, Long> sharing = hashes.values()
        .stream()
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    Set<String> ambiguous = hashes.keySet()
        .stream()
        .filter(id -> sharing.get(hashes.get(id)) > 1)
        .collect(Collectors.toSet());

    return (int) ambiguous.stream()
        .filter(id -> hasGroupOfSeveralNodes(id, mentions.get(id), ambiguous, hashes))
        .count();
  }

  /**
   * Whether the ambiguous blank node {@code id} has a group of related ambiguous nodes with two
   * or more distinct members (§4.3, steps 3 and 4).
   */
  private static boolean hasGroupOfSeveralNodes(String id, List<Triple> quads, Set<String> ambiguous,
      Map<String, String> hashes) {
    Map<List<String>, Set<String>> groups = new HashMap<>();
    for (Triple triple : quads) {
      if (!(triple.getSubject() instanceof BlankNode blankSubject)
          || !(triple.getObject() instanceof BlankNode blankObject)) {
        continue;
      }
      String subject = Terms.blankNodeId(blankSubject);
      String object = Terms.blankNodeId(blankObject);
      String predicate = triple.getPredicate().getIRIString();
      if (subject.equals(id)) {
        addRelated(groups, OUTGOING, predicate, object, id, ambiguous, hashes);
      }
      if (object.equals(id)) {
        addRelated(groups, INCOMING, predicate, subject, id, ambiguous, hashes);
      }
    }
    return groups.values().stream().anyMatch(group -> group.size() >= GROUP_THAT_NEEDS_PERMUTING);
  }

  private static void addRelated(Map<List<String>, Set<String>> groups, String position, String predicate,
      String related, String id, Set<String> ambiguous, Map<String, String> hashes) {
    if (related.equals(id) || !ambiguous.contains(related)) {
      return;
    }
    groups.computeIfAbsent(List.of(position, predicate, hashes.get(related)), key -> new HashSet<>()).add(related);
  }

  /** Every blank node of the graph, with the triples that mention it. */
  private static Map<String, List<Triple>> mentionsByBlankNode(Collection<Triple> triples) {
    Map<String, List<Triple>> mentions = new HashMap<>();
    for (Triple triple : triples) {
      String subject = null;
      if (triple.getSubject() instanceof BlankNode blankSubject) {
        subject = Terms.blankNodeId(blankSubject);
        mentions.computeIfAbsent(subject, key -> new ArrayList<>()).add(triple);
      }
      if (triple.getObject() instanceof BlankNode blankObject) {
        String object = Terms.blankNodeId(blankObject);
        if (!object.equals(subject)) {
          mentions.computeIfAbsent(object, key -> new ArrayList<>()).add(triple);
        }
      }
    }
    return mentions;
  }

  /**
   * Hash First Degree Quads (RDFC-1.0 §4.6.3) with SHA-256, every triple in the default graph:
   * each quad mentioning {@code id} is serialized as canonical N-Quads with {@code id} written
   * as {@code _:a} and every other blank node as {@code _:z}; the lines are sorted in Unicode
   * code point order (the unsigned order of their UTF-8 bytes), concatenated and hashed.
   *
   * @return the hash in lower-case hexadecimal
   */
  static String hashFirstDegreeQuads(String id, Collection<Triple> quads) {
    List<byte[]> lines = quads.stream()
        .map(triple -> nquad(triple, id).getBytes(StandardCharsets.UTF_8))
        .sorted(Arrays::compareUnsigned)
        .toList();
    MessageDigest digest = sha256();
    lines.forEach(digest::update);
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String nquad(Triple triple, String id) {
    String subject = relabelled(triple.getSubject(), id);
    String predicate = triple.getPredicate().getIRIString();
    RDFTerm object = triple.getObject();
    if (object instanceof Literal literal) {
      String language = literal.getLanguageTag().orElse(null);
      return NQuadsWriter.nquad(subject, predicate, literal.getLexicalForm(), Terms.datatypeOf(literal), language, null,
          null);
    }
    return NQuadsWriter.nquad(subject, predicate, relabelled(object, id), null, null, null, null);
  }

  private static String relabelled(RDFTerm term, String id) {
    if (term instanceof BlankNode blankNode) {
      return Terms.blankNodeId(blankNode).equals(id) ? SELF : OTHER;
    }
    return Terms.resource(term);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance(HASH_ALGORITHM);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is a MessageDigest algorithm every Java platform must provide.
      throw new IllegalStateException(HASH_ALGORITHM + " unavailable", e);
    }
  }
}
