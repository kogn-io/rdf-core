// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

/**
 * Represents a Blank Node in RDF.
 *
 * <p>A blank node is an anonymous resource without a global identifier.
 * It is only identified within the scope of a single RDF graph.</p>
 *
 * <h2>Equality</h2>
 *
 * <p>Equality is defined by {@link #uniqueReference()} and is independent of the
 * implementing class. Following the
 * <a href="https://commons.apache.org/proper/commons-rdf/">Commons RDF</a> contract,
 * an implementation's {@link Object#equals(Object)} <strong>must</strong> return
 * {@code true} if and only if the other object is also a {@code BlankNode} and its
 * {@link #uniqueReference()} is an equal {@link String}; {@link Object#hashCode()}
 * <strong>must</strong> equal {@code uniqueReference().hashCode()}.</p>
 *
 * <p>Note that this is reference equality, not graph-isomorphism: two blank nodes are
 * equal only when they carry the same {@link #uniqueReference()}. As that reference is
 * only meaningful within a single graph (see {@link #uniqueReference()}), blank nodes
 * from different graphs must not be compared for identity.</p>
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
