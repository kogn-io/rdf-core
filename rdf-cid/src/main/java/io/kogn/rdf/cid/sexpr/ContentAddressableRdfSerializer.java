// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Derives a content-addressed {@code urn:cid:} for the one resource a collection of triples
 * describes.
 *
 * <p>The resource is named by a single <em>base IRI</em>: an IRI without a fragment, passed in
 * by the caller rather than inferred from the triples. Every IRI subject of the triples is
 * either that base or a fragment IRI of it ({@code <base#part>}): an IRI whose string before the
 * first {@code #} equals the base, compared as a plain string with no further normalization. Per RFC 3986 the fragment starts at the first {@code #}; an IRI
 * holding further {@code #} characters is not rejected, everything after the first one is its
 * fragment. The triples of those subjects, together with the blank node triples reachable from
 * them, are the sub-graph the identifier is derived from.</p>
 *
 * <p>The sub-graph is canonicalized with
 * {@link RdfDatasetCanonicalizer}, serialized into a sorted, length-prefixed S-expression
 * form — blank nodes under deterministic {@code urn:skolem:} names — and hashed with
 * SHA3-256; the unpadded, lower-case Base32 digest is the URN.</p>
 *
 * <h2>What determines the identifier</h2>
 *
 * <p>Every term of every triple in the sub-graph goes into the hash <strong>in full</strong>,
 * tagged by its kind:</p>
 * <ul>
 *   <li>the base IRI by a fixed self placeholder that carries no IRI string at all,</li>
 *   <li>a fragment IRI of the base by its fragment alone (the part after the first {@code #},
 *       possibly empty),</li>
 *   <li>any other IRI by its IRI string,</li>
 *   <li>a literal by its lexical form, its datatype IRI <em>and</em> its language tag — the
 *       datatype IRI written like any other IRI term, so a datatype that is the base or one of
 *       its fragments goes in as the placeholder or as its fragment as well,</li>
 *   <li>a blank node by its deterministic {@code urn:skolem:} name, which is derived from
 *       the node's position in the graph rather than from its label.</li>
 * </ul>
 *
 * <p>The kind tag is part of the serialized form, so an IRI object and a literal whose
 * lexical form happens to be that same string do not collide, and neither does a blank node
 * with an IRI that spells out its skolem name. Two literals differing only in datatype or
 * only in language tag do not collide either — the identifier honours {@link Literal}'s
 * three-component equality contract. The self placeholder and fragments have kind tags of their
 * own, so no IRI and no literal can spell them out, and {@code <base>} never collides with
 * {@code <base#>}.</p>
 *
 * <h2>Why the base IRI stays out of the digest</h2>
 *
 * <p>A resource that is to carry its own identifier cannot have that identifier in the content
 * it is derived from: the identifier only exists once the content is hashed. The base IRI is
 * therefore replaced by the placeholder wherever it occurs — subject, predicate, object or a
 * literal's datatype, so a self-reference does not pull the name back in either — and its
 * fragment IRIs by their
 * fragments. A resource can be described under any provisional IRI, hashed, and then renamed to
 * {@code urn:cid:...} (with its parts becoming {@code urn:cid:...#part}); hashing the renamed
 * graph yields the same identifier again, so a reader can verify it. The approach follows the
 * fragment molecules of <a href="https://openengiadina.codeberg.page/rdf-cbor/">RDF/CBOR</a>,
 * which replaces the base subject by a placeholder in the same way.</p>
 *
 * <p>The identifier is therefore independent of blank node labels, of triple order and of the
 * base IRI the resource is described under, but <em>not</em> of any other IRI the data uses.
 * Two graphs describing the same thing under different base IRIs are the same content.</p>
 */
public class ContentAddressableRdfSerializer {

  private static final String URN_PREFIX = "urn:cid:";
  private static final String SKOLEM_PREFIX = "urn:skolem:";

  /** Term kind tags written into the serialized form so terms of different kinds cannot collide. */
  private static final String KIND_IRI = "I";
  private static final String KIND_LITERAL = "L";
  private static final String KIND_BLANK = "B";
  private static final String KIND_SELF = "S";
  private static final String KIND_FRAGMENT = "F";

  private static final char FRAGMENT_SEPARATOR = '#';

  /**
   * IRI namespace the base IRI and its fragments are mapped into before canonicalization, so the
   * order URDNA2015 hands out blank node labels in cannot depend on the base IRI either. The
   * base maps to {@code <prefix>s}, a fragment to {@code <prefix>s#fragment}; a foreign IRI that
   * happens to start with the prefix is escaped to {@code <prefix>e:rest}, which keeps the
   * mapping injective. Changing this value can change identifiers, like any other part of the
   * derivation.
   */
  static final String CANONICALIZATION_SELF_PREFIX = "urn:x-cid-self:";
  private static final String CANONICAL_SELF = CANONICALIZATION_SELF_PREFIX + "s";
  private static final String CANONICAL_FRAGMENT_PREFIX = CANONICAL_SELF + FRAGMENT_SEPARATOR;
  private static final String CANONICAL_ESCAPE_PREFIX = CANONICALIZATION_SELF_PREFIX + "e:";

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
   * @param base the base IRI of the resource the triples describe; must carry no fragment
   * @param triples the triples to serialize; every IRI subject must be {@code base} itself or a
   *        fragment IRI of it, and every other triple must be a blank node triple reachable from
   *        one of those subjects
   * @return the URN together with the serialized content it was derived from
   * @throws IllegalArgumentException if {@code base} is null or carries a fragment, if an IRI
   *         subject is neither {@code base} nor a fragment IRI of it, if no subject is, or if a
   *         triple is reachable from none of them, because such a triple would silently drop out
   *         of the identifier derived here
   */
  public ContentAddressableResult serializeWithUrn(IRI base, Collection<Triple> triples) {
    String baseIri = validBase(base);
    rejectForeignSubjects(triples, baseIri);
    return serializeWithUrnInternal(triplesOfTheResource(triples, baseIri), baseIri);
  }

  /**
   * Returns the IRI string of a base IRI.
   *
   * @throws IllegalArgumentException if the base is null or carries a fragment
   */
  private static String validBase(IRI base) {
    if (base == null) {
      throw new IllegalArgumentException("Base IRI cannot be null");
    }
    String value = base.getIRIString();
    if (value.indexOf(FRAGMENT_SEPARATOR) >= 0) {
      throw new IllegalArgumentException(
          "A base IRI names the resource as a whole and carries no #fragment, but got <" + value + ">");
    }
    return value;
  }

  /**
   * Rejects IRI subjects that are neither the base nor a fragment IRI of it: their triples
   * describe another resource, and hashing them under this base would mix two resources into
   * one identifier.
   *
   * @throws IllegalArgumentException if such a subject exists
   */
  private void rejectForeignSubjects(Collection<Triple> triples, String base) {
    List<String> foreign = triples.stream()
        .map(Triple::getSubject)
        .filter(IRI.class::isInstance)
        .map(subject -> ((IRI) subject).getIRIString())
        .filter(subject -> !baseOf(subject).equals(base))
        .distinct()
        .sorted()
        .toList();

    if (!foreign.isEmpty()) {
      throw new IllegalArgumentException("Content addressing describes exactly one resource, so every IRI subject "
          + "must be the base <" + base + "> or a fragment IRI of it, but " + foreign.size() + " are not: " + foreign);
    }
  }

  /** The IRI string before the first {@code #}; per RFC 3986 the fragment starts there. */
  private static String baseOf(String iri) {
    int separator = iri.indexOf(FRAGMENT_SEPARATOR);
    return separator < 0 ? iri : iri.substring(0, separator);
  }

  /**
   * Returns the triples of all IRI subjects, including all transitively reachable BlankNode
   * triples. The caller has already checked that every IRI subject is the base or a fragment
   * IRI of it.
   *
   * @param triples the triples to validate and collect from
   * @param base the base IRI, named in the message when no subject is the base or a fragment of it
   * @return the resource's triples (including BlankNode triples)
   * @throws IllegalArgumentException if there is no IRI subject, or a triple is reachable from
   *         no IRI subject
   */
  private Collection<Triple> triplesOfTheResource(Collection<Triple> triples, String base) {
    Map<BlankNodeOrIRI, List<Triple>> bySubject = triples.stream().collect(Collectors.groupingBy(Triple::getSubject));

    List<BlankNodeOrIRI> iriSubjects = triples.stream()
        .map(Triple::getSubject)
        .filter(IRI.class::isInstance)
        .distinct()
        .toList();
    if (iriSubjects.isEmpty()) {
      throw new IllegalArgumentException(
          "Graph holds no subject that is the base <" + base + "> or a fragment IRI of it");
    }

    Collection<Triple> resourceTriples = collectTriplesForResource(bySubject, iriSubjects);
    rejectUnreachable(triples, new HashSet<>(resourceTriples));
    return resourceTriples;
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
   * @param roots the IRI subjects to collect triples for
   * @return collection of triples belonging to this resource
   */
  private Collection<Triple> collectTriplesForResource(Map<BlankNodeOrIRI, List<Triple>> bySubject,
      List<BlankNodeOrIRI> roots) {
    List<Triple> result = new ArrayList<>();
    Set<BlankNodeOrIRI> visited = new HashSet<>();
    Queue<BlankNodeOrIRI> toVisit = new LinkedList<>(roots);

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

  private ContentAddressableResult serializeWithUrnInternal(Collection<Triple> triples, String base) {
    // 1. Map the base IRI and its fragments out of the way, then canonicalize the RDF dataset
    // (URDNA2015 relabels blank nodes deterministically, by an order the IRIs around a blank
    // node feed into, so the base must already be gone at this point)
    Collection<Triple> canonicalTriples = canonicalizer
        .canonicalize(triples.stream().map(t -> withoutBase(t, base)).toList());

    // 2. Serialize canonically; blank nodes go in under their own kind tag with the
    // deterministic skolem name derived from their canonical label
    byte[] canon = serializeFragmentGraph(canonicalTriples);

    // 3. Hash (SHA3-256)
    byte[] hash = sha3256(canon);
    IRI iri = rdf.createIRI(URN_PREFIX + base32(hash));

    return new ContentAddressableResult(iri, canon);
  }

  private byte[] sha3256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA3-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      // SHA3-256 is a JDK-guaranteed MessageDigest algorithm since Java 9.
      throw new IllegalStateException("SHA3-256 unavailable", e);
    }
  }

  /**
   * Base32-encodes a digest, lower-cased and without the {@code =} padding: a padded value
   * would not be a syntactically valid URN namespace-specific string.
   */
  private String base32(byte[] hash) {
    return new Base32().encodeToString(hash).replace("=", "").toLowerCase(Locale.ROOT);
  }

  /**
   * Replaces the base IRI and its fragment IRIs in every position of a triple, a literal's
   * datatype included, see {@link #withoutBase(IRI, String)}.
   */
  private Triple withoutBase(Triple triple, String base) {
    BlankNodeOrIRI subject = triple.getSubject() instanceof IRI iri ? withoutBase(iri, base) : triple.getSubject();
    RDFTerm object = switch (triple.getObject()) {
    case IRI iri -> withoutBase(iri, base);
    case Literal literal -> withoutBase(literal, base);
    default -> triple.getObject();
    };
    return rdf.createTriple(subject, withoutBase(triple.getPredicate(), base), object);
  }

  /**
   * Maps a literal's datatype IRI like any other IRI. A language-tagged literal keeps its
   * datatype: RDF 1.1 fixes it to {@code rdf:langString}, and the tag carries the distinction.
   */
  private Literal withoutBase(Literal literal, String base) {
    if (literal.getLanguageTag().isPresent() || literal.getDatatype() == null) {
      return literal;
    }
    IRI datatype = withoutBase(literal.getDatatype(), base);
    return datatype == literal.getDatatype() ? literal : rdf.createLiteral(literal.getLexicalForm(), datatype);
  }

  /**
   * Maps an IRI into the canonicalization namespace: the base to {@link #CANONICAL_SELF}, a
   * fragment IRI of the base to {@link #CANONICAL_FRAGMENT_PREFIX} plus its fragment, a foreign
   * IRI that already lies in the namespace to its escaped form, any other IRI to itself.
   */
  private IRI withoutBase(IRI iri, String base) {
    String value = iri.getIRIString();
    if (value.equals(base)) {
      return rdf.createIRI(CANONICAL_SELF);
    }
    if (value.length() > base.length() && value.startsWith(base) && value.charAt(base.length()) == FRAGMENT_SEPARATOR) {
      return rdf.createIRI(CANONICAL_FRAGMENT_PREFIX + value.substring(base.length() + 1));
    }
    if (value.startsWith(CANONICALIZATION_SELF_PREFIX)) {
      return rdf.createIRI(CANONICAL_ESCAPE_PREFIX + value.substring(CANONICALIZATION_SELF_PREFIX.length()));
    }
    return iri;
  }

  /** Serializes the graph canonically as a sorted S-expression of length-prefixed fields. */
  private byte[] serializeFragmentGraph(Collection<Triple> triples) {
    List<byte[]> forms = triples.stream()
        .map(this::tripleToSexpr)
        .sorted(Arrays::compareUnsigned)
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
   * Appends a term as a kind tag followed by its components: the base IRI as the bare
   * {@code S} tag, a fragment IRI of the base as {@code F} and its fragment, any other IRI as
   * {@code I} and its IRI string, a
   * literal as lexical form, datatype IRI (written as an IRI term, so with its own kind tag) and
   * language tag, a blank node as its deterministic
   * skolem name. The kind tag separates the three term kinds, so in particular a blank node
   * cannot collide with an IRI that spells out its skolem name.
   *
   * <p>Every component is a netstring, and the field count follows from the kind tags alone, so
   * the concatenation is unambiguous — no component can be mistaken for the next one, and no
   * separator can be forged from within a value.</p>
   */
  private void appendTerm(List<byte[]> elements, RDFTerm term) {
    switch (term) {
    case IRI iri -> appendIri(elements, iri.getIRIString());
    case Literal lit -> {
      elements.add(toNetstring(KIND_LITERAL));
      elements.add(toNetstring(lit.getLexicalForm()));
      appendIri(elements, lit.getDatatype() == null ? "" : lit.getDatatype().getIRIString());
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

  /** Undoes {@link #withoutBase(IRI, String)} on a canonicalized IRI while writing it out. */
  private void appendIri(List<byte[]> elements, String canonicalIri) {
    if (canonicalIri.equals(CANONICAL_SELF)) {
      // The placeholder carries no payload: there is nothing about the base left to hash.
      elements.add(toNetstring(KIND_SELF));
      return;
    }
    if (canonicalIri.startsWith(CANONICAL_FRAGMENT_PREFIX)) {
      elements.add(toNetstring(KIND_FRAGMENT));
      elements.add(toNetstring(canonicalIri.substring(CANONICAL_FRAGMENT_PREFIX.length())));
      return;
    }
    elements.add(toNetstring(KIND_IRI));
    if (canonicalIri.startsWith(CANONICAL_ESCAPE_PREFIX)) {
      elements
          .add(toNetstring(CANONICALIZATION_SELF_PREFIX + canonicalIri.substring(CANONICAL_ESCAPE_PREFIX.length())));
      return;
    }
    elements.add(toNetstring(canonicalIri));
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
  public record ContentAddressableResult(IRI urn, byte[] sexprBytes) {

    /** Defensively copies {@code sexprBytes} so this result owns an immutable snapshot of it. */
    public ContentAddressableResult {
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
