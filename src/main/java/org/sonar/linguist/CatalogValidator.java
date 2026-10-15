/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CatalogValidator {
  private static final int DELETED_LANGUAGE_ID = 21;
  private static final int RESERVED_LANGUAGE_ID_START = 432;
  private static final int RESERVED_LANGUAGE_ID_END = 1024;

  private CatalogValidator() {
  }

  static void validate(
    Collection<LanguageCatalog.Definition> definitions,
    Object popularYaml,
    Object genericYaml,
    Object heuristicsYaml) {
    Set<String> languageNames = validateDefinitions(definitions);
    validatePopularLanguages(popularYaml, languageNames);
    Set<String> recognizedPaths = recognizedPaths(definitions);
    validateGenericExtensions(genericYaml, recognizedPaths);
    validateHeuristics(heuristicsYaml, languageNames, recognizedPaths);
  }

  private static Set<String> validateDefinitions(Collection<LanguageCatalog.Definition> definitions) {
    Set<String> languageNames = new HashSet<>();
    Map<String, String> aliasOwners = new HashMap<>();
    for (LanguageCatalog.Definition definition : definitions) {
      validateLanguageId(definition);
      if (!languageNames.add(definition.name())) {
        throw new IllegalArgumentException("Duplicate language name: " + definition.name());
      }
      validateAlias(aliasOwners, definition.name(), definition.name());
      for (String alias : definition.aliases()) {
        validateAlias(aliasOwners, alias, definition.name());
      }
    }
    return languageNames;
  }

  private static void validateLanguageId(LanguageCatalog.Definition definition) {
    int languageId = definition.languageId();
    if (languageId < 0
      || languageId == DELETED_LANGUAGE_ID
      || (languageId >= RESERVED_LANGUAGE_ID_START && languageId < RESERVED_LANGUAGE_ID_END)
      || languageId == Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Invalid bundled language_id for " + definition.name() + ": " + languageId);
    }
  }

  private static void validateAlias(Map<String, String> aliasOwners, String alias, String language) {
    String normalizedAlias = LanguageCatalog.normalizeAlias(alias);
    String existing = aliasOwners.putIfAbsent(normalizedAlias, language);
    if (existing != null && !existing.equals(language)) {
      throw new IllegalArgumentException(
        "Conflicting bundled language alias '" + alias + "' for " + existing + " and " + language);
    }
  }

  private static void validatePopularLanguages(Object yaml, Set<String> languageNames) {
    for (String language : stringList(yaml, "popular languages")) {
      requireReference(languageNames, language, "Unknown popular language: ");
    }
  }

  private static Set<String> recognizedPaths(Collection<LanguageCatalog.Definition> definitions) {
    Set<String> paths = new HashSet<>();
    for (LanguageCatalog.Definition definition : definitions) {
      paths.addAll(definition.extensions());
      paths.addAll(definition.filenames());
    }
    return paths;
  }

  private static void validateGenericExtensions(Object yaml, Set<String> recognizedPaths) {
    Map<?, ?> generic = mapping(yaml, "Generic-extension data");
    for (String extension : stringList(generic.get("extensions"), "generic extensions")) {
      requireReference(recognizedPaths, extension, "Unknown generic extension: ");
    }
  }

  private static void validateHeuristics(
    Object yaml,
    Set<String> languageNames,
    Set<String> recognizedPaths) {
    Map<?, ?> heuristics = mapping(yaml, "Heuristic data");
    Set<String> namedPatterns = stringKeys(mapping(heuristics.get("named_patterns"), "Heuristic named_patterns"));
    for (Object value : list(heuristics.get("disambiguations"), "Heuristic disambiguations")) {
      Map<?, ?> disambiguation = mapping(value, "Heuristic disambiguation");
      for (String extension : stringList(disambiguation.get("extensions"), "heuristic extensions")) {
        requireReference(recognizedPaths, extension, "Unknown heuristic extension or filename: ");
      }
      for (Object rule : list(disambiguation.get("rules"), "heuristic rules")) {
        validateHeuristicRule(mapping(rule, "Heuristic rule"), languageNames, namedPatterns);
      }
    }
  }

  private static void validateHeuristicRule(
    Map<?, ?> rule,
    Set<String> languageNames,
    Set<String> namedPatterns) {
    Object language = rule.get("language");
    if (language != null) {
      for (String name : stringOrList(language, "heuristic language")) {
        requireReference(languageNames, name, "Unknown heuristic language: ");
      }
    }
    Object namedPattern = rule.get("named_pattern");
    if (namedPattern != null) {
      if (!(namedPattern instanceof String name) || name.isBlank()) {
        throw new IllegalArgumentException("Heuristic named_pattern must be a nonblank string");
      }
      requireReference(namedPatterns, name, "Unknown heuristic named_pattern: ");
    }
    Object conjunction = rule.get("and");
    if (conjunction != null) {
      for (Object nested : list(conjunction, "heuristic conjunction")) {
        validateHeuristicRule(mapping(nested, "Heuristic conjunction rule"), languageNames, namedPatterns);
      }
    }
  }

  private static Set<String> stringKeys(Map<?, ?> mapping) {
    Set<String> keys = new HashSet<>();
    for (Object key : mapping.keySet()) {
      if (!(key instanceof String name) || name.isBlank()) {
        throw new IllegalArgumentException("Heuristic named pattern names must be nonblank strings");
      }
      keys.add(name);
    }
    return keys;
  }

  private static Map<?, ?> mapping(Object value, String description) {
    if (!(value instanceof Map<?, ?> result)) {
      throw new IllegalArgumentException(description + " must be a mapping");
    }
    return result;
  }

  private static List<?> list(Object value, String description) {
    if (!(value instanceof List<?> result)) {
      throw new IllegalArgumentException(description + " must be a list");
    }
    return result;
  }

  private static List<String> stringList(Object value, String description) {
    List<?> values = list(value, description);
    if (values.stream().anyMatch(item -> !(item instanceof String string) || string.isBlank())) {
      throw new IllegalArgumentException(description + " must contain nonblank strings");
    }
    return values.stream().map(String.class::cast).toList();
  }

  private static List<String> stringOrList(Object value, String description) {
    if (value instanceof String string && !string.isBlank()) {
      return List.of(string);
    }
    return stringList(value, description);
  }

  private static void requireReference(Set<String> values, String reference, String message) {
    if (!values.contains(reference)) {
      throw new IllegalArgumentException(message + reference);
    }
  }
}
