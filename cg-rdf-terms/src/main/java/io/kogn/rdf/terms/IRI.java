package io.kogn.rdf.terms;

/**
 * Represents an IRI (Internationalized Resource Identifier) in RDF.
 *
 * <p>An IRI is a globally unique identifier for resources in RDF graphs.
 * It extends the concept of URI to support international characters.</p>
 */
public interface IRI extends BlankNodeOrIRI {
  /**
   * Returns the IRI string representation.
   *
   * @return the full IRI as string
   */
  String getIRIString();
}
