// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms.vocab;

import io.kogn.rdf.terms.IRI;

/**
 * Dublin Core Terms vocabulary.
 *
 * <p>Provides constants for Dublin Core Terms properties commonly used for
 * metadata such as creation dates, modification dates, and provenance.</p>
 *
 * <p>This vocabulary is defined at <a href="http://purl.org/dc/terms/">
 * http://purl.org/dc/terms/</a></p>
 *
 * @see <a href="https://www.dublincore.org/specifications/dublin-core/dcmi-terms/">Dublin Core Terms Documentation</a>
 */
public interface VocabDct {
  /** The Dublin Core Terms namespace: {@value} */
  String NAMESPACE = "http://purl.org/dc/terms/";

  /**
   * The {@code dct:created} property.
   *
   * <p>Date of creation of the resource.</p>
   *
   * @see <a href="http://purl.org/dc/terms/created">dct:created</a>
   */
  IRI CREATED = VocabIriFactory.createIRI(NAMESPACE, "created");

  /**
   * The {@code dct:modified} property.
   *
   * <p>Date on which the resource was changed.</p>
   *
   * @see <a href="http://purl.org/dc/terms/modified">dct:modified</a>
   */
  IRI MODIFIED = VocabIriFactory.createIRI(NAMESPACE, "modified");

  /**
   * The {@code dct:creator} property.
   *
   * <p>An entity responsible for making the resource.</p>
   *
   * @see <a href="http://purl.org/dc/terms/creator">dct:creator</a>
   */
  IRI CREATOR = VocabIriFactory.createIRI(NAMESPACE, "creator");

  /**
   * The {@code dct:identifier} property.
   *
   * <p>An unambiguous reference to the resource within a given context.</p>
   *
   * @see <a href="http://purl.org/dc/terms/identifier">dct:identifier</a>
   */
  IRI IDENTIFIER = VocabIriFactory.createIRI(NAMESPACE, "identifier");

  /**
   * The {@code dct:isVersionOf} property.
   *
   * <p>A related resource of which the described resource is a version, edition, or adaptation.</p>
   *
   * @see <a href="http://purl.org/dc/terms/isVersionOf">dct:isVersionOf</a>
   */
  IRI IS_VERSION_OF = VocabIriFactory.createIRI(NAMESPACE, "isVersionOf");
}
