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

class VendoredOverridesTest {
  private static final byte[] CONTENT = new byte[0];

  @Test
  void removesBundledRulesAndAddsCustomRules() {
    Linguist linguist = withOverrides("""
        remove:
          - (^|/)\\.github/
          - (^|/)Jenkinsfile$
        add:
          - (^|/)third-party-code/
        """);

    assertThat(vendored(linguist, ".github/workflows/build.yml")).isFalse();
    assertThat(vendored(linguist, "Jenkinsfile")).isFalse();
    assertThat(vendored(linguist, "third-party-code/lib.js")).isTrue();
    assertThat(vendored(linguist, "node_modules/lib/index.js")).isTrue();
  }

  @Test
  void removedRuleDoesNotUnvendorPathsMatchedByOtherRules() {
    Linguist linguist = withOverrides("""
        remove:
          - (^|/)\\.github/
        """);

    assertThat(vendored(linguist, "node_modules/lib/.github/workflows/build.yml")).isTrue();
  }

  @Test
  void doesNotModifyOriginalInstance() {
    Linguist original = Linguist.create();
    original.withVendoredOverrides(input("""
        remove:
          - (^|/)\\.github/
        """));

    assertThat(vendored(original, ".github/workflows/build.yml")).isTrue();
  }

  @Test
  void keepsCustomLanguages() {
    Linguist linguist = Linguist.create(input("""
        Example:
          language_id: -1
          type: programming
          extensions: [.example]
        """))
      .withVendoredOverrides(input("""
        remove:
          - (^|/)\\.github/
        """));

    Detection detection = linguist.detect(".github/file.example", "x".getBytes(StandardCharsets.UTF_8));
    assertThat(detection.language()).contains(new Language(-1, "Example", LanguageType.PROGRAMMING));
    assertThat(detection.vendored()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "remove:", "add: []", "remove: []\nadd: []"})
  void emptyOverridesKeepBundledRules(String yaml) {
    Linguist linguist = withOverrides(yaml);

    assertThat(vendored(linguist, ".github/workflows/build.yml")).isTrue();
    assertThat(vendored(linguist, "src/Main.java")).isFalse();
  }

  @ParameterizedTest
  @MethodSource("invalidOverrides")
  void rejectsInvalidOverrides(String yaml, String message) {
    Linguist linguist = Linguist.create();
    InputStream overrides = input(yaml);

    assertThatThrownBy(() -> linguist.withVendoredOverrides(overrides))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining(message);
  }

  private static Stream<Arguments> invalidOverrides() {
    return Stream.of(
      Arguments.of("remove: [", "Invalid vendored rule overrides YAML"),
      Arguments.of("- (^|/)foo/", "must be a YAML mapping"),
      Arguments.of("exclude: [foo]", "Unknown vendored rule overrides keys: [exclude]"),
      Arguments.of("remove: foo", "'remove' must be a YAML list"),
      Arguments.of("add: [1]", "'add' must contain non-empty strings: 1"),
      Arguments.of("add: ['']", "'add' must contain non-empty strings"),
      Arguments.of(
        "remove: ['(^|/)\\.githubb/']",
        "Vendored rule to remove is not a bundled vendored rule: (^|/)\\.githubb/"),
      Arguments.of("add: ['(']", "Invalid vendored rule to add: ("));
  }

  @Test
  void reportsReadFailures() {
    Linguist linguist = Linguist.create();
    InputStream failing = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("boom");
      }
    };

    assertThatThrownBy(() -> linguist.withVendoredOverrides(failing))
      .isInstanceOf(UncheckedIOException.class)
      .hasMessage("Cannot read vendored rule overrides YAML");
  }

  private static Linguist withOverrides(String yaml) {
    return Linguist.create().withVendoredOverrides(input(yaml));
  }

  private static boolean vendored(Linguist linguist, String path) {
    return linguist.detect(path, CONTENT).vendored();
  }

  private static InputStream input(String yaml) {
    return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
  }
}
