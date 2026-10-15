/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HeuristicRegexMatcher implements HeuristicRuleEvaluator.PatternMatcher {
  private final Map<String, BoundedRegex> patterns;

  private HeuristicRegexMatcher(Map<String, BoundedRegex> patterns) {
    this.patterns = Collections.unmodifiableMap(new LinkedHashMap<>(patterns));
  }

  static HeuristicRegexMatcher compile(HeuristicRules.Definition definition) {
    Map<String, BoundedRegex> patterns = new LinkedHashMap<>();
    definition.namedPatterns().values().forEach(sources -> compile(sources, patterns));
    definition.disambiguations().stream()
      .flatMap(disambiguation -> disambiguation.rules().stream())
      .forEach(rule -> compile(rule.condition(), patterns));
    return new HeuristicRegexMatcher(patterns);
  }

  @Override
  public boolean matches(String pattern, byte[] content) {
    BoundedRegex regex = patterns.get(pattern);
    if (regex == null) {
      throw new IllegalArgumentException("Heuristic pattern was not compiled: " + pattern);
    }
    return regex.matches(content);
  }

  private static void compile(HeuristicRules.Condition condition, Map<String, BoundedRegex> patterns) {
    if (condition instanceof HeuristicRules.All(var conditions)) {
      conditions.forEach(nested -> compile(nested, patterns));
    } else if (condition instanceof HeuristicRules.Pattern(var sources)) {
      compile(sources, patterns);
    } else if (condition instanceof HeuristicRules.NegativePattern(var sources)) {
      compile(sources, patterns);
    }
  }

  private static void compile(List<String> sources, Map<String, BoundedRegex> patterns) {
    sources.forEach(source -> patterns.computeIfAbsent(
      source,
      key -> BoundedRegex.compile(key, HeuristicRules.EVALUATION_BYTES)));
  }
}
