/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

final class CliOutput {
  private CliOutput() {}

  static void write(List<Detection> detections, PrintStream output) {
    Objects.requireNonNull(detections, "detections");
    Objects.requireNonNull(output, "output");
    detections.forEach(detection -> output.print(format(detection) + "\n"));
  }

  static String format(Detection detection) {
    Objects.requireNonNull(detection, "detection");
    return object(
      field("path", string(detection.path())),
      field("language", detection.language().map(CliOutput::language).orElse("null")),
      field("strategy", string(detection.strategy().upstreamName())),
      field("diagnostics", object(
        field("candidates", array(detection.candidates().stream().map(CliOutput::language).toList())),
        field("classifierScores", classifierScores(detection.classifierScores())))),
      field("facts", object(
        field("extension", string(detection.extension())),
        field("byteSize", Long.toString(detection.byteSize())),
        field("encoding", nullableString(detection.encoding().orElse(null))),
        field("rubyEncoding", nullableString(detection.rubyEncoding().orElse(null))),
        field("encodingConfidence", detection.encodingConfidence().map(String::valueOf).orElse("null")),
        field("encodingType", nullableString(detection.encodingType().orElse(null))),
        field("binary", Boolean.toString(detection.binary())),
        field("empty", Boolean.toString(detection.empty())),
        field("text", Boolean.toString(detection.text())),
        field("image", Boolean.toString(detection.image())),
        field("solid", Boolean.toString(detection.solid())),
        field("csv", Boolean.toString(detection.csv())),
        field("pdf", Boolean.toString(detection.pdf())),
        field("lfsPointer", Boolean.toString(detection.lfsPointer())),
        field("generated", Boolean.toString(detection.generated())),
        field("vendored", Boolean.toString(detection.vendored())),
        field("documentation", Boolean.toString(detection.documentation())),
        field("lineCount", Integer.toString(detection.lineCount())),
        field("sourceLineCount", Integer.toString(detection.sourceLineCount())),
        field("textMateScope", nullableString(detection.textMateScope().orElse(null))))));
  }

  private static String language(Language language) {
    return object(
      field("id", Integer.toString(language.languageId())),
      field("name", string(language.name())),
      field("type", string(language.type().name())));
  }

  private static String classifierScores(Map<Language, Double> scores) {
    return array(scores.entrySet().stream()
      .map(entry -> object(
        field("language", language(entry.getKey())),
        field("score", Double.toString(entry.getValue()))))
      .toList());
  }

  private static String nullableString(@Nullable String value) {
    return value == null ? "null" : string(value);
  }

  private static String object(String... fields) {
    return "{" + String.join(",", fields) + "}";
  }

  private static String array(List<String> values) {
    return values.stream().collect(Collectors.joining(",", "[", "]"));
  }

  private static String field(String name, String value) {
    return string(name) + ":" + value;
  }

  private static String string(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append("\\u%04x".formatted((int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.append('"').toString();
  }
}
