package io.kogn.rdf.terms;

/**
 * Represents a Blank Node in RDF.
 *
 * <p>A blank node is an anonymous resource without a global identifier.
 * It is only identified within the scope of a single RDF graph.</p>
 */
public interface BlankNode extends BlankNodeOrIRI {
  /**
   * Returns the internal identifier for this blank node.
   *
   * <p>This identifier is only unique within the context of a single graph
   * and should not be used for comparison across different graphs.</p>
   *
   * @return the blank node identifier
   */
  String uniqueReference();
}
