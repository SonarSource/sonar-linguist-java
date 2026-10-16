/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DetectionOptionsTest {
  @Test
  void rejectsEmptyFilesByDefault() {
    assertThat(DetectionOptions.defaults().allowEmpty()).isFalse();
  }

  @Test
  void supportsAllowingEmptyFiles() {
    assertThat(new DetectionOptions(true).allowEmpty()).isTrue();
  }
}
