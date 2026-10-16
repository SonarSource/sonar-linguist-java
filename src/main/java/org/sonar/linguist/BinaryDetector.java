/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

final class BinaryDetector {
  static final int SAMPLE_BYTES = 8192;

  private BinaryDetector() {
  }

  static boolean isBinary(FileContent content) {
    int length = Math.min(content.length(), SAMPLE_BYTES);
    if (length == 0 || hasUnicodeTextBom(content, length)) {
      return false;
    }
    int controls = 0;
    for (int index = 0; index < length; index++) {
      int value = Byte.toUnsignedInt(content.byteAt(index));
      if (value == 0) {
        return true;
      }
      if (isControl(value)) {
        controls++;
      }
    }
    return controls * 10 > length * 3;
  }

  private static boolean hasUnicodeTextBom(FileContent content, int length) {
    if (length >= 4 && (startsWith(content, 0x00, 0x00, 0xFE, 0xFF)
        || startsWith(content, 0xFF, 0xFE, 0x00, 0x00))) {
      return true;
    }
    return length >= 2 && (startsWith(content, 0xFE, 0xFF) || startsWith(content, 0xFF, 0xFE));
  }

  private static boolean startsWith(FileContent content, int... bytes) {
    for (int index = 0; index < bytes.length; index++) {
      if (Byte.toUnsignedInt(content.byteAt(index)) != bytes[index]) {
        return false;
      }
    }
    return true;
  }

  private static boolean isControl(int value) {
    return (value < 32 && value != '\t' && value != '\n' && value != '\r' && value != '\f') || value == 127;
  }
}
