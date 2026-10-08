/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class HeuristicDetector {
  private static final HeuristicRules.Definition RULES = HeuristicRules.bundled();
  private static final HeuristicRegexMatcher MATCHER = HeuristicRegexMatcher.compile(RULES);

  private HeuristicDetector() {
  }

  static List<Language> detect(FileContent content, LanguageCatalog catalog) {
    String path = content.path().toLowerCase(Locale.ROOT);
    byte[] evaluationBytes = HeuristicRules.evaluationBytes(content);
    return RULES.disambiguations().stream()
      .filter(disambiguation -> disambiguation.extensions().stream().anyMatch(path::endsWith))
      .findFirst()
      .map(disambiguation -> HeuristicRuleEvaluator.evaluate(
        disambiguation,
        RULES.namedPatterns(),
        evaluationBytes,
        MATCHER))
      .orElseGet(List::of).stream()
      .map(catalog::byName)
      .flatMap(Optional::stream)
      .toList();
  }
}
