/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicRuleEvaluatorTest {
  private static final HeuristicRuleEvaluator.PatternMatcher CONTAINS = (pattern, content) ->
    new String(content, StandardCharsets.UTF_8).contains(pattern);

  @Test
  void supportsInlinePatternUnionsAndFirstMatchingRuleOrder() {
    HeuristicRules.Disambiguation disambiguation = disambiguation(
      rule("First", new HeuristicRules.Pattern(List.of("missing", "inline"))),
      rule("Second", HeuristicRules.Always.INSTANCE));

    assertThat(evaluate(disambiguation, "inline content")).containsExactly("First");
    assertThat(evaluate(disambiguation, "other content")).containsExactly("Second");
  }

  @Test
  void supportsNamedPatternUnions() {
    HeuristicRules.Disambiguation disambiguation = disambiguation(
      rule("Named", new HeuristicRules.NamedPattern("shared")));
    Map<String, List<String>> namedPatterns = Map.of("shared", List.of("first", "second"));

    assertThat(evaluate(disambiguation, namedPatterns, "contains second pattern")).containsExactly("Named");
  }

  @Test
  void supportsConjunctionsAndNegativePatterns() {
    HeuristicRules.Condition condition = new HeuristicRules.All(List.of(
      new HeuristicRules.Pattern(List.of("required")),
      new HeuristicRules.NegativePattern(List.of("forbidden"))));
    HeuristicRules.Disambiguation disambiguation = disambiguation(rule("Combined", condition));

    assertThat(evaluate(disambiguation, "required content")).containsExactly("Combined");
    assertThat(evaluate(disambiguation, "required forbidden content")).isEmpty();
  }

  @Test
  void preservesMultipleLanguageOrder() {
    HeuristicRules.Rule rule = new HeuristicRules.Rule(
      List.of("First", "Second"),
      HeuristicRules.Always.INSTANCE);

    assertThat(evaluate(disambiguation(rule), "content")).containsExactly("First", "Second");
  }

  @Test
  void returnsNoMatchWhenJavaRegexBudgetReplacesRubyTimeout() {
    HeuristicRules.Disambiguation disambiguation = disambiguation(
      rule("Unsafe", new HeuristicRules.Pattern(List.of("(a+)+b"))));
    HeuristicRuleEvaluator.PatternMatcher failing = (pattern, content) ->
      BoundedRegex.compile(pattern, 1_000, 1_000).matches(content);

    assertThat(HeuristicRuleEvaluator.evaluate(
      disambiguation,
      Map.of(),
      "a".repeat(500).getBytes(StandardCharsets.UTF_8),
      failing))
      .isEmpty();
  }

  private static HeuristicRules.Disambiguation disambiguation(HeuristicRules.Rule... rules) {
    return new HeuristicRules.Disambiguation(List.of(".example"), List.of(rules));
  }

  private static HeuristicRules.Rule rule(String language, HeuristicRules.Condition condition) {
    return new HeuristicRules.Rule(List.of(language), condition);
  }

  private static List<String> evaluate(HeuristicRules.Disambiguation disambiguation, String content) {
    return evaluate(disambiguation, Map.of(), content);
  }

  private static List<String> evaluate(
    HeuristicRules.Disambiguation disambiguation,
    Map<String, List<String>> namedPatterns,
    String content) {
    return HeuristicRuleEvaluator.evaluate(
      disambiguation,
      namedPatterns,
      content.getBytes(StandardCharsets.UTF_8),
      CONTAINS);
  }
}
