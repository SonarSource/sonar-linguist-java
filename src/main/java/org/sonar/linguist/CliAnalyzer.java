/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.file.Files;
import java.util.List;
import java.util.Objects;

final class CliAnalyzer {
  private final Linguist linguist;

  CliAnalyzer(Linguist linguist) {
    this.linguist = Objects.requireNonNull(linguist, "linguist");
  }

  List<Detection> analyze(CliArguments arguments) {
    Objects.requireNonNull(arguments, "arguments");
    return arguments.files().stream()
      .map(file -> linguist.detect(file, () -> Files.newInputStream(file), arguments.detectionOptions()))
      .toList();
  }
}
