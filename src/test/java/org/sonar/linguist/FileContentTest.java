/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileContentTest {

  @Test
  void copiesBytesAndNormalizesPathSeparators() {
    byte[] source = "module Foo\nend\n".getBytes(StandardCharsets.UTF_8);

    FileContent content = FileContent.copyOf("lib\\foo.rb", source);
    source[0] = 'x';

    assertThat(content.path()).isEqualTo("lib/foo.rb");
    assertThat(content.bytes()).isEqualTo("module Foo\nend\n".getBytes(StandardCharsets.UTF_8));
    assertThat(content.byteSize()).isEqualTo(15);
    assertThat(content.empty()).isFalse();
  }

  @Test
  void readsContentOnceAndClosesTheStream() {
    AtomicInteger opens = new AtomicInteger();
    AtomicBoolean closed = new AtomicBoolean();
    ContentLoader loader = () -> {
      opens.incrementAndGet();
      return new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)) {
        @Override
        public void close() throws IOException {
          closed.set(true);
          super.close();
        }
      };
    };

    FileContent content = FileContent.read(Path.of("src", "Main.java"), loader);

    assertThat(content.bytes()).isEqualTo("content".getBytes(StandardCharsets.UTF_8));
    assertThat(opens).hasValue(1);
    assertThat(closed).isTrue();
  }

  @Test
  void wrapsContentReadFailures() {
    Path path = Path.of("src", "Main.java");
    ContentLoader loader = () -> {
      throw new IOException("boom");
    };

    assertThatThrownBy(() -> FileContent.read(path, loader))
      .isInstanceOf(UncheckedIOException.class)
      .hasMessage("Cannot read file: " + path)
      .hasRootCauseMessage("boom");
  }
}
