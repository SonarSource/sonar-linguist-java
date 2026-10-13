/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Upstream vendor and documentation path patterns, optionally adjusted by vendored rule overrides. */
final class PathRules {
  private static final String REMOVE_KEY = "remove";
  private static final String ADD_KEY = "add";

  private final List<Pattern> vendored;
  private final List<Pattern> documentation;

  PathRules() {
    this(load("vendor.yml"), load("documentation.yml"));
  }

  private PathRules(List<Pattern> vendored, List<Pattern> documentation) {
    this.vendored = vendored;
    this.documentation = documentation;
  }

  boolean vendored(String path) {
    return matches(vendored, path);
  }

  boolean documentation(String path) {
    return matches(documentation, path);
  }

  /**
   * Returns rules where the {@code remove} entries, which must be the exact text of current vendored rules, are dropped
   * and the {@code add} regular expressions are appended.
   */
  PathRules withVendoredOverrides(InputStream overrides) {
    Object yaml = LanguageCatalog.loadCustomYaml(overrides, "vendored rule overrides");
    if (yaml == null) {
      return this;
    }
    if (!(yaml instanceof Map<?, ?> entries)) {
      throw new IllegalArgumentException("Vendored rule overrides must be a YAML mapping");
    }
    Set<Object> unknownKeys = new LinkedHashSet<>(entries.keySet());
    unknownKeys.removeAll(Set.of(REMOVE_KEY, ADD_KEY));
    if (!unknownKeys.isEmpty()) {
      throw new IllegalArgumentException("Unknown vendored rule overrides keys: " + unknownKeys);
    }
    Set<String> removed = new LinkedHashSet<>(rules(entries.get(REMOVE_KEY), REMOVE_KEY));
    Set<String> current = vendored.stream().map(Pattern::pattern).collect(Collectors.toSet());
    for (String rule : removed) {
      if (!current.contains(rule)) {
        throw new IllegalArgumentException("Vendored rule to remove is not a bundled vendored rule: " + rule);
      }
    }
    List<Pattern> merged = Stream.concat(
      vendored.stream().filter(pattern -> !removed.contains(pattern.pattern())),
      rules(entries.get(ADD_KEY), ADD_KEY).stream().map(PathRules::compileAdded))
      .toList();
    return new PathRules(merged, documentation);
  }

  private static List<String> rules(@Nullable Object yaml, String key) {
    if (yaml == null) {
      return List.of();
    }
    if (!(yaml instanceof List<?> values)) {
      throw new IllegalArgumentException("Vendored rule overrides '" + key + "' must be a YAML list");
    }
    List<String> rules = new ArrayList<>();
    for (Object value : values) {
      if (!(value instanceof String rule) || rule.isEmpty()) {
        throw new IllegalArgumentException(
          "Vendored rule overrides '" + key + "' must contain non-empty strings: " + value);
      }
      rules.add(rule);
    }
    return rules;
  }

  private static Pattern compileAdded(String rule) {
    try {
      return Pattern.compile(rule);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid vendored rule to add: " + rule, e);
    }
  }

  private static List<Pattern> load(String name) {
    return ((List<?>) LanguageCatalog.loadYaml(name)).stream()
        .map(value -> Pattern.compile((String) value)).toList();
  }

  private static boolean matches(List<Pattern> rules, String path) {
    return rules.stream().anyMatch(pattern -> pattern.matcher(path).find());
  }
}
