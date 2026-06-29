package io.kogn.rdf.terms;

/**
 * Factory interface for creating {@link IRI} instances.
 *
 * <p>Extracted from {@link RDF} to allow components that only need IRI creation
 * (e.g., vocabulary constants, URI normalization) to depend on this minimal
 * interface instead of the full RDF factory.</p>
 *
 * @see RDF
 * @see SimpleIRI
 */
public interface IRIFactory {

  /**
   * Creates a new IRI from a string.
   *
   * @param iri the IRI string
   * @return the IRI instance
   */
  IRI createIRI(String iri);
}
