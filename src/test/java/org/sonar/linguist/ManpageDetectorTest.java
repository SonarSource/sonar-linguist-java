/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ManpageDetectorTest {
  private final LanguageCatalog catalog = LanguageCatalog.bundled();

  @ParameterizedTest
  @ValueSource(strings = {
    "bsdmalloc.3malloc",
    "dirent.h.0p",
    "linguist.1gh",
    "test.1.in",
    "test.9.in",
    "test.man.in",
    "test.mdoc.in",
    "TEST.MAN"
  })
  void detectsConventionalManpageExtensions(String path) {
    assertThat(names(ManpageDetector.detect(path, catalog, List.of())))
      .containsExactly("Roff Manpage", "Roff");
  }

  @ParameterizedTest
  @ValueSource(strings = {"manual.0", "manual.10", "manual.txt", "manual.manual"})
  void rejectsNonManpageExtensions(String path) {
    assertThat(ManpageDetector.detect(path, catalog, List.of())).isEmpty();
  }

  @Test
  void preservesExistingCandidates() {
    Language markdown = catalog.byAlias("Markdown").orElseThrow();

    assertThat(ManpageDetector.detect("manual.1", catalog, List.of(markdown)))
      .containsExactly(markdown);
  }

  private static List<String> names(List<Language> languages) {
    return languages.stream().map(Language::name).toList();
  }
}
