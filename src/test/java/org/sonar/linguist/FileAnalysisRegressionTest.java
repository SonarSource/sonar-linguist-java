/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class FileAnalysisRegressionTest {
  private final Linguist linguist = Linguist.create();

  @Test
  void reportsEncodingForUnicodeText() {
    byte[] content = "\uFEFFclass Main {}\n".getBytes(StandardCharsets.UTF_16LE);

    Detection detection = linguist.detect("Main.java", content);

    assertThat(detection.language()).map(Language::name).contains("Java");
    assertThat(detection.encoding()).contains("UTF-16LE");
    assertThat(detection.rubyEncoding()).contains("UTF-16LE");
    assertThat(detection.binary()).isFalse();
    assertThat(detection.text()).isTrue();
    assertThat(detection.lineCount()).isEqualTo(1);
  }

  @Test
  void retainsFactsWhenBinaryEmptyMalformedOrUnknownContentSkipsDetection() {
    Detection binary = linguist.detect("Main.java", new byte[] {'a', 0, 'b'});
    Detection empty = linguist.detect("Main.java", new byte[0]);
    Detection malformed = linguist.detect("Main.java", new byte[] {(byte) 0xFF, (byte) 0xFE, 'a'});
    Detection unknown = linguist.detect("notes.unknown", bytes("first\n\nthird\n"));

    assertThat(binary.language()).isEmpty();
    assertThat(binary.strategy()).isEqualTo(DetectionStrategy.NO_MATCH);
    assertThat(binary.binary()).isTrue();
    assertThat(binary.text()).isFalse();
    assertThat(binary.encoding()).isEmpty();
    assertThat(binary.lineCount()).isZero();

    assertThat(empty.language()).isEmpty();
    assertThat(empty.strategy()).isEqualTo(DetectionStrategy.NO_MATCH);
    assertThat(empty.empty()).isTrue();
    assertThat(empty.text()).isTrue();
    assertThat(empty.byteSize()).isZero();

    assertThat(malformed.language()).map(Language::name).contains("Java");
    assertThat(malformed.encoding()).isEmpty();
    assertThat(malformed.binary()).isFalse();

    assertThat(unknown.language()).isEmpty();
    assertThat(unknown.strategy()).isEqualTo(DetectionStrategy.NO_MATCH);
    assertThat(unknown.candidates()).isEmpty();
    assertThat(unknown.encoding()).isPresent();
    assertThat(unknown.encodingType()).contains("text");
    assertThat(unknown.lineCount()).isEqualTo(3);
    assertThat(unknown.sourceLineCount()).isEqualTo(2);
  }

  @Test
  void allowEmptyChangesOnlyLanguageDetection() {
    Detection rejected = linguist.detect("Main.java", new byte[0]);
    Detection allowed = linguist.detect("Main.java", new byte[0], new DetectionOptions(true));

    assertThat(allowed.language()).map(Language::name).contains("Java");
    assertThat(allowed.strategy()).isEqualTo(DetectionStrategy.EXTENSION);
    assertThat(allowed.empty()).isEqualTo(rejected.empty()).isTrue();
    assertThat(allowed.text()).isEqualTo(rejected.text()).isTrue();
    assertThat(allowed.byteSize()).isEqualTo(rejected.byteSize()).isZero();
  }

  @ParameterizedTest
  @MethodSource("pathFactCases")
  void reportsGeneratedMinifiedVendoredDocumentationAndLfsFactsIndependently(
    String path,
    String content,
    boolean generated,
    boolean vendored,
    boolean documentation,
    boolean lfsPointer) {
    Detection detection = linguist.detect(path, bytes(content));

    assertThat(detection)
      .extracting(Detection::generated, Detection::vendored, Detection::documentation, Detection::lfsPointer)
      .containsExactly(generated, vendored, documentation, lfsPointer);
  }

  private static Stream<Arguments> pathFactCases() {
    return Stream.of(
      Arguments.of("package-lock.json", "{}\n", true, false, false, false),
      Arguments.of("web/app.min.js", "x".repeat(111), true, true, false, false),
      Arguments.of("dist/app.js", "const value = 1;\n", false, true, false, false),
      Arguments.of("docs/Main.java", "class Main {}\n", false, false, true, false),
      Arguments.of("pointer", """
        version https://git-lfs.github.com/spec/v1
        oid sha256:0123456789abcdef
        size 123
        """, false, false, false, true),
      Arguments.of("web/app.min.js", "const value = 1;\n", false, true, false, false),
      Arguments.of("pointer", "version https://git-lfs.github.com/spec/v1\nsize unknown\n",
        false, false, false, false),
      Arguments.of("src/Main.java", "class Main {}\n", false, false, false, false));
  }

  private static byte[] bytes(String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }
}
