/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class ModelineDetectorTest {
  private final LanguageCatalog catalog = LanguageCatalog.bundled();

  @Test
  void detectsEmacsModelines() {
    assertThat(detect("// -*- ruby -*-\ncontent")).map(Language::name).contains("Ruby");
    assertThat(detect("// -*- foo: bar; MoDe: C++; -*-\ncontent")).map(Language::name).contains("C++");
    assertThat(detect("; -*-mode:Smalltalk-*-\ncontent")).map(Language::name).contains("Smalltalk");
  }

  @Test
  void detectsVimAndExModelines() {
    assertThat(detect("# vim: set ts=8 filetype=ruby tw=0:\ncontent")).map(Language::name).contains("Ruby");
    assertThat(detect(" ex: noexpandtab: ft=javascript\ncontent")).map(Language::name).contains("JavaScript");
    assertThat(detect("# vim600: syntax=Ruby\ncontent")).map(Language::name).contains("Ruby");
    assertThat(detect("# vim: set ft=ruby\ncontent")).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("upstreamModelines")
  void recognizesRepresentativeUpstreamModelineForms(String modeline, String language) {
    assertThat(detect(modeline + "\ncontent\n")).map(Language::name).contains(language);
  }

  @Test
  void examinesOnlyFirstAndLastFiveLines() {
    String middle = String.join("\n",
      "one", "two", "three", "four", "five", "# vim: ft=ruby", "seven", "eight", "nine", "ten", "eleven");
    String footer = String.join("\n",
      "one", "two", "three", "four", "five", "six", "# vim: ft=ruby", "eight", "nine", "ten", "eleven");

    assertThat(detect(middle)).isEmpty();
    assertThat(detect(footer)).map(Language::name).contains("Ruby");
  }

  @Test
  void ignoresVimballModelinesAndUnknownAliases() {
    assertThat(detect("UseVimball\none\ntwo\nthree\nfour\nfive\n# vim: ft=ruby")).isEmpty();
    assertThat(detect("# vim: ft=not-a-language\ncontent")).isEmpty();
  }

  private Optional<Language> detect(String content) {
    return ModelineDetector.detect(
      FileContent.copyOf("file", content.getBytes(StandardCharsets.UTF_8)),
      catalog);
  }

  private static Stream<Arguments> upstreamModelines() {
    return Stream.of(
      Arguments.of("# vim: filetype=ruby", "Ruby"),
      Arguments.of("# vim: set syntax=ruby:", "Ruby"),
      Arguments.of("// -*- font:bar;mode:c++ -*-", "C++"),
      Arguments.of("/* vim: set filetype=pRoloG: */", "Prolog"),
      Arguments.of("; -*- MoDe: PhP;-*-", "PHP"));
  }
}
