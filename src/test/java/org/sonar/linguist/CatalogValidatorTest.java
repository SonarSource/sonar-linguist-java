/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogValidatorTest {
  private static final String VALID_HEURISTICS = """
    disambiguations:
    - extensions: [.a]
      rules:
      - language: A
        named_pattern: known
    named_patterns:
      known: pattern
    """;

  @Test
  void acceptsPinnedRuntimeResources() {
    assertThatCode(LanguageCatalog::bundled).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @MethodSource("invalidResources")
  void rejectsInvalidCatalogAndCrossResourceReferences(
    String languages,
    Object popular,
    Object generic,
    Object heuristics,
    String message) {
    Collection<LanguageCatalog.Definition> definitions =
      LanguageCatalog.parseDefinitions(yaml(languages)).values();

    assertThatThrownBy(() -> CatalogValidator.validate(definitions, popular, generic, heuristics))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining(message);
  }

  private static Stream<Arguments> invalidResources() {
    String validLanguages = "A: {language_id: 1, type: programming, extensions: [.a]}";
    return Stream.of(
      Arguments.of("A: {language_id: -1, type: programming, extensions: [.a]}",
        List.of("A"), generic(".a"), yaml(VALID_HEURISTICS), "Invalid bundled language_id"),
      Arguments.of("""
          A: {language_id: 1, type: programming, extensions: [.a], aliases: [shared]}
          B: {language_id: 2, type: programming, extensions: [.b], aliases: [shared]}
          """, List.of("A"), generic(".a"), yaml(VALID_HEURISTICS), "Conflicting bundled language alias"),
      Arguments.of(validLanguages, List.of("Missing"), generic(".a"), yaml(VALID_HEURISTICS),
        "Unknown popular language"),
      Arguments.of(validLanguages, List.of("A"), generic(".missing"), yaml(VALID_HEURISTICS),
        "Unknown generic extension"),
      Arguments.of(validLanguages, List.of("A"), generic(".a"), heuristics(".missing", "A", "known"),
        "Unknown heuristic extension"),
      Arguments.of(validLanguages, List.of("A"), generic(".a"), heuristics(".a", "Missing", "known"),
        "Unknown heuristic language"),
      Arguments.of(validLanguages, List.of("A"), generic(".a"), heuristics(".a", "A", "missing"),
        "Unknown heuristic named_pattern"));
  }

  private static Map<String, Object> generic(String extension) {
    return Map.of("extensions", List.of(extension));
  }

  private static Object heuristics(String extension, String language, String namedPattern) {
    return yaml("""
      disambiguations:
      - extensions: [%s]
        rules:
        - language: %s
          named_pattern: %s
      named_patterns:
        known: pattern
      """.formatted(extension, language, namedPattern));
  }

  private static Object yaml(String content) {
    return new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
  }
}
