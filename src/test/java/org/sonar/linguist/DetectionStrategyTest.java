/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DetectionStrategyTest {
  @Test
  void definesStableValuesInPipelineOrder() {
    assertThat(DetectionStrategy.values()).containsExactly(
      DetectionStrategy.MODELINE,
      DetectionStrategy.FILENAME,
      DetectionStrategy.SHEBANG,
      DetectionStrategy.EXTENSION,
      DetectionStrategy.XML,
      DetectionStrategy.MANPAGE,
      DetectionStrategy.HEURISTICS,
      DetectionStrategy.CLASSIFIER,
      DetectionStrategy.NO_MATCH);
  }

  @Test
  void exposesUpstreamDiagnosticNames() {
    assertThat(Arrays.stream(DetectionStrategy.values()).map(DetectionStrategy::upstreamName))
      .containsExactly(
        "Modeline",
        "Filename",
        "Shebang",
        "Extension",
        "XML",
        "Manpage",
        "Heuristics",
        "Classifier",
        "No match");
  }
}
