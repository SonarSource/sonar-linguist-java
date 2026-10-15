/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LineMetricsTest {

  @Test
  void countsUnixWindowsAndClassicMacLines() {
    assertThat(calculate("first\nsecond\r\nthird\rfourth\n"))
      .extracting(LineMetrics.Result::lineCount, LineMetrics.Result::sourceLineCount)
      .containsExactly(4, 4);
  }

  @Test
  void excludesAsciiWhitespaceOnlyLinesFromSourceCount() {
    assertThat(calculate("first\n \t\f\u000b\nthird\n"))
      .extracting(LineMetrics.Result::lineCount, LineMetrics.Result::sourceLineCount)
      .containsExactly(3, 2);
  }

  @Test
  void returnsZeroCountsForEmptyAndBinaryContent() {
    assertThat(calculate(new byte[0], true, null))
      .extracting(LineMetrics.Result::lineCount, LineMetrics.Result::sourceLineCount)
      .containsExactly(0, 0);
    assertThat(calculate(new byte[] {'a', 0, 'b'}, false, null))
      .extracting(LineMetrics.Result::lineCount, LineMetrics.Result::sourceLineCount)
      .containsExactly(0, 0);
  }

  @Test
  void countsNewlinesInDetectedUtf16Encoding() {
    byte[] content = "\uFEFFfirst\r\nsecond\r\nthird".getBytes(StandardCharsets.UTF_16LE);
    EncodingDetector.Result encoding = new EncodingDetector.Result("UTF-16LE", "UTF-16LE", 100, "text");

    assertThat(calculate(content, true, encoding))
      .extracting(LineMetrics.Result::lineCount, LineMetrics.Result::sourceLineCount)
      .containsExactly(3, 3);
  }

  @Test
  void countsLinesAcrossCompleteContentWithoutAGlobalSizeLimit() {
    String content = "a".repeat(128 * 1024) + "\nsecond\n";

    assertThat(calculate(content))
      .extracting(LineMetrics.Result::lineCount, LineMetrics.Result::sourceLineCount)
      .containsExactly(2, 2);
  }

  private static LineMetrics.Result calculate(String content) {
    return calculate(content.getBytes(StandardCharsets.UTF_8), true, utf8());
  }

  private static LineMetrics.Result calculate(
    byte[] content,
    boolean text,
    EncodingDetector.Result encoding) {
    return LineMetrics.calculate(FileContent.copyOf("file.txt", content), text, encoding);
  }

  private static EncodingDetector.Result utf8() {
    return new EncodingDetector.Result("UTF-8", "UTF-8", 100, "text");
  }
}
