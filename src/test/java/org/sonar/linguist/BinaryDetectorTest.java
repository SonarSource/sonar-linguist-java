/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class BinaryDetectorTest {

  @ParameterizedTest
  @MethodSource("unicodeTextBoms")
  void treatsUtf16AndUtf32BomsAsText(byte[] content) {
    assertThat(binary(content)).isFalse();
  }

  @Test
  void detectsNulsAndHighControlByteRatios() {
    assertThat(binary(new byte[] {'a', 0, 'b'})).isTrue();
    assertThat(binary(controlBytes(30))).isFalse();
    assertThat(binary(controlBytes(31))).isTrue();
    assertThat(binary(new byte[0])).isFalse();
  }

  @Test
  void inspectsOnlyTheBoundedPrefix() {
    byte[] content = new byte[BinaryDetector.SAMPLE_BYTES + 1];
    Arrays.fill(content, (byte) 'a');
    content[BinaryDetector.SAMPLE_BYTES] = 0;
    assertThat(binary(content)).isFalse();

    content[BinaryDetector.SAMPLE_BYTES - 1] = 0;
    assertThat(binary(content)).isTrue();
  }

  @Test
  void ignoresFilenameAndMimeConventions() {
    assertThat(BinaryDetector.isBinary(FileContent.copyOf("image.png", new byte[] {'t', 'e', 'x', 't'}))).isFalse();
    assertThat(BinaryDetector.isBinary(FileContent.copyOf("file.txt", new byte[] {'a', 0, 'b'}))).isTrue();
  }

  private static Stream<Arguments> unicodeTextBoms() {
    return Stream.of(
      Arguments.of((Object) new byte[] {(byte) 0xFF, (byte) 0xFE, 'a', 0}),
      Arguments.of((Object) new byte[] {(byte) 0xFE, (byte) 0xFF, 0, 'a'}),
      Arguments.of((Object) new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 0, 'a', 0, 0, 0}),
      Arguments.of((Object) new byte[] {0, 0, (byte) 0xFE, (byte) 0xFF, 0, 0, 0, 'a'}));
  }

  private static byte[] controlBytes(int controls) {
    byte[] content = new byte[100];
    Arrays.fill(content, (byte) 'a');
    Arrays.fill(content, 0, controls, (byte) 1);
    return content;
  }

  private static boolean binary(byte[] content) {
    return BinaryDetector.isBinary(FileContent.copyOf("file", content));
  }
}
