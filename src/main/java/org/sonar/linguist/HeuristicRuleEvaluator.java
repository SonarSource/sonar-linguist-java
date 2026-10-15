/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.List;
import java.util.Map;

final class HeuristicRuleEvaluator {
  private HeuristicRuleEvaluator() {
  }

  static List<String> evaluate(
    HeuristicRules.Disambiguation disambiguation,
    Map<String, List<String>> namedPatterns,
    byte[] content,
    PatternMatcher matcher) {
    try {
      return disambiguation.rules().stream()
        .filter(rule -> matches(rule.condition(), namedPatterns, content, matcher))
        .findFirst()
        .map(HeuristicRules.Rule::languages)
        .orElseGet(List::of);
    } catch (BoundedRegex.EvaluationException e) {
      return List.of();
    }
  }

  private static boolean matches(
    HeuristicRules.Condition condition,
    Map<String, List<String>> namedPatterns,
    byte[] content,
    PatternMatcher matcher) {
    return switch (condition) {
      case HeuristicRules.All(var conditions) -> conditions.stream()
        .allMatch(nested -> matches(nested, namedPatterns, content, matcher));
      case HeuristicRules.Pattern(var patterns) -> anyMatches(patterns, content, matcher);
      case HeuristicRules.NegativePattern(var patterns) -> !anyMatches(patterns, content, matcher);
      case HeuristicRules.NamedPattern(var name) -> anyMatches(namedPatterns.get(name), content, matcher);
      case HeuristicRules.Always ignored -> true;
    };
  }

  private static boolean anyMatches(List<String> patterns, byte[] content, PatternMatcher matcher) {
    return patterns.stream().anyMatch(pattern -> matcher.matches(pattern, content));
  }

  @FunctionalInterface
  interface PatternMatcher {
    boolean matches(String pattern, byte[] content);
  }
}
