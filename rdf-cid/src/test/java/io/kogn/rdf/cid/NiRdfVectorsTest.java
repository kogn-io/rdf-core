// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.apicatalog.rdf.nquads.NQuadsReader;

import io.kogn.rdf.cid.sexpr.ContentAddressableRdfSerializer;
import io.kogn.rdf.cid.sexpr.RdfDatasetCanonicalizer;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs every test vector of the specification ni-rdf/1 ({@code docs/spec/ni-rdf/vectors/v1.json})
 * against {@link ContentAddressedIriGeneratorSexpr}: a vector expects either the name and the
 * hashed input, or the failure code the message starts with.
 */
class NiRdfVectorsTest {

  private static final Path VECTORS = Path.of("docs", "spec", "ni-rdf", "vectors", "v1.json");
  private static final String BLANK_NODE_PREFIX = "_:";

  private final RDF rdf = new SimpleRdf();
  private final ContentAddressedIriGenerator generator = new ContentAddressedIriGeneratorSexpr(rdf);
  private final ContentAddressableRdfSerializer serializer = new ContentAddressableRdfSerializer(
      new RdfDatasetCanonicalizer(), rdf);

  @TestFactory
  Stream<DynamicTest> everyVector() {
    JsonNode vectors = JsonMapper.builder().build().readTree(vectorsFile().toFile()).get("vectors");
    assertThat(vectors.size()).as("the vector file holds vectors").isPositive();
    return StreamSupport.stream(vectors.spliterator(), false)
        .map(vector -> DynamicTest.dynamicTest(vector.get("id").asString(), () -> verify(vector)));
  }

  /**
   * Vectors the pinned titanium-rdfc would get wrong because it sorts by UTF-16 code unit instead
   * of code point (https://github.com/filip26/titanium-rdf-canon/issues/65). Here the generator
   * fails closed: it throws a {@link ContentAddressingException} instead of minting a name that
   * deviates from the specification. The vector stays in the file for other implementations;
   * remove the entry once titanium is fixed.
   */
  private static final Set<String> BLOCKED_BY_TITANIUM_65 = Set.of("code-point-order");

  private void verify(JsonNode vector) {
    IRI base = rdf.createIRI(vector.get("base").asString());
    Graph graph = parseNTriples(vector.get("input").asString());

    if (BLOCKED_BY_TITANIUM_65.contains(vector.get("id").asString())) {
      assertThatThrownBy(() -> generator.generateIri(base, graph)).isExactlyInstanceOf(ContentAddressingException.class)
          .hasMessageContaining("titanium-rdf-canon#65");
      return;
    }

    if (vector.has("failure")) {
      String code = vector.get("failure").asString();
      Class<? extends RuntimeException> expected = "RESOURCE_LIMIT".equals(code)
          ? CanonicalizationResourceLimitExceededException.class
          : IllegalArgumentException.class;
      assertThatThrownBy(() -> generator.generateIri(base, graph)).isInstanceOf(expected)
          .hasMessageStartingWith("[" + code + "]");
      return;
    }

    assertThat(vector.has("name")).as("a success vector carries its expected name").isTrue();
    assertThat(generator.generateIri(base, graph).getIRIString()).isEqualTo(vector.get("name").asString());
    List<Triple> triples = graph.stream().toList();
    assertThat(new String(serializer.serializeWithIri(base, triples).sexprBytes(), StandardCharsets.UTF_8))
        .isEqualTo(vector.get("hashed").asString());
  }

  /** Parses an N-Triples document into the term model, keeping blank node labels as written. */
  private Graph parseNTriples(String input) {
    Graph graph = rdf.createGraph();
    try {
      new NQuadsReader(new StringReader(input))
          .provide((subject, predicate, object, datatype, language, direction, graphName) -> {
            RDFTerm objectTerm = datatype == null
                ? resource(object)
                : language != null
                    ? rdf.createLiteral(object, language)
                    : rdf.createLiteral(object, rdf.createIRI(datatype));
            graph.add(resource(subject), rdf.createIRI(predicate), objectTerm);
            return null;
          });
    } catch (Exception e) {
      throw new IllegalStateException("Vector input is not valid N-Triples", e);
    }
    return graph;
  }

  private BlankNodeOrIRI resource(String value) {
    return value.startsWith(BLANK_NODE_PREFIX)
        ? rdf.createBlankNode(value.substring(BLANK_NODE_PREFIX.length()))
        : rdf.createIRI(value);
  }

  /** The vector file, searched upwards from the working directory (the module or the root). */
  static Path vectorsFile() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      Path candidate = directory.resolve(VECTORS);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new UncheckedIOException(new IOException("No " + VECTORS + " above " + Path.of("").toAbsolutePath()));
  }
}
