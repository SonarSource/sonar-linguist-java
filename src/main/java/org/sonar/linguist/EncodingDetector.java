/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Map;
import java.util.Optional;

final class EncodingDetector {
  private static final String TEXT = "text";
  private static final String BINARY = "binary";
  private static final Map<String, String> RUBY_ENCODINGS = Map.ofEntries(
    Map.entry("ISO-2022-CN", BINARY),
    Map.entry("ISO-2022-KR", BINARY),
    Map.entry("ISO-8859-8-I", BINARY),
    Map.entry("IBM424_rtl", BINARY),
    Map.entry("IBM424_ltr", BINARY),
    Map.entry("IBM420_rtl", BINARY),
    Map.entry("IBM420_ltr", BINARY),
    Map.entry("windows-1251", "Windows-1251"),
    Map.entry("windows-1256", "Windows-1256"));

  private EncodingDetector() {
  }

  static Optional<Result> detect(FileContent content, boolean binary) {
    if (binary || content.empty()) {
      return Optional.empty();
    }
    Optional<String> bomEncoding = bomEncoding(content);
    if (bomEncoding.isPresent()) {
      return valid(content, bomEncoding.orElseThrow())
        ? Optional.of(result(bomEncoding.orElseThrow(), 100))
        : Optional.empty();
    }
    CharsetMatch[] matches = new CharsetDetector().setText(content.bytes()).detectAll();
    for (CharsetMatch match : matches) {
      if (valid(content, match.getName())) {
        return Optional.of(result(match.getName(), match.getConfidence()));
      }
    }
    return Optional.empty();
  }

  private static Optional<String> bomEncoding(FileContent content) {
    if (content.length() >= 4) {
      if (startsWith(content, 0x00, 0x00, 0xFE, 0xFF)) {
        return Optional.of("UTF-32BE");
      }
      if (startsWith(content, 0xFF, 0xFE, 0x00, 0x00)) {
        return Optional.of("UTF-32LE");
      }
    }
    if (content.length() >= 3 && startsWith(content, 0xEF, 0xBB, 0xBF)) {
      return Optional.of(StandardCharsets.UTF_8.name());
    }
    if (content.length() >= 2) {
      if (startsWith(content, 0xFE, 0xFF)) {
        return Optional.of(StandardCharsets.UTF_16BE.name());
      }
      if (startsWith(content, 0xFF, 0xFE)) {
        return Optional.of(StandardCharsets.UTF_16LE.name());
      }
    }
    return Optional.empty();
  }

  private static boolean startsWith(FileContent content, int... bytes) {
    for (int index = 0; index < bytes.length; index++) {
      if (Byte.toUnsignedInt(content.byteAt(index)) != bytes[index]) {
        return false;
      }
    }
    return true;
  }

  private static boolean valid(FileContent content, String encoding) {
    try {
      Charset.forName(encoding).newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(content.bytes()));
      return true;
    } catch (CharacterCodingException e) {
      return false;
    } catch (UnsupportedCharsetException e) {
      return true;
    }
  }

  private static Result result(String encoding, int confidence) {
    return new Result(encoding, rubyEncoding(encoding), confidence, TEXT);
  }

  static String rubyEncoding(String encoding) {
    return RUBY_ENCODINGS.getOrDefault(encoding, encoding);
  }

  record Result(String encoding, String rubyEncoding, int confidence, String type) {
  }
}
