// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.cbor;

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
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.codec.binary.Base32;
import org.apache.commons.lang3.ArrayUtils;
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
 * Derives a content-addressed {@code urn:} per IRI subject from a collection of triples.
 *
 * <p>Per subject the reachable sub-graph (following blank nodes) is canonicalized with
 * {@link RdfDatasetCanonicalizer}, its blank nodes are skolemized to deterministic
 * {@code urn:skolem:} IRIs, serialized into a sorted, length-prefixed S-expression form
 * and hashed with Blake2b-256; the Base32-encoded digest is the URN.</p>
 *
 * <p>The result therefore depends only on the content of the sub-graph, not on blank node
 * labels or triple order.</p>
 */
public class ContentAddressableRdfSerializer {

  private final RdfDatasetCanonicalizer canonicalizer;
  private final RDF rdf;

  /**
   * Creates a serializer.
   *
   * @param canonicalizer the URDNA2015 canonicalizer applied before hashing
   */
  public ContentAddressableRdfSerializer(RdfDatasetCanonicalizer canonicalizer) {
    this.canonicalizer = canonicalizer;
    this.rdf = new SimpleRdf();
  }

  /**
   * Groups triples by their IRI subject and collects all related BlankNode triples.
   *
   * @param triples the triples to group
   * @return map of IRI subjects to their associated triples (including BlankNode triples)
   */
  private Map<IRI, Collection<Triple>> groupTriplesByIriSubject(Collection<Triple> triples) {
    Map<IRI, Collection<Triple>> result = new HashMap<>();

    List<IRI> iriSubjects = triples.stream()
        .map(Triple::getSubject)
        .filter(s -> s instanceof IRI)
        .map(s -> (IRI) s)
        .distinct()
        .toList();

    for (IRI subject : iriSubjects) {
      Collection<Triple> resourceTriples = collectTriplesForResource(triples, subject);
      result.put(subject, resourceTriples);
    }

    return result;
  }

  /**
   * Collects all triples belonging to a resource, including transitive BlankNode triples.
   * Uses breadth-first traversal to follow BlankNode references.
   *
   * @param allTriples all available triples
   * @param subject the IRI subject to collect triples for
   * @return collection of triples belonging to this resource
   */
  private Collection<Triple> collectTriplesForResource(Collection<Triple> allTriples, IRI subject) {
    List<Triple> result = new ArrayList<>();
    Set<BlankNodeOrIRI> visited = new HashSet<>();
    Queue<BlankNodeOrIRI> toVisit = new LinkedList<>();

    toVisit.add(subject);

    while (!toVisit.isEmpty()) {
      BlankNodeOrIRI current = toVisit.poll();
      if (visited.contains(current))
        continue;
      visited.add(current);

      for (Triple t : allTriples) {
        if (t.getSubject().equals(current)) {
          result.add(t);
          if (t.getObject() instanceof BlankNode bn && !visited.contains(bn)) {
            toVisit.add(bn);
          }
        }
      }
    }
    return result;
  }

  /**
   * Serializes RDF triples and generates content-addressed URNs.
   * Groups triples by IRI subject and generates one URN per resource.
   *
   * @param triples the triples to serialize
   * @return result containing URNs mapped to their serialized content
   */
  public ContentAddressableResult serializeWithUrn(Collection<Triple> triples) {
    ContentAddressableResult result = new ContentAddressableResult();

    Map<IRI, Collection<Triple>> groupedBySubject = groupTriplesByIriSubject(triples);

    groupedBySubject.entrySet().stream().forEach(e -> {
      SingleContentAddressableResult sr = serializeWithUrnInternal(e.getValue());
      result.put(sr.urn(), sr.sexprBytes);
    });
    return result;
  }

  private SingleContentAddressableResult serializeWithUrnInternal(Collection<Triple> triples) {
    // 1. Kanonisiere RDF Dataset (URDNA2015 - ersetzt BlankNodes deterministisch)
    Collection<Triple> canonicalTriples = canonicalizer.canonicalize(triples);

    // 2. Skolemisiere BlankNodes zu deterministischen IRIs
    Map<BlankNode, IRI> mapping = buildCanonicalBlankNodeMapping(canonicalTriples);
    List<Triple> skolemizedTriples = canonicalTriples.stream()
        .map(t -> skolemizeTripleWithMapping(t, mapping))
        .toList();

    // 3. Serialisiere kanonisch
    byte[] canon = serializeFragmentGraph(skolemizedTriples);

    // 4. Hashen (Blake2b-256)
    byte[] hash = blake2b256(canon);
    Base32 base32 = new Base32();
    IRI iri = rdf.createIRI("urn:" + base32.encodeToString(hash).toLowerCase(Locale.ROOT));

    return new SingleContentAddressableResult(iri, canon);
  }

  private byte[] blake2b256(byte[] input) {
    Blake2bDigest digest = new Blake2bDigest(256); // 256-bit = 32 byte
    digest.update(input, 0, input.length);
    byte[] output = new byte[digest.getDigestSize()];
    digest.doFinal(output, 0);
    return output;
  }

  /** Skolemisiere ein Triple mit vorgegebenem Mapping */
  private Triple skolemizeTripleWithMapping(Triple t, Map<BlankNode, IRI> mapping) {
    BlankNodeOrIRI subj = t.getSubject();
    RDFTerm obj = t.getObject();
    if (subj instanceof BlankNode bn) {
      subj = mapping.get(bn);
    }
    if (obj instanceof BlankNode bn2) {
      obj = mapping.get(bn2);
    }
    return rdf.createTriple(subj, t.getPredicate(), obj);
  }

  /**
   * Creates a deterministic mapping from BlankNodes to Skolem IRIs.
   * Based on graph structure (predicates and objects), not on BlankNode IDs.
   *
   * @param triples the triples containing BlankNodes
   * @return mapping from BlankNodes to deterministic Skolem IRIs
   */
  private Map<BlankNode, IRI> buildCanonicalBlankNodeMapping(Collection<Triple> triples) {
    Map<BlankNode, List<String>> blankNodeSignatures = new HashMap<>();

    for (Triple t : triples) {
      if (t.getSubject() instanceof BlankNode bn) {
        blankNodeSignatures.computeIfAbsent(bn, _ -> new ArrayList<>())
            .add(t.getPredicate().getIRIString() + ":" + objectToString(t.getObject()));
      }
      if (t.getObject() instanceof BlankNode bn) {
        // ^ marks inverse direction
        blankNodeSignatures.computeIfAbsent(bn, _ -> new ArrayList<>()).add("^" + t.getPredicate().getIRIString());
      }
    }

    Map<BlankNode, IRI> mapping = new HashMap<>();
    for (Map.Entry<BlankNode, List<String>> entry : blankNodeSignatures.entrySet()) {
      List<String> signature = entry.getValue();
      signature.sort(String::compareTo);

      String signatureStr = String.join("|", signature);
      String hash = hashBlankNodeSignature(signatureStr);

      mapping.put(entry.getKey(), rdf.createIRI("urn:skolem:" + hash));
    }

    return mapping;
  }

  private String hashBlankNodeSignature(String signature) {
    Blake2bDigest digest = new Blake2bDigest(256);
    byte[] input = signature.getBytes(StandardCharsets.UTF_8);
    digest.update(input, 0, input.length);
    byte[] hash = new byte[digest.getDigestSize()];
    digest.doFinal(hash, 0);

    Base32 base32 = new Base32();
    return base32.encodeToString(hash).toLowerCase(Locale.ROOT).substring(0, 32);
  }

  /** Serialisiert kanonisch als S-Expression wie vorher (mit fs / s etc.) */
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

  private byte[] tripleToSexpr(Triple triple) {
    List<byte[]> elements = new ArrayList<>();
    if (isFsForm(triple)) {
      IRI subj = (IRI) triple.getSubject();
      String[] split = subj.getIRIString().split("#", 2);
      String fragment = split[1];
      elements.add(toNetstring("fs"));
      elements.add(toNetstring(fragment));
    } else {
      elements.add(toNetstring("s"));
    }
    elements.add(toNetstring(((IRI) triple.getPredicate()).getIRIString()));
    elements.add(toNetstring(objectToString(triple.getObject())));
    return wrapInParens(elements);
  }

  private boolean isFsForm(Triple t) {
    RDFTerm s = t.getSubject();
    if (s instanceof IRI iri) {
      return iri.getIRIString().contains("#");
    }
    return false;
  }

  private String objectToString(RDFTerm obj) {
    if (obj instanceof Literal lit) {
      return lit.getLexicalForm();
    } else if (obj instanceof IRI iri) {
      return iri.getIRIString();
    } else if (obj instanceof BlankNode bn) {
      // Sollte nicht passieren, weil wir skolemisiert haben
      return bn.uniqueReference();
    } else {
      return obj.toString();
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

  /** Ergebnis-Klasse: URN + S-Expression Bytes */
  record SingleContentAddressableResult(IRI urn, byte[] sexprBytes) {
  }

  /** The URNs derived from one call, each mapped to the bytes that were hashed for it. */
  public class ContentAddressableResult {

    private Map<IRI, Byte[]> sexprBytesByUrn;

    /** Creates an empty result. */
    public ContentAddressableResult() {
      this.sexprBytesByUrn = new HashMap<>();
    }

    /**
     * Records the serialized form a URN was derived from.
     *
     * @param urn the content-addressed URN
     * @param sExprBytes the serialized bytes that were hashed into {@code urn}
     */
    public void put(IRI urn, byte[] sExprBytes) {
      this.sexprBytesByUrn.put(urn, ArrayUtils.toObject(sExprBytes));
    }

    /**
     * Returns the serialized form a URN was derived from.
     *
     * @param iri the URN to look up
     * @return the serialized bytes, or {@code null} if this result holds no such URN
     */
    public byte[] get(IRI iri) {
      return Optional.ofNullable(this.sexprBytesByUrn.get(iri)).map(ArrayUtils::toPrimitive).orElse(null);
    }

    /**
     * Returns the URNs held by this result.
     *
     * @return a stream of the content-addressed URNs, in no particular order
     */
    public Stream<IRI> iris() {
      return this.sexprBytesByUrn.keySet().stream();
    }
  }
}
