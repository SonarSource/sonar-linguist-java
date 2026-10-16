/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileKindDetectorTest {

  @Test
  void detectsEmptyAndTextFactsFromContent() {
    assertThat(detect("empty", new byte[0], false))
      .extracting(FileKindDetector.Result::empty, FileKindDetector.Result::text)
      .containsExactly(true, true);
    assertThat(detect("binary", new byte[] {'a', 0, 'b'}, true))
      .extracting(FileKindDetector.Result::empty, FileKindDetector.Result::text)
      .containsExactly(false, false);
  }

  @Test
  void detectsRetainedKindsByFinalExtension() {
    assertThat(detect("image.PNG", text("not an image"), false).image()).isTrue();
    assertThat(detect("image.jpeg", text("content"), false).image()).isTrue();
    assertThat(detect("model.STL", text("solid model"), false).solid()).isTrue();
    assertThat(detect("document.PDF", new byte[] {'a', 0, 'b'}, true).pdf()).isTrue();
    assertThat(detect("archive.png.gz", text("content"), false).image()).isFalse();
    assertThat(detect(".png", text("content"), false).image()).isFalse();
  }

  @Test
  void detectsCsvOnlyForTextContent() {
    assertThat(detect("data.CSV", text("a,b\n1,2\n"), false).csv()).isTrue();
    assertThat(detect("data.csv", new byte[] {'a', 0, 'b'}, true).csv()).isFalse();
  }

  @Test
  void detectsGitLfsPointers() {
    assertThat(detect("pointer", text("""
      version https://git-lfs.github.com/spec/v1
      oid sha256:0123456789abcdef
      size 123
      """), false).lfsPointer()).isTrue();
    assertThat(detect("pointer", text("""
      version https://git-lfs.github.com/spec/v1
      oid sha256:0123456789abcdef
      size 123"""), false).lfsPointer()).isTrue();
  }

  @Test
  void rejectsMalformedGitLfsPointers() {
    assertThat(detect("pointer", text("oid sha256:abc\nsize 1\n"), false).lfsPointer()).isFalse();
    assertThat(detect(
      "pointer", text("version https://git-lfs.github.com/spec/v1\nsize 1\n"), false).lfsPointer()).isFalse();
    assertThat(detect("pointer", text("""
      version https://git-lfs.github.com/spec/v1
      oid sha256:abc
      size unknown
      """), false).lfsPointer()).isFalse();
    assertThat(detect("pointer", text("""
      version https://git-lfs.github.com/spec/v1
      oid sha256:abc
      size 1
      trailing
      """), false).lfsPointer()).isFalse();
  }

  private static FileKindDetector.Result detect(String path, byte[] content, boolean binary) {
    return FileKindDetector.detect(FileContent.copyOf(path, content), binary);
  }

  private static byte[] text(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
