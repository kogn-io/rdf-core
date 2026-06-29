// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

/**
 * Marker interface for RDF terms that can be used as subjects in triples.
 *
 * <p>In RDF, only IRIs and Blank Nodes can be used as subjects.
 * Literals cannot be subjects.</p>
 */
public interface BlankNodeOrIRI extends RDFTerm {
}
