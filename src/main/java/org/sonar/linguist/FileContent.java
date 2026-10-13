/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/** Immutable per-detection snapshot of a file path and its complete bytes. */
final class FileContent {
  private final String path;
  private final byte[] bytes;

  private FileContent(String path, byte[] bytes) {
    this.path = path;
    this.bytes = bytes;
  }

  static FileContent read(Path relativePath, ContentLoader contentLoader) {
    try (InputStream input = contentLoader.open()) {
      return new FileContent(normalize(relativePath.toString()), input.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read file: " + relativePath, e);
    }
  }

  static FileContent copyOf(String path, byte[] bytes) {
    return new FileContent(normalize(path), bytes.clone());
  }

  String path() {
    return path;
  }

  int length() {
    return bytes.length;
  }

  byte byteAt(int index) {
    return bytes[index];
  }

  byte[] bytes() {
    return bytes;
  }

  long byteSize() {
    return bytes.length;
  }

  boolean empty() {
    return bytes.length == 0;
  }

  private static String normalize(String path) {
    return path.replace('\\', '/');
  }
}
