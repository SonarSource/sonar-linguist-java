/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

final class FileKindDetector {
  private static final Set<String> IMAGE_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".gif");
  private static final String LFS_VERSION = "version https://git-lfs.github.com/spec/v1\n";
  private static final byte[] LFS_VERSION_BYTES = LFS_VERSION.getBytes(StandardCharsets.US_ASCII);

  private FileKindDetector() {
  }

  static Result detect(FileContent content, boolean binary) {
    String extension = extension(content.path());
    boolean text = !binary;
    return new Result(
      content.empty(),
      text,
      IMAGE_EXTENSIONS.contains(extension),
      extension.equals(".stl"),
      text && extension.equals(".csv"),
      extension.equals(".pdf"),
      isLfsPointer(content));
  }

  private static String extension(String path) {
    String filename = path.substring(path.lastIndexOf('/') + 1);
    int dot = filename.lastIndexOf('.');
    return dot <= 0 || dot == filename.length() - 1 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
  }

  private static boolean isLfsPointer(FileContent content) {
    if (!startsWith(content, LFS_VERSION_BYTES)) {
      return false;
    }
    String data = new String(content.bytes(), StandardCharsets.ISO_8859_1);
    if (!data.contains("\noid sha256:")) {
      return false;
    }
    int end = data.endsWith("\n") ? (data.length() - 1) : data.length();
    int sizeLine = data.lastIndexOf("\nsize ", end - 1);
    int digits = sizeLine + "\nsize ".length();
    if (sizeLine < 0 || digits == end) {
      return false;
    }
    for (int index = digits; index < end; index++) {
      if (data.charAt(index) < '0' || data.charAt(index) > '9') {
        return false;
      }
    }
    return true;
  }

  private static boolean startsWith(FileContent content, byte[] prefix) {
    if (content.length() < prefix.length) {
      return false;
    }
    for (int index = 0; index < prefix.length; index++) {
      if (content.byteAt(index) != prefix[index]) {
        return false;
      }
    }
    return true;
  }

  record Result(
    boolean empty,
    boolean text,
    boolean image,
    boolean solid,
    boolean csv,
    boolean pdf,
    boolean lfsPointer) {
  }
}
