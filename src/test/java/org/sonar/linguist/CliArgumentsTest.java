/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliArgumentsTest {
  @Test
  void parsesFilesAndDetectionOptions() {
    CliArguments arguments = CliArguments.parse(new String[] {"src/Main.java", "--allow-empty", "README"});

    assertThat(arguments.files()).containsExactly(Path.of("src/Main.java"), Path.of("README"));
    assertThat(arguments.detectionOptions()).isEqualTo(new DetectionOptions(true));
  }

  @Test
  void usesDefaultDetectionOptions() {
    CliArguments arguments = CliArguments.parse(new String[] {"Main.java"});

    assertThat(arguments.detectionOptions()).isEqualTo(DetectionOptions.defaults());
  }

  @Test
  void acceptsFileNamesStartingWithHyphenAfterOptionTerminator() {
    CliArguments arguments = CliArguments.parse(new String[] {"--", "-file.rb"});

    assertThat(arguments.files()).containsExactly(Path.of("-file.rb"));
  }

  @Test
  void rejectsMissingFilesUnknownOptionsAndBlankPaths() {
    assertThatThrownBy(() -> CliArguments.parse(new String[0]))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("At least one file is required");
    assertThatThrownBy(() -> CliArguments.parse(new String[] {"--unknown", "file.rb"}))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Unknown option: --unknown");
    assertThatThrownBy(() -> CliArguments.parse(new String[] {" "}))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("File path must not be blank");
  }
}
