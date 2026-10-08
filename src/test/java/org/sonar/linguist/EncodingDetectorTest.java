/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EncodingDetectorTest {

  @Test
  void detectsUtf8Deterministically() {
    EncodingDetector.Result result = detect("Zażółć gęślą jaźń".getBytes(StandardCharsets.UTF_8));

    assertThat(result.encoding()).isEqualTo("UTF-8");
    assertThat(result.rubyEncoding()).isEqualTo("UTF-8");
    assertThat(result.confidence()).isEqualTo(100);
    assertThat(result.type()).isEqualTo("text");
  }

  @Test
  void detectsUtf16AndUtf32FromBoms() {
    assertThat(detect(new byte[] {(byte) 0xFF, (byte) 0xFE, 'a', 0}).encoding()).isEqualTo("UTF-16LE");
    assertThat(detect(new byte[] {(byte) 0xFE, (byte) 0xFF, 0, 'a'}).encoding()).isEqualTo("UTF-16BE");
    assertThat(detect(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 0, 'a', 0, 0, 0}).encoding()).isEqualTo("UTF-32LE");
    assertThat(detect(new byte[] {0, 0, (byte) 0xFE, (byte) 0xFF, 0, 0, 0, 'a'}).encoding()).isEqualTo("UTF-32BE");
  }

  @Test
  void preservesUpstreamEncodingWithoutPinningImplementationDependentIcuConfidence() {
    byte[] content = "README\n=======\n".getBytes(StandardCharsets.US_ASCII);

    EncodingDetector.Result result = detect(content);

    assertThat(result.encoding()).isEqualTo("ISO-8859-2");
    assertThat(result.rubyEncoding()).isEqualTo("ISO-8859-2");
    assertThat(result.confidence()).isBetween(1, 100);
    assertThat(result.type()).isEqualTo("text");
  }

  @Test
  void handlesMalformedAndUnavailableInputWithoutThrowing() {
    FileContent malformedUtf16 = FileContent.copyOf("file", new byte[] {(byte) 0xFF, (byte) 0xFE, 'a'});

    assertThat(EncodingDetector.detect(malformedUtf16, false)).isEmpty();
    assertThat(EncodingDetector.detect(FileContent.copyOf("empty", new byte[0]), false)).isEmpty();
    assertThat(EncodingDetector.detect(FileContent.copyOf("binary", new byte[] {'a', 0, 'b'}), true)).isEmpty();
  }

  @Test
  void validatesEncodingAgainstCompleteContentBeyondTheBinarySample() {
    byte[] content = new byte[BinaryDetector.SAMPLE_BYTES + 2];
    Arrays.fill(content, (byte) 'a');
    content[0] = (byte) 0xEF;
    content[1] = (byte) 0xBB;
    content[2] = (byte) 0xBF;
    content[content.length - 1] = (byte) 0xC3;

    assertThat(BinaryDetector.isBinary(FileContent.copyOf("file", content))).isFalse();
    assertThat(EncodingDetector.detect(FileContent.copyOf("file", content), false)).isEmpty();
  }

  @Test
  void mapsIcuEncodingNamesToRubyNames() {
    assertThat(EncodingDetector.rubyEncoding("ISO-2022-KR")).isEqualTo("binary");
    assertThat(EncodingDetector.rubyEncoding("IBM424_rtl")).isEqualTo("binary");
    assertThat(EncodingDetector.rubyEncoding("windows-1251")).isEqualTo("Windows-1251");
    assertThat(EncodingDetector.rubyEncoding("UTF-8")).isEqualTo("UTF-8");
  }

  private static EncodingDetector.Result detect(byte[] content) {
    return EncodingDetector.detect(FileContent.copyOf("file", content), false).orElseThrow();
  }
}
