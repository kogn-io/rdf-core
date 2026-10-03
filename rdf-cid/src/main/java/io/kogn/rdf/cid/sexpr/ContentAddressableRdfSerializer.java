// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.kogn.rdf.cid.CanonicalizationResourceLimitExceededException;
import io.kogn.rdf.cid.ContentAddressingException;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Derives the {@code ni:///sha3-256;…} name of the one resource a collection of triples
 * describes, following the specification ni-rdf/1 ({@code docs/spec/ni-rdf/v1.md} in the
 * kognio-rdf repository), which defines every byte of the derivation.
 *
 * <p>The resource is named by a <em>base IRI</em> {@code B}, an IRI without {@code #} that the
 * caller passes in rather than having it inferred from the triples. In short:</p>
 * <ol>
 *   <li>the preconditions of §3 are checked in order — the graph describes exactly {@code B}:
 *       every IRI subject is {@code B} or a fragment IRI {@code <B#f>} of it, and every other
 *       triple is a blank node triple reachable from one of them;</li>
 *   <li>language tags are lower-cased (§4.1);</li>
 *   <li>{@code B} and its fragment IRIs are mapped, in every position including datatype IRIs,
 *       onto the reserved IRI {@code R} = {@value #RESERVED_IRI} and its fragments (§4.2), so
 *       neither the blank node labels nor the digest can depend on {@code B};</li>
 *   <li>the resource limit of §4.3 is checked and the blank nodes are labelled with RDFC-1.0
 *       (§4.4), both by {@link RdfDatasetCanonicalizer};</li>
 *   <li>the triples are serialized into a sorted S-expression of length-prefixed strings,
 *       opened by the procedure tag {@value #PROCEDURE_TAG} (§4.5) — {@code R} as {@code S},
 *       {@code R#f} as {@code F} and {@code f}, any other IRI as {@code I} and its string, a
 *       literal as {@code L}, lexical form, datatype IRI and language tag, a blank node as
 *       {@code B} and its canonical issued identifier;</li>
 *   <li>the digest is SHA3-256 of those bytes, and the name is {@value #NI_PREFIX} followed by
 *       its unpadded base64url encoding (§4.7, §4.8).</li>
 * </ol>
 *
 * <p>The name is therefore independent of blank node labels, of triple order, of the case of
 * language tags and of the base IRI the resource is described under, but <em>not</em> of any
 * other term: an IRI by its string, a literal by lexical form, datatype <em>and</em> language
 * tag. Because {@code B} stays out of the digest, the resource can be renamed to its own name
 * ({@code <B>} to {@code <ni:…>}, {@code <B#f>} to {@code <ni:…#f>}) and deriving the name
 * again from the renamed triples, with the name as base, yields the same name.</p>
 *
 * <p>Every precondition failure is an {@link IllegalArgumentException} whose message starts
 * with the failure code of ni-rdf/1 §5 in brackets, e.g. {@code [FOREIGN_SUBJECT]}; an exceeded
 * resource limit is a {@link CanonicalizationResourceLimitExceededException} starting with
 * {@code [RESOURCE_LIMIT]}.</p>
 */
public class ContentAddressableRdfSerializer {

  /** The canonical prefix of every name: RFC 6920 {@code ni:} without authority, SHA3-256. */
  public static final String NI_PREFIX = "ni:///sha3-256;";

  /** The procedure tag that opens the hashed input (ni-rdf/1 §4.5). */
  public static final String PROCEDURE_TAG = "ni-rdf/1";

  /**
   * The reserved IRI {@code R} of ni-rdf/1 §1.2: the base IRI is mapped onto it before
   * canonicalization, so no IRI of the input may have it as its base.
   */
  public static final String RESERVED_IRI = "urn:uuid:171650ec-eda4-47fd-9053-6a34696171c1";

  private static final String RESERVED_FRAGMENT_PREFIX = RESERVED_IRI + "#";
  private static final char FRAGMENT_SEPARATOR = '#';

  /** Term kind tags written into the serialized form so terms of different kinds cannot collide. */
  private static final String KIND_IRI = "I";
  private static final String KIND_LITERAL = "L";
  private static final String KIND_BLANK = "B";
  private static final String KIND_SELF = "S";
  private static final String KIND_FRAGMENT = "F";

  private static final byte OPEN = '(';
  private static final byte CLOSE = ')';
  private static final byte LENGTH_SEPARATOR = ':';
  private static final String DIGEST_ALGORITHM = "SHA3-256";

  private final RdfDatasetCanonicalizer canonicalizer;
  private final RDF rdf;

  /**
   * Creates a serializer using {@link SimpleRdf} to create the resulting name.
   *
   * @param canonicalizer the RDFC-1.0 canonicalizer that labels the blank nodes
   */
  public ContentAddressableRdfSerializer(RdfDatasetCanonicalizer canonicalizer) {
    this(canonicalizer, new SimpleRdf());
  }

  /**
   * Creates a serializer.
   *
   * @param canonicalizer the RDFC-1.0 canonicalizer that labels the blank nodes
   * @param rdf the term factory used to create the resulting name and the mapped triples
   */
  public ContentAddressableRdfSerializer(RdfDatasetCanonicalizer canonicalizer, RDF rdf) {
    this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer must not be null");
    this.rdf = Objects.requireNonNull(rdf, "rdf must not be null");
  }

  /**
   * Derives the name of the resource {@code base} that the triples describe, together with the
   * bytes it is the digest of.
   *
   * @param base the base IRI {@code B} of the resource the triples describe
   * @param triples the graph {@code G} describing that resource
   * @return the name together with the hashed input {@code D} it was derived from
   * @throws IllegalArgumentException if {@code base} or {@code triples} is null, or if a
   *         precondition of ni-rdf/1 §3 does not hold; the message then starts with its failure
   *         code in brackets ({@code [INVALID_BASE]}, {@code [RESERVED_IRI]},
   *         {@code [EMPTY_GRAPH]}, {@code [UNSUPPORTED_TERM]}, {@code [FOREIGN_SUBJECT]},
   *         {@code [NO_ROOT]} or {@code [UNREACHABLE]}), the first failing one in the order of §3
   * @throws CanonicalizationResourceLimitExceededException if the blank node core exceeds the
   *         resource limit of ni-rdf/1 §4.3
   * @throws ContentAddressingException if canonicalization otherwise fails
   */
  public ContentAddressableResult serializeWithIri(IRI base, Collection<Triple> triples) {
    String baseIri = Preconditions.check(base, triples, RESERVED_IRI);

    List<Triple> mapped = triples.stream().map(t -> mapped(t, baseIri)).distinct().toList();
    Map<String, String> labels = canonicalizer.canonicalIdentifiers(mapped);
    byte[] hashed = document(mapped, labels);

    String name = NI_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(sha3256(hashed));
    return new ContentAddressableResult(rdf.createIRI(name), hashed);
  }

  /**
   * Applies §4.1 and §4.2 to a triple: language tags lower-cased, the base IRI and its fragment
   * IRIs mapped onto the reserved IRI in every position, a literal's datatype IRI included.
   * Mapped triples that become equal (two spellings of one language tag) collapse into one, as
   * RDF 1.1 compares language tags case-insensitively.
   */
  private Triple mapped(Triple triple, String base) {
    BlankNodeOrIRI subject = triple.getSubject() instanceof IRI iri ? mapped(iri, base) : triple.getSubject();
    RDFTerm object = switch (triple.getObject()) {
    case IRI iri -> mapped(iri, base);
    case Literal literal -> mapped(literal, base);
    default -> triple.getObject();
    };
    return rdf.createTriple(subject, mapped(triple.getPredicate(), base), object);
  }

  private Literal mapped(Literal literal, String base) {
    if (literal.getLanguageTag().isPresent()) {
      return rdf.createLiteral(literal.getLexicalForm(), Terms.lowerCaseLanguageTag(literal.getLanguageTag().get()));
    }
    return rdf.createLiteral(literal.getLexicalForm(), rdf.createIRI(mapped(Terms.datatypeOf(literal), base)));
  }

  private IRI mapped(IRI iri, String base) {
    String value = iri.getIRIString();
    String mappedValue = mapped(value, base);
    return mappedValue.equals(value) ? iri : rdf.createIRI(mappedValue);
  }

  /** {@code B} becomes {@code R}, {@code B#f} becomes {@code R#f}, any other IRI stays. */
  private static String mapped(String iri, String base) {
    if (iri.equals(base)) {
      return RESERVED_IRI;
    }
    if (iri.length() > base.length() && iri.startsWith(base) && iri.charAt(base.length()) == FRAGMENT_SEPARATOR) {
      return RESERVED_FRAGMENT_PREFIX + iri.substring(base.length() + 1);
    }
    return iri;
  }

  /** The hashed input {@code D} of §4.5: the procedure tag and the sorted triple encodings. */
  private static byte[] document(List<Triple> triples, Map<String, String> labels) {
    List<byte[]> encodings = triples.stream()
        .map(triple -> encoded(triple, labels))
        .sorted(Arrays::compareUnsigned)
        .toList();

    ByteArrayOutputStream document = new ByteArrayOutputStream();
    document.write(OPEN);
    writeString(document, PROCEDURE_TAG);
    encodings.forEach(document::writeBytes);
    document.write(CLOSE);
    return document.toByteArray();
  }

  /** One triple as {@code (}, subject, predicate, object, {@code )}. */
  private static byte[] encoded(Triple triple, Map<String, String> labels) {
    ByteArrayOutputStream encoding = new ByteArrayOutputStream();
    encoding.write(OPEN);
    writeTerm(encoding, triple.getSubject(), labels);
    writeTerm(encoding, triple.getPredicate(), labels);
    writeTerm(encoding, triple.getObject(), labels);
    encoding.write(CLOSE);
    return encoding.toByteArray();
  }

  /**
   * Writes a term as its kind tag followed by its components, every one a length-prefixed
   * string, so the concatenation is unambiguous and no value can spell a placeholder.
   */
  private static void writeTerm(ByteArrayOutputStream out, RDFTerm term, Map<String, String> labels) {
    switch (term) {
    case IRI iri -> writeIri(out, iri.getIRIString());
    case Literal literal -> {
      writeString(out, KIND_LITERAL);
      writeString(out, literal.getLexicalForm());
      writeIri(out, Terms.datatypeOf(literal));
      writeString(out, literal.getLanguageTag().orElse(""));
    }
    case BlankNode blankNode -> {
      String label = labels.get(blankNode.uniqueReference());
      if (label == null) {
        throw new IllegalStateException("RDFC-1.0 issued no identifier for " + blankNode.ntriplesString());
      }
      writeString(out, KIND_BLANK);
      writeString(out, label);
    }
    default -> throw new IllegalStateException("Unsupported term kind: " + term.getClass());
    }
  }

  /** {@code R} as {@code S}, {@code R#f} as {@code F} and {@code f}, any other IRI as {@code I}. */
  private static void writeIri(ByteArrayOutputStream out, String iri) {
    if (iri.equals(RESERVED_IRI)) {
      writeString(out, KIND_SELF);
      return;
    }
    if (iri.startsWith(RESERVED_FRAGMENT_PREFIX)) {
      writeString(out, KIND_FRAGMENT);
      writeString(out, iri.substring(RESERVED_FRAGMENT_PREFIX.length()));
      return;
    }
    writeString(out, KIND_IRI);
    writeString(out, iri);
  }

  /** {@code LS(x)}: the UTF-8 byte length in ASCII decimal, {@code :}, the UTF-8 bytes. */
  private static void writeString(ByteArrayOutputStream out, String value) {
    byte[] data = value.getBytes(StandardCharsets.UTF_8);
    out.writeBytes(Integer.toString(data.length).getBytes(StandardCharsets.US_ASCII));
    out.write(LENGTH_SEPARATOR);
    out.writeBytes(data);
  }

  private static byte[] sha3256(byte[] input) {
    try {
      return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(input);
    } catch (NoSuchAlgorithmException e) {
      // SHA3-256 is a JDK-guaranteed MessageDigest algorithm since Java 9.
      throw new IllegalStateException(DIGEST_ALGORITHM + " unavailable", e);
    }
  }

  /**
   * One name together with the bytes it is the digest of.
   *
   * @param iri the {@code ni:///sha3-256;…} name
   * @param sexprBytes the hashed input {@code D} of ni-rdf/1 §4.5 {@code iri} was derived from;
   *     defensively copied on construction and on every {@link #sexprBytes()} call, so neither
   *     the caller's original array nor a returned copy can change what this result reports
   *     having hashed
   */
  public record ContentAddressableResult(IRI iri, byte[] sexprBytes) {

    /** Defensively copies {@code sexprBytes} so this result owns an immutable snapshot of it. */
    public ContentAddressableResult {
      sexprBytes = sexprBytes.clone();
    }

    /**
     * Returns the hashed input {@link #iri()} was derived from.
     *
     * @return a copy of the serialized bytes
     */
    @Override
    public byte[] sexprBytes() {
      return sexprBytes.clone();
    }
  }
}
