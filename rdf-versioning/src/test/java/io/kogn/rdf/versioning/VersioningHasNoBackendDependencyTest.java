// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Structural test: {@code rdf-versioning} is backend-free and independent of the hosting
 * layer (ADR-0021). RDF4J is on the test classpath, so a class lookup cannot prove its
 * absence; the main sources are checked for imports instead. The compile classpath
 * itself is held by the POM.
 */
class VersioningHasNoBackendDependencyTest {

  private static final List<String> FORBIDDEN = List.of("org.eclipse.rdf4j.", "io.kogn.rdf.rdf4j.",
      "io.kogn.rdf.dataset.hosting.");

  @Test
  void mainSourcesImportNoBackendAndNoHosting() throws IOException {
    final Path main = Path.of("src", "main", "java");
    try (Stream<Path> files = Files.walk(main)) {
      final List<String> offending = files.filter(file -> file.toString().endsWith(".java"))
          .flatMap(VersioningHasNoBackendDependencyTest::lines)
          .filter(line -> line.startsWith("import "))
          .filter(line -> FORBIDDEN.stream().anyMatch(line::contains))
          .toList();
      assertThat(offending).isEmpty();
    }
  }

  private static Stream<String> lines(final Path file) {
    try {
      return Files.readAllLines(file).stream();
    } catch (IOException e) {
      throw new IllegalStateException(file.toString(), e);
    }
  }
}
