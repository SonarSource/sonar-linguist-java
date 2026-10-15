/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class CliAnalyzerTest {
  @TempDir
  Path temporary;

  @Test
  void analyzesExplicitFilesThroughPublicDetectionPipeline() throws Exception {
    Path java = temporary.resolve("Main.java");
    Path python = temporary.resolve("script.py");
    Files.writeString(java, "class Main {}\n");
    Files.writeString(python, "print('hello')\n");
    CliArguments arguments = CliArguments.parse(new String[] {java.toString(), python.toString()});
    Linguist linguist = Linguist.create();

    assertThat(new CliAnalyzer(linguist).analyze(arguments)).containsExactly(
      linguist.detect(java, () -> Files.newInputStream(java), DetectionOptions.defaults()),
      linguist.detect(python, () -> Files.newInputStream(python), DetectionOptions.defaults()));
  }

  @Test
  void forwardsDetectionOptions() throws Exception {
    Path empty = temporary.resolve("Empty.java");
    Files.createFile(empty);

    Detection detection = new CliAnalyzer(Linguist.create()).analyze(
      CliArguments.parse(new String[] {"--allow-empty", empty.toString()})).getFirst();

    assertThat(detection.empty()).isTrue();
    assertThat(detection.language()).map(Language::name).contains("Java");
  }
}
