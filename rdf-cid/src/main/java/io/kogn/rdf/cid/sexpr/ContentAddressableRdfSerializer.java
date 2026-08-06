// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.codec.binary.Base32;
import org.bouncycastle.crypto.digests.Blake2bDigest;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Derives a content-addressed {@code urn:cid:} per IRI subject from a collection of triples.
 *
 * <p>Per subject the reachable sub-graph (following blank nodes) is canonicalized with
 * {@link RdfDatasetCanonicalizer}, serialized into a sorted, length-prefixed S-expression
 * form — blank nodes under deterministic {@code urn:skolem:} names — and hashed with
 * Blake2b-256; the unpadded, lower-case Base32 digest is the URN.</p>
 *
 * <h2>What determines the identifier</h2>
 *
 * <p>Every term of every triple in the sub-graph goes into the hash <strong>in full</strong>,
 * tagged by its kind:</p>
 * <ul>
 *   <li>an IRI by its IRI string,</li>
 *   <li>a literal by its lexical form, its datatype IRI <em>and</em> its language tag,</li>
 *   <li>a blank node by its deterministic {@code urn:skolem:} name, which is derived from
 *       the node's position in the graph rather than from its label.</li>
 * </ul>
 *
 * <p>The kind tag is part of the serialized form, so an IRI object and a literal whose
 * lexical form happens to be that same string do not collide, and neither does a blank node
 * with an IRI that spells out its skolem name. Two literals differing only in datatype or
 * only in language tag do not collide either — the identifier honours {@link Literal}'s
 * three-component equality contract.</p>
 *
 * <p>The subject IRI is included as well: a graph is identified by its triples, and the
 * subject is part of a triple. The identifier is therefore independent of blank node labels
 * and of triple order, but <em>not</em> of the IRIs the data uses.</p>
 */
public class ContentAddressableRdfSerializer {

  private static final String URN_PREFIX = "urn:cid:";
  private static final String SKOLEM_PREFIX = "urn:skolem:";

  /** Term kind tags written into the serialized form so terms of different kinds cannot collide. */
  private static final String KIND_IRI = "I";
  private static final String KIND_LITERAL = "L";
  private static final String KIND_BLANK = "B";

  private final RdfDatasetCanonicalizer canonicalizer;
  private final RDF rdf;

  /**
   * Creates a serializer using {@link SimpleRdf} to create the resulting URN.
   *
   * @param canonicalizer the URDNA2015 canonicalizer applied before hashing
   */
  public ContentAddressableRdfSerializer(RdfDatasetCanonicalizer canonicalizer) {
    this(canonicalizer, new SimpleRdf());
  }

  /**
   * Creates a serializer.
   *
   * @param canonicalizer the URDNA2015 canonicalizer applied before hashing
   * @param rdf the term factory used to create the resulting URN
   */
  public ContentAddressableRdfSerializer(RdfDatasetCanonicalizer canonicalizer, RDF rdf) {
    this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer must not be null");
    this.rdf = Objects.requireNonNull(rdf, "rdf must not be null");
  }

  /**
   * Serializes RDF triples and generates a content-addressed URN.
   *
   * @param triples the triples to serialize; must hold exactly one IRI subject, plus any blank
   *        node triples reachable from it
   * @return the URN together with the serialized content it was derived from
   * @throws IllegalArgumentException if the triples hold other than exactly one IRI subject, or
   *         hold a triple not reachable from that subject, because such a triple would silently
   *         drop out of the identifier derived here
   */
  public SingleContentAddressableResult serializeWithUrn(Collection<Triple> triples) {
    Map<IRI, Collection<Triple>> groupedBySubject = groupTriplesByIriSubject(triples);
    Collection<Triple> subjectTriples = groupedBySubject.values().iterator().next();
    return serializeWithUrnInternal(subjectTriples);
  }

  /**
   * Groups triples by their IRI subject and collects all related BlankNode triples.
   *
   * @param triples the triples to group
   * @return map of IRI subjects to their associated triples (including BlankNode triples)
   * @throws IllegalArgumentException if the triples hold other than exactly one IRI subject, or
   *         a triple is reachable from no IRI subject
   */
  private Map<IRI, Collection<Triple>> groupTriplesByIriSubject(Collection<Triple> triples) {
    Map<BlankNodeOrIRI, List<Triple>> bySubject = triples.stream().collect(Collectors.groupingBy(Triple::getSubject));

    List<IRI> iriSubjects = triples.stream()
        .map(Triple::getSubject)
        .filter(IRI.class::isInstance)
        .map(IRI.class::cast)
        .distinct()
        .toList();

    if (iriSubjects.size() != 1) {
      throw new IllegalArgumentException(
          "Content addressing describes exactly one resource, so the triples must hold exactly "
              + "one IRI subject, but hold " + iriSubjects.size());
    }

    Map<IRI, Collection<Triple>> result = new HashMap<>();
    Set<Triple> reachable = new HashSet<>();
    for (IRI subject : iriSubjects) {
      Collection<Triple> resourceTriples = collectTriplesForResource(bySubject, subject);
      reachable.addAll(resourceTriples);
      result.put(subject, resourceTriples);
    }

    rejectUnreachable(triples, reachable);
    return result;
  }

  /**
   * Rejects a graph holding triples no IRI subject reaches — a free-standing blank node
   * component, for instance. Such triples are legal RDF but would contribute to no identifier,
   * so two different graphs would silently share one. Failing is honest; hashing them would
   * mean deciding which identifier they belong to.
   */
  private void rejectUnreachable(Collection<Triple> triples, Set<Triple> reachable) {
    List<Triple> unreachable = triples.stream().filter(t -> !reachable.contains(t)).toList();
    if (!unreachable.isEmpty()) {
      throw new IllegalArgumentException("Graph holds " + unreachable.size()
          + " triple(s) not reachable from any IRI subject, "
          + "which would silently drop out of the content-addressed identifier; first one: " + unreachable.getFirst());
    }
  }

  /**
   * Collects all triples belonging to a resource, including transitive BlankNode triples.
   * Uses breadth-first traversal over a subject index to follow BlankNode references.
   *
   * @param bySubject all available triples, indexed by their subject
   * @param subject the IRI subject to collect triples for
   * @return collection of triples belonging to this resource
   */
  private Collection<Triple> collectTriplesForResource(Map<BlankNodeOrIRI, List<Triple>> bySubject, IRI subject) {
    List<Triple> result = new ArrayList<>();
    Set<BlankNodeOrIRI> visited = new HashSet<>();
    Queue<BlankNodeOrIRI> toVisit = new LinkedList<>();

    toVisit.add(subject);

    while (!toVisit.isEmpty()) {
      BlankNodeOrIRI current = toVisit.poll();
      if (!visited.add(current))
        continue;

      for (Triple t : bySubject.getOrDefault(current, List.of())) {
        result.add(t);
        if (t.getObject() instanceof BlankNode bn && !visited.contains(bn)) {
          toVisit.add(bn);
        }
      }
    }
    return result;
  }

  private SingleContentAddressableResult serializeWithUrnInternal(Collection<Triple> triples) {
    // 1. Canonicalize the RDF dataset (URDNA2015 relabels blank nodes deterministically)
    Collection<Triple> canonicalTriples = canonicalizer.canonicalize(triples);

    // 2. Serialize canonically; blank nodes go in under their own kind tag with the
    // deterministic skolem name derived from their canonical label
    byte[] canon = serializeFragmentGraph(canonicalTriples);

    // 3. Hash (Blake2b-256)
    byte[] hash = blake2b256(canon);
    IRI iri = rdf.createIRI(URN_PREFIX + base32(hash));

    return new SingleContentAddressableResult(iri, canon);
  }

  private byte[] blake2b256(byte[] input) {
    Blake2bDigest digest = new Blake2bDigest(256); // 256-bit = 32 byte
    digest.update(input, 0, input.length);
    byte[] output = new byte[digest.getDigestSize()];
    digest.doFinal(output, 0);
    return output;
  }

  /**
   * Base32-encodes a digest, lower-cased and without the {@code =} padding: a padded value
   * would not be a syntactically valid URN namespace-specific string.
   */
  private String base32(byte[] hash) {
    return new Base32().encodeToString(hash).replace("=", "").toLowerCase(Locale.ROOT);
  }

  /** Serializes the graph canonically as a sorted S-expression of length-prefixed fields. */
  private byte[] serializeFragmentGraph(Collection<Triple> triples) {
    List<byte[]> forms = triples.stream()
        .map(this::tripleToSexpr)
        .sorted(Comparator.comparing(b -> new String(b, StandardCharsets.ISO_8859_1)))
        .collect(Collectors.toList());

    List<byte[]> parts = new ArrayList<>();
    parts.add(new byte[] {'('});
    parts.add(toNetstring("rdf"));
    parts.addAll(forms);
    parts.add(new byte[] {')'});
    return concat(parts.toArray(new byte[0][]));
  }

  /**
   * Serializes one triple as {@code (<subject> <predicate> <object>)}, each term written with
   * its kind tag and all its components, so no two distinct terms share a serialized form.
   */
  private byte[] tripleToSexpr(Triple triple) {
    List<byte[]> elements = new ArrayList<>();
    appendTerm(elements, triple.getSubject());
    appendTerm(elements, triple.getPredicate());
    appendTerm(elements, triple.getObject());
    return wrapInParens(elements);
  }

  /**
   * Appends a term as a kind tag followed by its components: an IRI as its IRI string, a
   * literal as lexical form, datatype IRI and language tag, a blank node as its deterministic
   * skolem name. The kind tag separates the three term kinds, so in particular a blank node
   * cannot collide with an IRI that spells out its skolem name.
   *
   * <p>Every component is a netstring, so the field count per kind is fixed and the
   * concatenation is unambiguous — no component can be mistaken for the next one, and no
   * separator can be forged from within a value.</p>
   */
  private void appendTerm(List<byte[]> elements, RDFTerm term) {
    switch (term) {
    case IRI iri -> {
      elements.add(toNetstring(KIND_IRI));
      elements.add(toNetstring(iri.getIRIString()));
    }
    case Literal lit -> {
      elements.add(toNetstring(KIND_LITERAL));
      elements.add(toNetstring(lit.getLexicalForm()));
      elements.add(toNetstring(lit.getDatatype() == null ? "" : lit.getDatatype().getIRIString()));
      // RDF 1.1 compares language tags case-insensitively ("Bank"@en == "Bank"@EN); lower-case
      // before hashing, or a re-import spelling the same tag differently misses its duplicate.
      elements.add(toNetstring(lit.getLanguageTag().map(tag -> tag.toLowerCase(Locale.ROOT)).orElse("")));
    }
    case BlankNode bn -> {
      // Every triple serialized here has passed the canonicalizer, so uniqueReference() is the
      // canonical, injective URDNA2015 label (_:c14n0, _:c14n1, ...): the skolem name built
      // from it is independent of the input labels, and two structurally identical siblings
      // stay two distinct names rather than collapsing into one.
      elements.add(toNetstring(KIND_BLANK));
      elements.add(toNetstring(SKOLEM_PREFIX + bn.uniqueReference()));
    }
    default -> throw new IllegalArgumentException("Unknown term type: " + term.getClass());
    }
  }

  private byte[] toNetstring(String s) {
    byte[] data = s.getBytes(StandardCharsets.UTF_8);
    byte[] len = Integer.toString(data.length).getBytes(StandardCharsets.UTF_8);
    return concat(len, new byte[] {':'}, data);
  }

  private byte[] wrapInParens(List<byte[]> parts) {
    List<byte[]> all = new ArrayList<>();
    all.add(new byte[] {'('});
    all.addAll(parts);
    all.add(new byte[] {')'});
    return concat(all.toArray(new byte[0][]));
  }

  private byte[] concat(byte[]... arrays) {
    int tot = Arrays.stream(arrays).mapToInt(a -> a.length).sum();
    byte[] res = new byte[tot];
    int pos = 0;
    for (byte[] a : arrays) {
      System.arraycopy(a, 0, res, pos, a.length);
      pos += a.length;
    }
    return res;
  }

  /**
   * One URN together with the S-expression bytes it was derived from.
   *
   * @param urn the content-addressed URN
   * @param sexprBytes the serialized bytes {@code urn} was derived from; defensively copied
   *     on construction and on every {@link #sexprBytes()} call, so neither the caller's
   *     original array nor a returned copy can change what this result reports having hashed
   */
  public record SingleContentAddressableResult(IRI urn, byte[] sexprBytes) {

    /** Defensively copies {@code sexprBytes} so this result owns an immutable snapshot of it. */
    public SingleContentAddressableResult {
      sexprBytes = sexprBytes.clone();
    }

    /**
     * Returns the serialized form {@link #urn()} was derived from.
     *
     * @return a copy of the serialized bytes
     */
    @Override
    public byte[] sexprBytes() {
      return sexprBytes.clone();
    }
  }
}
