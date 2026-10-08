/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CliOutputTest {
  private static final Language JAVA = new Language(181, "Java", LanguageType.PROGRAMMING);

  @Test
  void writesDeterministicJsonLineWithDiagnosticsAndAllFileFacts() {
    Detection detection = Detection.builder("src/quoted\"name.java")
      .language(JAVA)
      .strategy(DetectionStrategy.CLASSIFIER)
      .candidates(List.of(JAVA))
      .classifierScores(Map.of(JAVA, 0.75))
      .byteSize(123)
      .encoding("UTF-8", "UTF-8", 100, "text")
      .binary(false)
      .empty(false)
      .text(true)
      .fileKinds(true, true, true, true, true)
      .generated(true)
      .vendored(true)
      .documentation(true)
      .lines(12, 8)
      .build();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream platformDependentOutput = new PrintStream(bytes, true, StandardCharsets.UTF_8) {
      @Override
      public void println(String value) {
        print(value + "\r\n");
      }
    };

    CliOutput.write(List.of(detection), platformDependentOutput);

    assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("""
      {"path":"src/quoted\\\"name.java",\
      "language":{"id":181,"name":"Java","type":"PROGRAMMING"},\
      "strategy":"Classifier",\
      "diagnostics":{\
      "candidates":[{"id":181,"name":"Java","type":"PROGRAMMING"}],\
      "classifierScores":[{\
      "language":{"id":181,"name":"Java","type":"PROGRAMMING"},"score":0.75}]},\
      "facts":{\
      "extension":".java","byteSize":123,"encoding":"UTF-8","rubyEncoding":"UTF-8",\
      "encodingConfidence":100,"encodingType":"text",\
      "binary":false,"empty":false,"text":true,"image":true,"solid":true,"csv":true,"pdf":true,\
      "lfsPointer":true,"generated":true,"vendored":true,"documentation":true,\
      "lineCount":12,"sourceLineCount":8,"textMateScope":"none"}}
      """);
  }

  @Test
  void writesNullsAndEmptyDiagnosticsForUnknownLanguage() {
    Detection detection = Detection.builder("unknown").build();

    assertThat(CliOutput.format(detection)).contains(
      "\"language\":null",
      "\"strategy\":\"No match\"",
      "\"candidates\":[]",
      "\"classifierScores\":[]",
      "\"encoding\":null",
      "\"textMateScope\":null");
  }
}
