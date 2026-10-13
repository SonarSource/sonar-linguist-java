/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PathRulesTest {
  private final PathRules rules = new PathRules();

  @ParameterizedTest
  @ValueSource(strings = {
    "node_modules/package/index.js",
    "bower_components/custom/custom.js",
    ".obsidian/plugins/templater-obsidian/main.js",
    ".teamcity/Project_Name_CI/settings.kts"
  })
  void recognizesRepresentativeUpstreamVendoredPaths(String path) {
    assertThat(rules.vendored(path)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "README.md",
    "foo/CHANGELOG.txt",
    "project/Documentation/index.md",
    "examples/some-file.pl"
  })
  void recognizesRepresentativeUpstreamDocumentationPaths(String path) {
    assertThat(rules.documentation(path)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "src/Main.java",
    "project/doc/foo.html",
    "project/docs/foo.html",
    "foo.md"
  })
  void rejectsOrdinaryPathsFromRetainedPathFacts(String path) {
    assertThat(rules.vendored(path)).isFalse();
    assertThat(rules.documentation(path)).isFalse();
  }
}
