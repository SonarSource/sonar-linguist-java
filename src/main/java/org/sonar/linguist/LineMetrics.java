/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import javax.annotation.Nullable;

final class LineMetrics {
  private LineMetrics() {
  }

  static Result calculate(FileContent content, boolean text, @Nullable EncodingDetector.Result encoding) {
    if (!text || content.empty()) {
      return new Result(0, 0);
    }
    Optional<Newlines> newlines = Optional.ofNullable(encoding).flatMap(LineMetrics::newlines);
    if (newlines.isEmpty()) {
      return new Result(1, containsNonWhitespace(content, 0, content.length()) ? 1 : 0);
    }

    Newlines lineEndings = newlines.orElseThrow();
    int end = chomp(content, lineEndings);
    if (end == 0) {
      return new Result(0, 0);
    }
    int lineStart = 0;
    int total = 0;
    int source = 0;
    while (lineStart <= end) {
      Match match = findNext(content, lineStart, end, lineEndings);
      int lineEnd = match == null ? end : match.index();
      total++;
      if (containsNonWhitespace(content, lineStart, lineEnd)) {
        source++;
      }
      if (match == null) {
        break;
      }
      lineStart = match.index() + match.length();
    }
    return new Result(total, source);
  }

  private static Optional<Newlines> newlines(EncodingDetector.Result encoding) {
    try {
      Charset charset = encoding.rubyEncoding().equals("binary")
        ? StandardCharsets.ISO_8859_1
        : Charset.forName(encoding.rubyEncoding());
      return Optional.of(new Newlines(encode(charset, "\r\n"), encode(charset, "\r"), encode(charset, "\n")));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static byte[] encode(Charset charset, String value) {
    ByteBuffer encoded = charset.encode(value);
    byte[] bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    return bytes;
  }

  private static int chomp(FileContent content, Newlines newlines) {
    int end = content.length();
    if (endsWith(content, end, newlines.crlf())) {
      return end - newlines.crlf().length;
    }
    if (endsWith(content, end, newlines.lf())) {
      return end - newlines.lf().length;
    }
    if (endsWith(content, end, newlines.cr())) {
      return end - newlines.cr().length;
    }
    return end;
  }

  private static Match findNext(FileContent content, int from, int end, Newlines newlines) {
    for (int index = from; index < end; index++) {
      if (matches(content, index, end, newlines.crlf())) {
        return new Match(index, newlines.crlf().length);
      }
      if (matches(content, index, end, newlines.cr())) {
        return new Match(index, newlines.cr().length);
      }
      if (matches(content, index, end, newlines.lf())) {
        return new Match(index, newlines.lf().length);
      }
    }
    return null;
  }

  private static boolean matches(FileContent content, int index, int end, byte[] expected) {
    if (index + expected.length > end) {
      return false;
    }
    for (int offset = 0; offset < expected.length; offset++) {
      if (content.byteAt(index + offset) != expected[offset]) {
        return false;
      }
    }
    return true;
  }

  private static boolean endsWith(FileContent content, int end, byte[] expected) {
    return end >= expected.length && matches(content, end - expected.length, end, expected);
  }

  private static boolean containsNonWhitespace(FileContent content, int start, int end) {
    for (int index = start; index < end; index++) {
      if (!isWhitespace(content.byteAt(index))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isWhitespace(byte value) {
    return value == ' ' || value == '\t' || value == '\r' || value == '\n' || value == '\f' || value == 0x0B;
  }

  record Result(int lineCount, int sourceLineCount) {
  }

  private record Newlines(byte[] crlf, byte[] cr, byte[] lf) {
    @Override
    public boolean equals(Object object) {
      if (this == object) {
        return true;
      }
      if (!(object instanceof Newlines(byte[] otherCrlf, byte[] otherCr, byte[] otherLf))) {
        return false;
      }
      return Arrays.equals(crlf, otherCrlf)
        && Arrays.equals(cr, otherCr)
        && Arrays.equals(lf, otherLf);
    }

    @Override
    public int hashCode() {
      int result = Arrays.hashCode(crlf);
      result = 31 * result + Arrays.hashCode(cr);
      return 31 * result + Arrays.hashCode(lf);
    }

    @Override
    public String toString() {
      return "Newlines[crlf=%s, cr=%s, lf=%s]".formatted(
        Arrays.toString(crlf), Arrays.toString(cr), Arrays.toString(lf));
    }
  }

  private record Match(int index, int length) {
  }
}
