/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicDetectorTest {
  private final LanguageCatalog catalog = LanguageCatalog.bundled();

  @Test
  void returnsFirstMatchingRuleForThePathExtension() {
    assertThat(detect("header.h", "@interface Example\n"))
      .extracting(Language::name)
      .containsExactly("Objective-C");
  }

  @Test
  void returnsFallbackRuleWhenNoEarlierPatternMatches() {
    assertThat(detect("query.sql", "select * from example;\n"))
      .extracting(Language::name)
      .containsExactly("SQL");
  }

  @Test
  void ignoresPathsWithoutHeuristicRules() {
    assertThat(detect("Main.java", "class Main {}\n")).isEmpty();
  }

  private List<Language> detect(String path, String content) {
    return HeuristicDetector.detect(
      FileContent.copyOf(path, content.getBytes(StandardCharsets.UTF_8)),
      catalog);
  }
}
