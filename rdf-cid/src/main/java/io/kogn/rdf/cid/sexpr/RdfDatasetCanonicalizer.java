// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.apicatalog.rdf.Rdf;
import com.apicatalog.rdf.RdfDataset;
import com.apicatalog.rdf.RdfResource;
import com.apicatalog.rdf.RdfTriple;

import io.kogn.rdf.cid.ContentAddressingException;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.setl.rdf.normalization.RdfNormalize;
import lombok.extern.slf4j.Slf4j;

/**
 * Canonicalizes RDF datasets using URDNA2015 algorithm.
 *
 * <p>Based on io.setl:rdf-urdna library which implements the W3C RDF Dataset
 * Canonicalization specification (URDNA2015).</p>
 *
 * <p>This class converts our RDF API types to Titanium JSON-LD format,
 * applies canonicalization, and converts back.</p>
 */
@Slf4j
public class RdfDatasetCanonicalizer {

  private final RDF rdf;

  /** Creates a canonicalizer using {@link SimpleRdf} to rebuild terms from the canonical form. */
  public RdfDatasetCanonicalizer() {
    this(new SimpleRdf());
  }

  /**
   * Creates a canonicalizer.
   *
   * @param rdf the term factory used to rebuild terms from the canonical form
   */
  public RdfDatasetCanonicalizer(RDF rdf) {
    this.rdf = rdf;
  }

  /**
   * Canonicalizes a collection of RDF triples.
   *
   * <p>Ensures that blank nodes are consistently labeled, so identical
   * RDF graphs produce identical canonical forms regardless of how blank
   * nodes were originally named.</p>
   *
   * @param triples the triples to canonicalize
   * @return canonicalized triples with deterministic blank node labels
   * @throws ContentAddressingException if canonicalization fails; the underlying failure is
   *         kept as cause
   */
  public Collection<Triple> canonicalize(Collection<Triple> triples) {
    try {
      // 1. Convert our RDF API to Titanium RdfDataset
      RdfDataset dataset = toTitaniumDataset(triples);

      // 2. Apply URDNA2015 canonicalization
      RdfDataset normalized = RdfNormalize.normalize(dataset);

      // 3. Convert back to our RDF API
      return fromTitaniumDataset(normalized);
    } catch (Exception e) {
      log.error("Failed to canonicalize RDF dataset", e);
      throw new ContentAddressingException("RDF canonicalization failed", e);
    }
  }

  private RdfDataset toTitaniumDataset(Collection<Triple> triples) {
    RdfDataset dataset = Rdf.createDataset();

    for (Triple triple : triples) {
      RdfResource subject = toTitaniumResource(triple.getSubject());
      RdfResource predicate = Rdf.createIRI(triple.getPredicate().getIRIString());
      com.apicatalog.rdf.RdfValue object = toTitaniumValue(triple.getObject());

      RdfTriple rdfTriple = Rdf.createTriple(subject, predicate, object);
      dataset.add(rdfTriple);
    }

    return dataset;
  }

  private Collection<Triple> fromTitaniumDataset(RdfDataset dataset) {
    List<Triple> result = new ArrayList<>();

    for (RdfTriple rdfTriple : dataset.toList()) {
      result.add(fromTitaniumTriple(rdfTriple));
    }

    return result;
  }

  private RdfResource toTitaniumResource(BlankNodeOrIRI resource) {
    if (resource instanceof IRI iri) {
      return Rdf.createIRI(iri.getIRIString());
    } else if (resource instanceof BlankNode bn) {
      return Rdf.createBlankNode(bn.uniqueReference());
    }
    throw new IllegalArgumentException("Unknown resource type: " + resource.getClass());
  }

  private com.apicatalog.rdf.RdfValue toTitaniumValue(RDFTerm term) {
    if (term instanceof IRI iri) {
      return Rdf.createIRI(iri.getIRIString());
    } else if (term instanceof Literal lit) {
      String lexical = lit.getLexicalForm();
      if (lit.getLanguageTag().isPresent()) {
        return Rdf.createLangString(lexical, lit.getLanguageTag().get());
      }
      IRI datatype = lit.getDatatype();
      if (datatype != null) {
        return Rdf.createTypedString(lexical, datatype.getIRIString());
      }
      return Rdf.createValue(lexical);
    } else if (term instanceof BlankNode bn) {
      return Rdf.createBlankNode(bn.uniqueReference());
    }
    throw new IllegalArgumentException("Unknown term type: " + term.getClass());
  }

  private Triple fromTitaniumTriple(RdfTriple rdfTriple) {
    BlankNodeOrIRI subject = fromTitaniumResource(rdfTriple.getSubject());
    IRI predicate = rdf.createIRI(rdfTriple.getPredicate().getValue());
    RDFTerm object = fromTitaniumTerm(rdfTriple.getObject());

    return rdf.createTriple(subject, predicate, object);
  }

  private BlankNodeOrIRI fromTitaniumResource(RdfResource resource) {
    if (resource.isIRI()) {
      return rdf.createIRI(resource.getValue());
    } else if (resource.isBlankNode()) {
      return rdf.createBlankNode(resource.getValue());
    }
    throw new IllegalArgumentException("Unknown resource type");
  }

  private RDFTerm fromTitaniumTerm(com.apicatalog.rdf.RdfValue value) {
    if (value.isIRI()) {
      return rdf.createIRI(value.getValue());
    } else if (value.isLiteral()) {
      com.apicatalog.rdf.RdfLiteral literal = value.asLiteral();
      String lexical = literal.getValue();

      if (literal.getLanguage().isPresent()) {
        return rdf.createLiteral(lexical, literal.getLanguage().get());
      }

      if (literal.getDatatype() != null) {
        return rdf.createLiteral(lexical, rdf.createIRI(literal.getDatatype()));
      }

      return rdf.createLiteral(lexical);
    } else if (value.isBlankNode()) {
      return rdf.createBlankNode(value.getValue());
    }
    throw new IllegalArgumentException("Unknown value type");
  }
}
