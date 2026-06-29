package io.kogn.rdf.terms;

/**
 * Simple value-based implementation of {@link IRI}.
 *
 * <p>This record provides a lightweight IRI implementation that does not depend
 * on any specific RDF backend (RDF4J, Jena, etc.). It is used internally by
 * {@link SimpleRdf} and vocabulary constants.</p>
 */
public record SimpleIRI(String iri) implements IRI {

  public SimpleIRI {
    if (iri == null || iri.isEmpty()) {
      throw new IllegalArgumentException("IRI string must not be null or empty");
    }
  }

  @Override
  public String ntriplesString() {
    return "<" + iri + ">";
  }

  @Override
  public String getIRIString() {
    return iri;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (!(obj instanceof IRI other))
      return false;
    return iri.equals(other.getIRIString());
  }

  @Override
  public int hashCode() {
    return iri.hashCode();
  }

  @Override
  public String toString() {
    return iri;
  }
}
