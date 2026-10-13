/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionDetectorTest {
  private final LanguageCatalog catalog = LanguageCatalog.bundled();

  @Test
  void returnsExtensionCandidatesInUpstreamOrder() {
    assertThat(names(ExtensionDetector.detect("query.sql", catalog, List.of())))
      .containsExactly("PLSQL", "PLpgSQL", "SQL", "SQLPL", "TSQL");
  }

  @Test
  void intersectsWithExistingCandidatesInTheirOrder() {
    Language typescript = catalog.byAlias("typescript").orElseThrow();
    Language javascript = catalog.byAlias("javascript").orElseThrow();

    assertThat(ExtensionDetector.detect("script.ts", catalog, List.of(javascript, typescript)))
      .containsExactly(typescript);
  }

  @Test
  void preservesCandidatesAndDoesNotIntroduceCandidatesForGenericExtensions() {
    Language javascript = catalog.byAlias("javascript").orElseThrow();
    Language typescript = catalog.byAlias("typescript").orElseThrow();

    assertThat(ExtensionDetector.detect("model.STL", catalog, List.of(javascript, typescript)))
      .containsExactly(javascript, typescript);
    assertThat(ExtensionDetector.detect("model.stl", catalog, List.of())).isEmpty();
  }

  private static List<String> names(List<Language> languages) {
    return languages.stream().map(Language::name).toList();
  }
}
