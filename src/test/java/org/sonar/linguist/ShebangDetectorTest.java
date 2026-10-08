/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class ShebangDetectorTest {
  private final LanguageCatalog catalog = LanguageCatalog.bundled();

  @ParameterizedTest
  @MethodSource("recognizedShebangs")
  void extractsInterpreters(String content, String interpreter) {
    assertThat(interpreter(content)).contains(interpreter);
  }

  @ParameterizedTest
  @MethodSource("unrecognizedShebangs")
  void rejectsInvalidOrLanguageSelectingShebangs(String content) {
    assertThat(interpreter(content)).isEmpty();
  }

  @Test
  void limitsMultilineExecHackToFirstFiveLines() {
    assertThat(interpreter("#!/bin/sh\n\n\nexec ruby $0 $@\n")).contains("ruby");
    assertThat(interpreter("#!/bin/sh\n\n\n\n\nexec ruby $0 $@\n")).contains("sh");
  }

  @Test
  void intersectsWithExistingCandidatesInTheirOrder() {
    Language typescript = catalog.byAlias("typescript").orElseThrow();
    Language javascript = catalog.byAlias("javascript").orElseThrow();

    assertThat(ShebangDetector.detect(content("#!/usr/bin/env node\n"), catalog, List.of(typescript, javascript)))
      .containsExactly(typescript, javascript);
  }

  private static Optional<String> interpreter(String content) {
    return ShebangDetector.interpreter(content(content));
  }

  private static FileContent content(String content) {
    return FileContent.copyOf("script", content.getBytes(StandardCharsets.UTF_8));
  }

  private static Stream<Arguments> recognizedShebangs() {
    return Stream.of(
      Arguments.of("#!/usr/sbin/ruby\n# bar", "ruby"),
      Arguments.of("#! perl", "perl"),
      Arguments.of("#!/usr/bin/python2.7\n", "python2"),
      Arguments.of("#!/usr/bin/env Rscript\n", "Rscript"),
      Arguments.of("#!/usr/bin/env bin/crystal", "crystal"),
      Arguments.of("#! /usr/bin/env A=003 B=149 sh", "sh"),
      Arguments.of("#!/usr/bin/env -vS ruby -wKU\n", "ruby"),
      Arguments.of("#!/usr/bin/env --split-string sed -f\n", "sed"),
      Arguments.of("#!/usr/bin/env -S GH_TOKEN=value deno run\n", "deno"),
      Arguments.of("#!/usr/bin/osascript", "osascript"));
  }

  private static Stream<Arguments> unrecognizedShebangs() {
    return Stream.of(
      Arguments.of(""),
      Arguments.of(" #!/usr/sbin/ruby"),
      Arguments.of("\n#!/usr/sbin/ruby"),
      Arguments.of("#!"),
      Arguments.of("#! "),
      Arguments.of("#!/usr/bin/env"),
      Arguments.of("#!/usr/bin/env osascript -l JavaScript"),
      Arguments.of("#!/usr/bin/osascript -l AppleScript"));
  }
}
