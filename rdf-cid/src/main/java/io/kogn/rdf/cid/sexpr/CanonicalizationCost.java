// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.math.BigInteger;
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
 * The resource limit of ni-rdf/1 §4.3: a cost bound <em>E</em> on what RDFC-1.0 needs for a
 * graph, computed from the graph alone before RDFC-1.0 runs.
 *
 * <p>RDFC-1.0 tells blank nodes with equal first-degree hashes apart by trying every order of
 * their mutually indistinguishable neighbours. {@code E} adds up, per connected component
 * {@code C} of such <em>ambiguous</em> blank nodes, {@code W_C · |C| · (|C|² + T_C)}: the
 * number of orders to try, times a polynomial factor for the work on each. It is an upper
 * estimate argued from RDFC-1.0's structure and checked by measurement, not a proof.</p>
 */
final class CanonicalizationCost {

  private static final String HASH_ALGORITHM = "SHA-256";
  private static final String SELF = "_:a";
  private static final String OTHER = "_:z";
  private static final String OUTGOING = "o";
  private static final String INCOMING = "s";

  private CanonicalizationCost() {
  }

  /**
   * Returns the cost bound {@code E} of the given graph (ni-rdf/1 §4.3), exact as an integer.
   * The number of orders to try is capped once it alone exceeds {@code limit}, which cannot
   * change whether {@code E ≤ limit}, so the result is exact only up to that decision: it is
   * at most {@code limit} if and only if the exact value is.
   *
   * @param triples the graph {@code G'}: base already mapped out, language tags already
   *        lower-cased
   * @param limit the largest admitted cost
   * @return {@code E}, or a value above {@code limit} if {@code E} is above it
   */
  static BigInteger estimate(Collection<Triple> triples, long limit) {
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

    long cap = limit + 1;
    Map<String, Long> orders = new HashMap<>();
    for (String id : ambiguous) {
      orders.put(id, ordersToTry(id, mentions.get(id), ambiguous, hashes, cap));
    }

    Map<String, String> component = components(triples, ambiguous);
    Map<String, long[]> sizeAndTriples = new HashMap<>();
    Map<String, Long> componentOrders = new HashMap<>();
    ambiguous.forEach(id -> {
      sizeAndTriples.computeIfAbsent(component.get(id), key -> new long[2])[0]++;
      componentOrders.merge(component.get(id), orders.get(id), (a, b) -> saturatedProduct(a, b, cap));
    });
    for (Triple triple : triples) {
      String root = rootOf(triple.getSubject(), component);
      if (root == null) {
        root = rootOf(triple.getObject(), component);
      }
      if (root != null) {
        sizeAndTriples.get(root)[1]++;
      }
    }

    BigInteger cost = BigInteger.ZERO;
    for (Map.Entry<String, long[]> entry : sizeAndTriples.entrySet()) {
      BigInteger size = BigInteger.valueOf(entry.getValue()[0]);
      BigInteger touching = BigInteger.valueOf(entry.getValue()[1]);
      BigInteger work = size.multiply(size.multiply(size).add(touching));
      cost = cost.add(BigInteger.valueOf(componentOrders.get(entry.getKey())).multiply(work));
    }
    return cost;
  }

  /**
   * The number of orders RDFC-1.0 may have to try at the ambiguous blank node {@code id}: the
   * product of the factorials of the sizes of its groups of related ambiguous nodes (§4.3,
   * step 3), capped at {@code cap}.
   */
  private static long ordersToTry(String id, List<Triple> quads, Set<String> ambiguous, Map<String, String> hashes,
      long cap) {
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
    long orders = 1;
    for (Set<String> group : groups.values()) {
      for (int factor = 2; factor <= group.size() && orders < cap; factor++) {
        orders = saturatedProduct(orders, factor, cap);
      }
    }
    return orders;
  }

  private static void addRelated(Map<List<String>, Set<String>> groups, String position, String predicate,
      String related, String id, Set<String> ambiguous, Map<String, String> hashes) {
    if (related.equals(id) || !ambiguous.contains(related)) {
      return;
    }
    groups.computeIfAbsent(List.of(position, predicate, hashes.get(related)), key -> new HashSet<>()).add(related);
  }

  /** {@code a · b}, or {@code cap} if that is more; both factors are at most {@code cap}. */
  private static long saturatedProduct(long a, long b, long cap) {
    return a > cap / b ? cap : Math.min(a * b, cap);
  }

  /**
   * The connected components of the subgraph the ambiguous blank nodes induce: two of them are
   * connected if some triple has one as subject and the other as object.
   *
   * @return each ambiguous blank node mapped to the representative of its component
   */
  private static Map<String, String> components(Collection<Triple> triples, Set<String> ambiguous) {
    Map<String, String> parent = new HashMap<>();
    ambiguous.forEach(id -> parent.put(id, id));
    for (Triple triple : triples) {
      if (triple.getSubject() instanceof BlankNode subject && triple.getObject() instanceof BlankNode object) {
        String from = Terms.blankNodeId(subject);
        String to = Terms.blankNodeId(object);
        if (ambiguous.contains(from) && ambiguous.contains(to)) {
          parent.put(representative(parent, from), representative(parent, to));
        }
      }
    }
    Map<String, String> component = new HashMap<>();
    ambiguous.forEach(id -> component.put(id, representative(parent, id)));
    return component;
  }

  private static String representative(Map<String, String> parent, String id) {
    String current = id;
    while (!parent.get(current).equals(current)) {
      parent.put(current, parent.get(parent.get(current)));
      current = parent.get(current);
    }
    return current;
  }

  /** The component of an ambiguous blank node, {@code null} for any other term. */
  private static String rootOf(RDFTerm term, Map<String, String> component) {
    return term instanceof BlankNode blankNode ? component.get(Terms.blankNodeId(blankNode)) : null;
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
