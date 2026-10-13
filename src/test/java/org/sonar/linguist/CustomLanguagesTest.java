/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomLanguagesTest {
  @Test
  void exposesCompleteMetadataForCustomLanguages() {
    Linguist custom = create("""
        Example:
          language_id: -1
          type: programming
          aliases: [example-alias]
          extensions: [.example, .example2]
          filenames: [Examplefile]
          interpreters: [z-example, a-example]
          fs_name: ExampleFS
          color: "#123456"
          group: Java
          tm_scope: source.example
          ace_mode: example
          codemirror_mode: example-mode
          codemirror_mime_type: text/x-example
          wrap: true
          searchable: false
        """);

    Detection detection = custom.detect("file.example", content());
    Language language = detection.language().orElseThrow();
    assertThat(detection.strategy()).isEqualTo(DetectionStrategy.EXTENSION);
    assertThat(detection.textMateScope()).contains("source.example");
    assertThat(language.aliases()).containsExactly("example", "example-alias");
    assertThat(language.extensions()).containsExactly(".example", ".example2");
    assertThat(language.filenames()).containsExactly("Examplefile");
    assertThat(language.interpreters()).containsExactly("a-example", "z-example");
    assertThat(language.filesystemName()).contains("ExampleFS");
    assertThat(language.color()).contains("#123456");
    assertThat(language.group().name()).isEqualTo("Java");
    assertThat(language.tmScope()).isEqualTo("source.example");
    assertThat(language.aceMode()).isEqualTo("example");
    assertThat(language.codeMirrorMode()).contains("example-mode");
    assertThat(language.codeMirrorMimeType()).contains("text/x-example");
    assertThat(language.wrap()).isTrue();
    assertThat(language.popular()).isFalse();
  }

  @Test
  void addsLanguageAndKeepsUnchangedBuiltInsAndPathRules() {
    Linguist custom = create("""
        Example:
          language_id: -1
          type: programming
          extensions: [.example]
          filenames: [Examplefile]
          aliases: [ex]
        """);
    Language expected = new Language(-1, "Example", LanguageType.PROGRAMMING);
    Detection extension = custom.detect("file.example", content());
    assertThat(extension.language()).contains(expected);
    assertThat(extension.strategy()).isEqualTo(DetectionStrategy.EXTENSION);
    Detection filename = custom.detect("Examplefile", content());
    assertThat(filename.language()).contains(expected);
    assertThat(filename.strategy()).isEqualTo(DetectionStrategy.FILENAME);
    assertThat(custom.detect("Main.java", content())).isEqualTo(Linguist.create().detect("Main.java", content()));
    assertThat(custom.detect("node_modules/file.example", content()).vendored()).isTrue();
    assertThat(Linguist.create().detect("file.example", content()).language()).isEmpty();
    assertThat(create("").detect("file.example", content()).language()).isEmpty();
  }

  @Test
  void isolatesCustomCatalogsFromOtherInstancesAndBundledCatalog() {
    Linguist replaced = create("""
        Renamed Docker:
          language_id: %d
          type: programming
          extensions: [.replaced]
        """.formatted(builtInId("Dockerfile")));
    Linguist added = create("""
        Added:
          language_id: -1
          type: programming
          extensions: [.added]
        """);
    Linguist bundled = Linguist.create();

    assertThat(replaced.detect("file.replaced", content()).language()).map(Language::name).contains("Renamed Docker");
    assertThat(replaced.detect("Dockerfile", content()).language()).isEmpty();
    assertThat(replaced.detect("file.added", content()).language()).isEmpty();
    assertThat(added.detect("file.added", content()).language()).map(Language::name).contains("Added");
    assertThat(added.detect("file.replaced", content()).language()).isEmpty();
    assertThat(added.detect("Dockerfile", content()).language()).map(Language::name).contains("Dockerfile");
    assertThat(bundled.detect("file.replaced", content()).language()).isEmpty();
    assertThat(bundled.detect("file.added", content()).language()).isEmpty();
    assertThat(bundled.detect("Dockerfile", content()).language()).map(Language::name).contains("Dockerfile");
  }

  @Test
  void usesCustomAliasesInModelines() {
    Linguist custom = create("""
        Example:
          language_id: -1
          type: programming
          aliases: [examplemode]
        """);

    Detection detection = custom.detect("file.txt", "# vim: ft=examplemode\n".getBytes(StandardCharsets.UTF_8));

    assertThat(detection.language()).map(Language::name).contains("Example");
    assertThat(detection.strategy()).isEqualTo(DetectionStrategy.MODELINE);
  }

  @Test
  void usesCustomInterpretersInShebangs() {
    Linguist custom = create("""
        Example:
          language_id: -1
          type: programming
          interpreters: [example-run]
        """);

    Detection detection = custom.detect("script", "#!/usr/bin/env example-run\n".getBytes(StandardCharsets.UTF_8));

    assertThat(detection.language()).map(Language::name).contains("Example");
    assertThat(detection.strategy()).isEqualTo(DetectionStrategy.SHEBANG);
  }

  @Test
  void preservesUntrainedCustomInterpreterAmbiguityAndGrouping() {
    Linguist custom = create("""
        Parent Custom:
          language_id: -1
          type: programming
          interpreters: [custom-run]
        Child Custom:
          language_id: -2
          type: programming
          interpreters: [custom-run]
          group: Parent Custom
        """);

    Detection detection = custom.detect("script", "#!/usr/bin/env custom-run\n".getBytes(StandardCharsets.UTF_8));

    assertThat(detection.language()).map(Language::name).contains("Parent Custom");
    assertThat(detection.strategy()).isEqualTo(DetectionStrategy.SHEBANG);
    assertThat(detection.candidates()).extracting(Language::name)
      .containsExactly("Parent Custom", "Child Custom");
    assertThat(detection.classifierScores()).isEmpty();
    Language parent = detection.candidates().get(0);
    Language child = detection.candidates().get(1);
    assertThat(parent.group()).isSameAs(parent);
    assertThat(child.group()).isSameAs(parent);
  }

  @Test
  void replacesWholeDefinitionByIdIncludingNameAndOldMappings() {
    LanguageCatalog catalog = new LanguageCatalog(input("""
        Renamed Docker:
          language_id: %d
          type: data
          extensions: [.customdocker]
          filenames: [CustomDockerfile]
          aliases: [custom-docker]
        """.formatted(builtInId("Dockerfile"))));
    Language expected = new Language(builtInId("Dockerfile"), "Renamed Docker", LanguageType.DATA);
    assertThat(catalog.detect("file.customdocker")).contains(expected);
    assertThat(catalog.detect("CustomDockerfile")).contains(expected);
    assertThat(catalog.byAlias("custom-docker")).contains(expected);
    assertThat(catalog.byAlias("Renamed Docker")).contains(expected);
    assertThat(catalog.detect("Dockerfile")).isEmpty();
    assertThat(catalog.detect("Containerfile")).isEmpty();
    assertThat(catalog.detect("file.dockerfile")).isEmpty();
    assertThat(catalog.byAlias("Dockerfile")).isEmpty();
    assertThat(LanguageCatalog.group(expected)).isEqualTo(expected);
    assertThat(Linguist.create().detect("Dockerfile", content()).language()).isPresent();
  }

  @Test
  void retainsBundledClassifierCentroidWhenReplacingLanguageById() {
    Linguist custom = create("""
        Renamed MATLAB:
          language_id: %d
          type: programming
          extensions: [.m]
        """.formatted(builtInId("file.matlab")));

    Detection detection = custom.detect("matrix.m", """
        x = linspace(0, 100, 101);
        y = zeros(101, 101);
        for i = 1:101
          y(i, i) = x(i);
        end
        figure
        pcolor(x, y)
        shading flat
        """.getBytes(StandardCharsets.UTF_8));

    Language language = detection.language().orElseThrow();
    assertThat(language.name()).isEqualTo("Renamed MATLAB");
    assertThat(detection.strategy()).isEqualTo(DetectionStrategy.CLASSIFIER);
    assertThat(detection.classifierScores()).containsKey(language);
  }

  @Test
  void keepsNewLanguagesUntrainedAndPreservesTheirCandidateOrder() {
    Linguist custom = create("""
        Z Custom:
          language_id: -1
          type: programming
          extensions: [.m]
        Y Custom:
          language_id: -2
          type: programming
          extensions: [.m]
        """);

    Detection scored = custom.detect("matrix.m", """
        x = linspace(0, 100, 101);
        y = zeros(101, 101);
        for i = 1:101
          y(i, i) = x(i);
        end
        figure
        pcolor(x, y)
        shading flat
        """.getBytes(StandardCharsets.UTF_8));
    assertThat(scored.strategy()).isEqualTo(DetectionStrategy.CLASSIFIER);
    assertThat(scored.language()).map(Language::name).contains("MATLAB");
    assertThat(scored.classifierScores().keySet()).noneMatch(language -> language.languageId() < 0);
    assertThat(scored.candidates()).extracting(Language::name).endsWith("Z Custom", "Y Custom");

    Detection unscored = custom.detect("unknown.m", "unrecognized content\n".getBytes(StandardCharsets.UTF_8));
    assertThat(unscored.strategy()).isEqualTo(DetectionStrategy.EXTENSION);
    assertThat(unscored.classifierScores()).isEmpty();
    assertThat(unscored.language()).contains(unscored.candidates().getFirst());
    assertThat(unscored.candidates()).extracting(Language::name).endsWith("Z Custom", "Y Custom");
  }

  @Test
  void removesOmittedAliasesAndGroup() {
    LanguageCatalog catalog = new LanguageCatalog(input("""
        TSX:
          language_id: %d
          type: programming
          extensions: [.customtsx]
        """.formatted(builtInId("file.tsx"))));
    Language tsx = catalog.detect("file.customtsx").orElseThrow();
    assertThat(LanguageCatalog.group(tsx)).isEqualTo(tsx);
    // XML also claims .tsx, so removing TSX reveals the remaining candidate.
    assertThat(catalog.detect("file.tsx").orElseThrow().name()).isEqualTo("XML");
    assertThat(catalog.byAlias("typescriptreact")).isEmpty();
    LanguageCatalog shell = new LanguageCatalog(input("""
        Shell:
          language_id: %d
          type: programming
        """.formatted(builtInId("file.sh"))));
    assertThat(shell.byAlias("bash")).isEmpty();
    assertThat(shell.byAlias("Shell")).isPresent();
    assertThat(shell.findByInterpreter("bash")).isEmpty();
    assertThat(shell.findByInterpreter("sh")).isEmpty();
  }

  @Test
  void keepsSharedMatchAndAliasTieRules() {
    LanguageCatalog catalog = new LanguageCatalog(input("""
        A Custom:
          language_id: -1
          type: programming
          extensions: [.sql, .h, .rs, .html, .java]
          filenames: [Dockerfile]
          aliases: [java, custom-shared]
        Z Custom:
          language_id: -2
          type: programming
          aliases: [custom-shared]
        """));
    assertThat(catalog.detect("file.sql").orElseThrow().name()).isEqualTo("SQL");
    assertThat(catalog.detect("file.h").orElseThrow().name()).isEqualTo("C");
    assertThat(catalog.detect("file.rs").orElseThrow().name()).isEqualTo("Rust");
    assertThat(catalog.detect("file.html").orElseThrow().name()).isEqualTo("HTML");
    assertThat(catalog.detect("file.java").orElseThrow().name()).isEqualTo("A Custom");
    assertThat(catalog.detect("Dockerfile").orElseThrow().name()).isEqualTo("A Custom");
    assertThat(catalog.byAlias("java").orElseThrow().name()).isEqualTo("Java");
    assertThat(catalog.byAlias("custom-shared").orElseThrow().name()).isEqualTo("A Custom");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "# empty", "{}"})
  void emptyYamlKeepsBuiltIns(String yaml) {
    assertThat(create(yaml).detect("Main.java", content())).isEqualTo(Linguist.create().detect("Main.java", content()));
  }

  @ParameterizedTest
  @MethodSource("invalidDefinitions")
  void rejectsInvalidDefinitions(String yaml, String message) {
    assertThatThrownBy(() -> create(yaml)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
  }

  private static Stream<Arguments> invalidDefinitions() {
    return Stream.of(
        Arguments.of("[]", "YAML mapping"),
        Arguments.of("'': {}", "Language names"),
        Arguments.of("12: {}", "Language names"),
        Arguments.of("Example: []", "must be a mapping"),
        Arguments.of("Example: {type: programming}", "language_id"),
        Arguments.of("Example: {language_id: 1.5, type: programming}", "language_id"),
        Arguments.of("Example: {language_id: '1', type: programming}", "language_id"),
        Arguments.of("Example: {language_id: 2147483648, type: programming}", "language_id"),
        Arguments.of("Example: {language_id: -1}", "Invalid language type"),
        Arguments.of("Example: {language_id: -1, type: other}", "Invalid language type"),
        Arguments.of("Example: {language_id: -1, type: [], extensions: []}", "Invalid language type"),
        Arguments.of("Example: {language_id: -1, type: programming, extensions: .example}", "extensions"),
        Arguments.of("Example: {language_id: -1, type: programming, extensions: ['']}", "extensions"),
        Arguments.of("Example: {language_id: -1, type: programming, filenames: [12]}", "filenames"),
        Arguments.of("Example: {language_id: -1, type: programming, aliases: null}", "aliases"),
        Arguments.of("Example: {language_id: -1, type: programming, interpreters: shell}", "interpreters"),
        Arguments.of("Example: {language_id: -1, type: programming, group: 12}", "group"),
        Arguments.of("Example: {language_id: -1, type: programming, fs_name: ''}", "fs_name"),
        Arguments.of("Example: {language_id: -1, type: programming, color: false}", "color"),
        Arguments.of("Example: {language_id: -1, type: programming, tm_scope: []}", "tm_scope"),
        Arguments.of("Example: {language_id: -1, type: programming, ace_mode: ''}", "ace_mode"),
        Arguments.of("Example: {language_id: -1, type: programming, codemirror_mode: 12}", "codemirror_mode"),
        Arguments.of("Example: {language_id: -1, type: programming, codemirror_mime_type: ''}",
          "codemirror_mime_type"),
        Arguments.of("Example: {language_id: -1, type: programming, wrap: invalid}", "wrap"),
        Arguments.of("Example: {language_id: -1, type: programming, searchable: invalid}", "searchable"),
        Arguments.of("Example: {language_id: -1, type: programming, unexpected: true}", "Unknown language field"),
        Arguments.of("Example: {language_id: -1, type: programming, 12: true}", "Unknown language field"),
        Arguments.of("Example: {language_id: -1, type: programming, group: Missing}", "Unknown language group"),
        Arguments.of("""
          Cycle One: {language_id: -1, type: programming, group: Cycle Two}
          Cycle Two: {language_id: -2, type: programming, group: Cycle One}
          """, "Language group cycle"),
        Arguments.of("Java: {language_id: -1, type: programming}", "Conflicting language name: Java"),
        Arguments.of("java: {language_id: -1, type: programming}", "Conflicting language name: java"),
        Arguments.of("Visual-Basic-.NET: {language_id: -1, type: programming}",
          "Conflicting language name: Visual-Basic-.NET"),
        Arguments.of(
          "A: {language_id: -1, type: programming}\nB: {language_id: -1, type: data}",
          "Duplicate language_id"),
        Arguments.of("A: {language_id: -1, type: programming}\nA: {language_id: -2, type: data}", "duplicate key A"),
        Arguments.of("A: [", "Invalid custom language YAML"));
  }

  @Test
  void renamedGroupsRequireUpdatingReferences() {
    String rename = """
        Renamed TypeScript:
          language_id: %d
          type: programming
          extensions: [.ts]
        """.formatted(builtInId("file.ts"));
    assertThatThrownBy(() -> create(rename))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("TypeScript");
    LanguageCatalog catalog = new LanguageCatalog(input(rename + """
        TSX:
          language_id: %d
          type: programming
          extensions: [.tsx]
          group: Renamed TypeScript
        Glimmer TS:
          language_id: %d
          type: programming
          extensions: [.gts]
          group: Renamed TypeScript
        """.formatted(
          builtInId("file.tsx"),
          LanguageCatalog.bundled().byAlias("Glimmer TS").orElseThrow().languageId())));
    assertThat(LanguageCatalog.group(catalog.detect("file.tsx").orElseThrow()).name()).isEqualTo("Renamed TypeScript");
  }

  @Test
  void readsEagerlyWithoutClosingCallerStream() {
    ByteArrayInputStream stream = new ByteArrayInputStream("""
        Example:
          language_id: -1
          type: programming
          extensions: [.example]
        """.getBytes(StandardCharsets.UTF_8)) {
      @Override
      public void close() {
        throw new AssertionError("Caller owns the stream");
      }
    };
    Linguist custom = Linguist.create(stream);
    assertThat(stream.available()).isZero();
    assertThat(custom.detect("file.example", content()).language()).isPresent();
  }

  @Test
  void reportsStreamFailuresAndDoesNotCloseStream() {
    IOException failure = new IOException("read failure");
    InputStream stream = new InputStream() {
      @Override
      public int read() throws IOException {
        throw failure;
      }

      @Override
      public void close() {
        throw new AssertionError("Caller owns the stream");
      }
    };
    assertThatThrownBy(() -> Linguist.create(stream)).isInstanceOf(UncheckedIOException.class).hasCause(failure);
    assertThatThrownBy(() -> Linguist.create(null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("customLanguages");
  }

  private static int builtInId(String filename) {
    return Linguist.create().detect(filename, content()).language().orElseThrow().languageId();
  }

  private static byte[] content() {
    return new byte[] {(byte) 'x'};
  }

  private static Linguist create(String yaml) {
    return Linguist.create(input(yaml));
  }

  private static InputStream input(String yaml) {
    return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
  }
}
