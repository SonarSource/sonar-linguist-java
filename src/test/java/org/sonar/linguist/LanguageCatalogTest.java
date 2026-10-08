/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageCatalogTest {

  @Test
  void sharesBundledCatalog() {
    assertThat(LanguageCatalog.bundled()).isSameAs(LanguageCatalog.bundled());
  }

  @Test
  void supportsConcurrentDetection() throws Exception {
    LanguageCatalog catalog = LanguageCatalog.bundled();
    Language java = catalog.detect("Main.java").orElseThrow();
    Callable<Language> detection = () -> catalog.detect("Main.java").orElseThrow();

    try (var executor = Executors.newFixedThreadPool(8)) {
      for (var result : executor.invokeAll(Collections.nCopies(100, detection))) {
        assertThat(result.get()).isSameAs(java);
      }
    }
  }

  @Test
  void preservesUpstreamCandidateOrder() {
    LanguageCatalog catalog = LanguageCatalog.bundled();

    assertThat(names(catalog.findByExtension("foo.m")))
      .containsExactly("Limbo", "M", "MATLAB", "MUF", "Mercury", "Objective-C", "Wolfram Language");
    assertThat(names(catalog.findByFilename("config/.eslintrc")))
      .containsExactly("JSON with Comments", "YAML");
    assertThat(names(catalog.findByExtension("query.sql")))
      .containsExactly("PLSQL", "PLpgSQL", "SQL", "SQLPL", "TSQL");
  }

  @Test
  void matchesExactBasenamesWithCaseSensitiveLookup() {
    LanguageCatalog catalog = LanguageCatalog.bundled();

    assertThat(names(catalog.findByFilename("nested/Rakefile"))).containsExactly("Ruby");
    assertThat(catalog.findByFilename("nested/rakefile")).isEmpty();
    assertThat(names(catalog.findByFilename("nested/.eslintrc"))).containsExactly("JSON with Comments", "YAML");
  }

  @Test
  void preservesUpstreamInterpreterCandidateOrderAndCase() {
    LanguageCatalog catalog = LanguageCatalog.bundled();

    assertThat(names(catalog.findByInterpreter("node"))).containsExactly("JavaScript", "TypeScript");
    assertThat(names(catalog.findByInterpreter("Rscript"))).containsExactly("R");
    assertThat(catalog.findByInterpreter("rscript")).isEmpty();
  }

  @Test
  void findsLongestCompoundExtensionCaseInsensitively() {
    LanguageCatalog catalog = LanguageCatalog.bundled();

    assertThat(names(catalog.findByExtension("BUILD.GRADLE.KTS"))).containsExactly("Gradle Kotlin DSL");
    assertThat(names(catalog.findByExtension("script.KTS"))).containsExactly("Kotlin");
    assertThat(catalog.findByExtension("kts")).isEmpty();
  }

  private static List<String> names(List<Language> languages) {
    return languages.stream().map(Language::name).toList();
  }
}
