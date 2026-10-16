/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HeuristicRules {
  static final int EVALUATION_BYTES = 50 * 1024;

  private HeuristicRules() {
  }

  private static final class Bundled {
    private static final Definition DEFINITION = parse(LanguageCatalog.loadYaml("heuristics.yml"));
  }

  static Definition bundled() {
    return Bundled.DEFINITION;
  }

  static Definition parse(Object yaml) {
    Map<?, ?> root = mapping(yaml, "Heuristic data");
    Map<String, List<String>> namedPatterns = namedPatterns(root.get("named_patterns"));
    List<Disambiguation> disambiguations = list(root.get("disambiguations"), "Heuristic disambiguations").stream()
      .map(value -> disambiguation(value, namedPatterns))
      .toList();
    return new Definition(disambiguations, namedPatterns);
  }

  static byte[] evaluationBytes(FileContent content) {
    return Arrays.copyOf(content.bytes(), Math.min(content.length(), EVALUATION_BYTES));
  }

  private static Map<String, List<String>> namedPatterns(Object value) {
    Map<?, ?> values = mapping(value, "Heuristic named_patterns");
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (var entry : values.entrySet()) {
      String name = nonblankString(entry.getKey(), "Heuristic named pattern name");
      result.put(name, strings(entry.getValue(), "Heuristic named pattern " + name));
    }
    return Collections.unmodifiableMap(result);
  }

  private static Disambiguation disambiguation(Object value, Map<String, List<String>> namedPatterns) {
    Map<?, ?> fields = mapping(value, "Heuristic disambiguation");
    List<String> extensions = stringList(fields.get("extensions"), "Heuristic extensions");
    List<Rule> rules = list(fields.get("rules"), "Heuristic rules").stream()
      .map(rule -> rule(rule, namedPatterns))
      .toList();
    return new Disambiguation(extensions, rules);
  }

  private static Rule rule(Object value, Map<String, List<String>> namedPatterns) {
    Map<?, ?> fields = mapping(value, "Heuristic rule");
    List<String> languages = strings(fields.get("language"), "Heuristic language");
    return new Rule(languages, condition(fields, namedPatterns));
  }

  private static Condition condition(Map<?, ?> fields, Map<String, List<String>> namedPatterns) {
    if (fields.containsKey("and")) {
      List<Condition> conditions = list(fields.get("and"), "Heuristic conjunction").stream()
        .map(value -> condition(mapping(value, "Heuristic conjunction rule"), namedPatterns))
        .toList();
      return new All(conditions);
    }
    if (fields.containsKey("pattern")) {
      return new Pattern(strings(fields.get("pattern"), "Heuristic pattern"));
    }
    if (fields.containsKey("negative_pattern")) {
      return new NegativePattern(strings(fields.get("negative_pattern"), "Heuristic negative_pattern"));
    }
    if (fields.containsKey("named_pattern")) {
      String name = nonblankString(fields.get("named_pattern"), "Heuristic named_pattern");
      if (!namedPatterns.containsKey(name)) {
        throw new IllegalArgumentException("Unknown heuristic named_pattern: " + name);
      }
      return new NamedPattern(name);
    }
    return Always.INSTANCE;
  }

  private static List<String> strings(Object value, String description) {
    if (value instanceof String string) {
      return List.of(nonblankString(string, description));
    }
    return stringList(value, description);
  }

  private static List<String> stringList(Object value, String description) {
    List<?> values = list(value, description);
    return values.stream().map(item -> nonblankString(item, description)).toList();
  }

  private static String nonblankString(Object value, String description) {
    if (!(value instanceof String string) || string.isBlank()) {
      throw new IllegalArgumentException(description + " must be a nonblank string");
    }
    return string;
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

  record Definition(List<Disambiguation> disambiguations, Map<String, List<String>> namedPatterns) {
    Definition {
      disambiguations = List.copyOf(disambiguations);
      namedPatterns = Collections.unmodifiableMap(new LinkedHashMap<>(namedPatterns));
    }
  }

  record Disambiguation(List<String> extensions, List<Rule> rules) {
    Disambiguation {
      extensions = List.copyOf(extensions);
      rules = List.copyOf(rules);
    }
  }

  record Rule(List<String> languages, Condition condition) {
    Rule {
      languages = List.copyOf(languages);
    }
  }

  sealed interface Condition permits All, Pattern, NegativePattern, NamedPattern, Always {
  }

  record All(List<Condition> conditions) implements Condition {
    All {
      conditions = List.copyOf(conditions);
    }
  }

  record Pattern(List<String> patterns) implements Condition {
    Pattern {
      patterns = List.copyOf(patterns);
    }
  }

  record NegativePattern(List<String> patterns) implements Condition {
    NegativePattern {
      patterns = List.copyOf(patterns);
    }
  }

  record NamedPattern(String name) implements Condition {
  }

  enum Always implements Condition {
    INSTANCE
  }
}
