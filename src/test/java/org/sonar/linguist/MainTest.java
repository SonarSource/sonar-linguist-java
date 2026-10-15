/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {
  @TempDir
  Path temporary;

  @Test
  void returnsSuccessAndWritesAnalysis() throws Exception {
    Path file = temporary.resolve("Main.java");
    Files.writeString(file, "class Main {}\n");
    Streams streams = new Streams();

    int exitCode = Main.run(new String[] {file.toString()}, streams.output(), streams.error());

    assertThat(exitCode).isZero();
    assertThat(streams.outputText()).contains(
      "\"path\":\"" + file.toString().replace('\\', '/') + "\"",
      "\"name\":\"Java\"");
    assertThat(streams.errorText()).isEmpty();
  }

  @Test
  void writesOneJsonLinePerFileInArgumentOrder() throws Exception {
    Path java = temporary.resolve("Main.java");
    Path python = temporary.resolve("script.py");
    Files.writeString(java, "class Main {}\n");
    Files.writeString(python, "print('hello')\n");
    Streams streams = new Streams();

    int exitCode = Main.run(new String[] {java.toString(), python.toString()}, streams.output(), streams.error());

    assertThat(exitCode).isZero();
    String[] lines = streams.outputText().split("\n", -1);
    assertThat(lines).hasSize(3);
    assertThat(lines[0]).contains(
      "\"path\":\"" + java.toString().replace('\\', '/') + "\"",
      "\"name\":\"Java\"");
    assertThat(lines[1]).contains(
      "\"path\":\"" + python.toString().replace('\\', '/') + "\"",
      "\"name\":\"Python\"");
    assertThat(lines[2]).isEmpty();
    assertThat(streams.outputText()).doesNotContain("\r");
    assertThat(streams.errorText()).isEmpty();
  }

  @Test
  void allowsEmptyFilesWhenOptionIsSet() throws Exception {
    Path file = temporary.resolve("Empty.java");
    Files.createFile(file);
    Streams streams = new Streams();

    int exitCode = Main.run(new String[] {"--allow-empty", file.toString()}, streams.output(), streams.error());

    assertThat(exitCode).isZero();
    assertThat(streams.outputText()).contains(
      "\"path\":\"" + file.toString().replace('\\', '/') + "\"",
      "\"name\":\"Java\"",
      "\"strategy\":\"Extension\"",
      "\"empty\":true");
    assertThat(streams.errorText()).isEmpty();
  }

  @Test
  void reportsInvalidArgumentsWithUsageExitCode() {
    Streams streams = new Streams();

    int exitCode = Main.run(new String[] {"--unknown"}, streams.output(), streams.error());

    assertThat(exitCode).isEqualTo(2);
    assertThat(streams.outputText()).isEmpty();
    assertThat(streams.errorText()).isEqualTo(
      "error: Unknown option: --unknown" + System.lineSeparator()
        + "Usage: linguist [--allow-empty] [--] <file>..." + System.lineSeparator());
  }

  @Test
  void reportsUnreadableFilesWithoutPartialOutput() throws Exception {
    Path readable = temporary.resolve("Main.java");
    Files.writeString(readable, "class Main {}\n");
    Path missing = temporary.resolve("missing.java");
    Streams streams = new Streams();

    int exitCode = Main.run(
      new String[] {readable.toString(), missing.toString()}, streams.output(), streams.error());

    assertThat(exitCode).isEqualTo(1);
    assertThat(streams.outputText()).isEmpty();
    assertThat(streams.errorText()).isEqualTo("error: Cannot read file: " + missing + System.lineSeparator());
  }

  @Test
  void doesNotApplyRepositoryAttributesToExplicitFiles() throws Exception {
    Path repository = temporary.resolve("repo");
    Path file = repository.resolve("Main.java");
    try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
      Files.writeString(file, "class Main {}\n");
      Files.writeString(repository.resolve(".gitattributes"), "Main.java linguist-language=Python\n");
      git.add().addFilepattern(".").call();
      git.commit().setSign(false).setMessage("Fixture").setAuthor("Test", "test@example.com")
        .setCommitter("Test", "test@example.com").call();
      assertThat(Linguist.create().analyzeRepository(repository).includedFiles().get("Main.java").name())
        .isEqualTo("Python");
      Streams streams = new Streams();

      int exitCode = Main.run(new String[] {file.toString()}, streams.output(), streams.error());

      assertThat(exitCode).isZero();
      assertThat(streams.outputText()).contains("\"name\":\"Java\"").doesNotContain("\"name\":\"Python\"");
      assertThat(streams.errorText()).isEmpty();
    }
  }

  @Test
  void doesNotTraverseRepositoryDirectories() throws Exception {
    Path repository = temporary.resolve("repo");
    try (Git ignored = Git.init().setDirectory(repository.toFile()).call()) {
      Files.writeString(repository.resolve("Main.java"), "class Main {}\n");
      Streams streams = new Streams();

      int exitCode = Main.run(new String[] {repository.toString()}, streams.output(), streams.error());

      assertThat(exitCode).isEqualTo(1);
      assertThat(streams.outputText()).isEmpty();
      assertThat(streams.errorText()).isEqualTo("error: Cannot read file: " + repository + System.lineSeparator());
    }
  }

  @Test
  void reportsAnalysisFailuresWithoutStackTrace() {
    Streams streams = new Streams();

    int exitCode = Main.run(
      new String[] {"file.rb"},
      streams.output(),
      streams.error(),
      arguments -> throwFailure());

    assertThat(exitCode).isEqualTo(1);
    assertThat(streams.outputText()).isEmpty();
    assertThat(streams.errorText()).isEqualTo("error: detection failed" + System.lineSeparator());
  }

  private static java.util.List<Detection> throwFailure() {
    throw new IllegalStateException("detection failed");
  }

  private static final class Streams {
    private final ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
    private final PrintStream output = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);
    private final PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8);

    PrintStream output() {
      return output;
    }

    PrintStream error() {
      return error;
    }

    String outputText() {
      return outputBytes.toString(StandardCharsets.UTF_8);
    }

    String errorText() {
      return errorBytes.toString(StandardCharsets.UTF_8);
    }
  }
}
