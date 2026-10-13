/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedRegexTest {
  @Test
  void matchesByteOrientedMultilineContent() {
    BoundedRegex regex = BoundedRegex.compile("^second$", 100);

    assertThat(regex.matches(bytes("first\nsecond\nthird"))).isTrue();
  }

  @Test
  void rejectsContentBeyondConfiguredBound() {
    BoundedRegex regex = BoundedRegex.compile("content", 3);

    assertThatThrownBy(() -> regex.matches(bytes("four")))
      .isInstanceOf(BoundedRegex.EvaluationException.class)
      .hasMessage("Regex input exceeds 3 bytes");
  }

  @Test
  void stopsPathologicalBacktrackingAtDeterministicExecutionBudget() {
    BoundedRegex regex = BoundedRegex.compile("(a+)+b", 1_000, 1_000);

    assertThatThrownBy(() -> regex.matches(bytes("a".repeat(500))))
      .isInstanceOf(BoundedRegex.EvaluationException.class)
      .hasMessage("Regex execution budget exhausted");
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.ISO_8859_1);
  }
}
