/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicRulesTest {
  @Test
  void parsesBundledDefinitionsWithoutChangingOrder() {
    HeuristicRules.Definition definition = HeuristicRules.bundled();

    assertThat(definition.disambiguations()).hasSize(145);
    assertThat(definition.namedPatterns()).hasSize(23);
    assertThat(definition.namedPatterns().keySet()).startsWith("cpp", "euphoria", "fortran");

    HeuristicRules.Disambiguation first = definition.disambiguations().getFirst();
    assertThat(first.extensions()).containsExactly(".1", ".2", ".3", ".4", ".5", ".6", ".7", ".8", ".9");
    assertThat(first.rules()).extracting(rule -> rule.languages().getFirst())
      .containsExactly("Roff Manpage", "Roff Manpage", "Roff", "Text");

    HeuristicRules.All condition = (HeuristicRules.All) first.rules().getFirst().condition();
    assertThat(condition.conditions())
      .containsExactly(
        new HeuristicRules.NamedPattern("mdoc-date"),
        new HeuristicRules.NamedPattern("mdoc-title"),
        new HeuristicRules.NamedPattern("mdoc-heading"));
  }

  @Test
  void normalizesScalarAndListPatternsWithoutCompilingThem() {
    HeuristicRules.Definition definition = HeuristicRules.bundled();

    assertThat(definition.namedPatterns().get("fortran"))
      .containsExactly("^(?i:[c*][^abd-z]|      (subroutine|program|end|data)\\s|\\s*!)");
    assertThat(definition.namedPatterns().get("cpp")).hasSize(8);
  }

  @Test
  void limitsEvaluationToFirstFiftyKibibytes() {
    byte[] bytes = new byte[HeuristicRules.EVALUATION_BYTES + 100];
    Arrays.fill(bytes, (byte) 'a');
    bytes[HeuristicRules.EVALUATION_BYTES] = 'b';

    byte[] evaluationBytes = HeuristicRules.evaluationBytes(FileContent.copyOf("file.ext", bytes));

    assertThat(evaluationBytes).hasSize(HeuristicRules.EVALUATION_BYTES).containsOnly((byte) 'a');
  }

  @Test
  void exposesImmutableDefinitions() {
    HeuristicRules.Definition definition = HeuristicRules.bundled();

    assertThat(definition.disambiguations()).isUnmodifiable();
    assertThat(definition.namedPatterns()).isUnmodifiable();
    assertThat(definition.disambiguations().getFirst().extensions()).isUnmodifiable();
    assertThat(definition.disambiguations().getFirst().rules()).isUnmodifiable();
    assertThat(definition.namedPatterns().get("cpp")).isUnmodifiable();
  }
}
