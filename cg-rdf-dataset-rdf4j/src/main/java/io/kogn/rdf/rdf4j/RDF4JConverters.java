package io.kogn.rdf.rdf4j;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.util.Values;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;

/**
 * Package-private utility class for converting between our RDF API types and RDF4J types.
 *
 * <p>This class provides static conversion methods that support both native RDF4J implementations
 * (direct access) and foreign implementations (conversion via string representation).</p>
 */
public class RDF4JConverters {

  // TODO package private, wenn wir mit io.kogn.rdf.repository durch sind!

  private RDF4JConverters() {
    // Utility class - no instantiation
  }

  /**
   * Converts an IRI from our API to RDF4J IRI.
   * Supports both RDF4JIRI (direct access) and foreign implementations (conversion).
   *
   * @param iri the IRI to convert
   * @return RDF4J IRI
   */
  public static org.eclipse.rdf4j.model.IRI toRDF4JIRI(IRI iri) {
    // TODO package private, wenn wir mit
    // io.kogn.rdf.repository durch sind!
    if (iri instanceof RDF4JIRI) {
      return ((RDF4JIRI) iri).getRDF4JValue();
    }
    // Convert foreign IRI implementation by re-creating from IRI string
    return Values.iri(iri.getIRIString());
  }

  /**
   * Converts a BlankNodeOrIRI from our API to RDF4J Resource.
   * Supports both RDF4J implementations and foreign implementations.
   *
   * @param resource the resource to convert
   * @return RDF4J Resource
   */
  public static org.eclipse.rdf4j.model.Resource toRDF4JResource(BlankNodeOrIRI resource) {
    if (resource instanceof RDF4JTerm) {
      return (org.eclipse.rdf4j.model.Resource) ((RDF4JTerm) resource).getRDF4JValue();
    }
    // Convert foreign implementations
    if (resource instanceof IRI) {
      return Values.iri(((IRI) resource).getIRIString());
    }
    if (resource instanceof BlankNode) {
      return Values.bnode(((BlankNode) resource).uniqueReference());
    }
    throw new IllegalArgumentException("Unsupported BlankNodeOrIRI type: " + resource.getClass());
  }

  /**
   * Converts an RDFTerm from our API to RDF4J Value.
   * Supports both RDF4J implementations and foreign implementations.
   *
   * @param term the term to convert
   * @return RDF4J Value
   */
  public static Value toRDF4JValue(RDFTerm term) {
    if (term instanceof RDF4JTerm) {
      return ((RDF4JTerm) term).getRDF4JValue();
    }
    // Convert foreign implementations
    if (term instanceof IRI) {
      return Values.iri(((IRI) term).getIRIString());
    }
    if (term instanceof Literal) {
      Literal lit = (Literal) term;
      if (lit.getLanguageTag().isPresent()) {
        return Values.literal(lit.getLexicalForm(), lit.getLanguageTag().get());
      }
      if (lit.getDatatype() != null) {
        org.eclipse.rdf4j.model.IRI datatype = toRDF4JIRI(lit.getDatatype());
        return Values.literal(lit.getLexicalForm(), datatype);
      }
      return Values.literal(lit.getLexicalForm());
    }
    if (term instanceof BlankNode) {
      return Values.bnode(((BlankNode) term).uniqueReference());
    }
    throw new IllegalArgumentException("Unsupported RDFTerm type: " + term.getClass());
  }
}
