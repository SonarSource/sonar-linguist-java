/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class HeuristicRegexMatcherTest {
  @Test
  void compilesEveryBundledPattern() {
    assertThatCode(() -> HeuristicRegexMatcher.compile(HeuristicRules.bundled())).doesNotThrowAnyException();
  }

  @Test
  void evaluatesBundledRulesThroughCompiledPatterns() {
    HeuristicRules.Definition definition = HeuristicRules.bundled();
    HeuristicRules.Disambiguation yaml = definition.disambiguations().stream()
      .filter(disambiguation -> disambiguation.extensions().contains(".yaml"))
      .findFirst()
      .orElseThrow();
    HeuristicRegexMatcher matcher = HeuristicRegexMatcher.compile(definition);

    assertThat(HeuristicRuleEvaluator.evaluate(
      yaml,
      definition.namedPatterns(),
      "swagger: '2.0'\n".getBytes(StandardCharsets.ISO_8859_1),
      matcher))
      .containsExactly("OASv2-yaml");
  }
}
