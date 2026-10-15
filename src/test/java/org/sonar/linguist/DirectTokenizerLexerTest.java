/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DirectTokenizerLexerTest {
  @Test
  void tokenizesIdentifiersCommentsStringsAndOperators() {
    assertThat(tokens("#!/usr/bin/env ruby --disable-gems\nname /** doc */ + \"ignored\" ${value}"))
      .containsExactly("SHEBANG#!ruby", "name", "COMMENT/**", "+", "${", "value", "}");
  }

  @Test
  void resumesAfterEveryMultilineState() {
    assertThat(tokens("a /*x*/ b <!--x--> c {-x-} d (*x*) e \"\"\"x\"\"\" f '''x''' g /-x-/ h"))
      .containsExactly(
        "a", "COMMENT/*", "b", "COMMENT<!--", "c", "COMMENT{-", "d", "COMMENT(*", "e",
        "COMMENT\"\"\"", "f", "COMMENT'''", "g", "COMMENT/-", "h");
  }

  @Test
  void appliesLongestMatchBeforeRuleOrder() {
    assertThat(tokens("/**/ /***/\n//***\nG1 123abc 123 <=> -/"))
      .containsExactly("COMMENT/*", "COMMENT/**", "COMMENT//", "G1", "123abc", "<=>", "-", "/");
  }

  @Test
  void requiresSignInNumericExponent() {
    assertThat(tokens("9E30 9E+30 9E-30"))
      .containsExactly("9E30");
  }

  @Test
  void treatsInputAsBytesWithoutRequiringValidUtf8() {
    assertThat(tokens(new byte[] {'a', (byte) 0xFF, '+', (byte) 0xC3, 'b'}))
      .containsExactly("a", "+", "b");
  }

  @Test
  void recognizesByteOrientedLineTerminators() {
    assertThat(tokens(new byte[] {(byte) 0x85, ';', ' ', 'c', 'o', 'm', 'm', 'e', 'n', 't'}))
      .containsExactly("COMMENT;");
  }

  @Test
  void limitsEvaluationToFirstHundredThousandBytes() {
    byte[] outsideLimit = new byte[100_001];
    Arrays.fill(outsideLimit, (byte) ' ');
    outsideLimit[100_000] = '+';

    byte[] insideLimit = outsideLimit.clone();
    insideLimit[99_999] = '+';

    assertThat(tokens(outsideLimit)).isEmpty();
    assertThat(tokens(insideLimit)).containsExactly("+");
  }

  @Test
  void truncatesDynamicTokensToSixteenBytesWithoutChangingOrder() {
    assertThat(tokens("first " + "a".repeat(100) + " second " + ".".repeat(100) + " third"))
      .containsExactly("first", "a".repeat(16), "second", ".".repeat(16), "third");
    assertThat(tokens("#!/usr/bin/abcdefghijklmnopq\necho"))
      .containsExactly("SHEBANG#!abcdefghijklmnop", "echo");
  }

  private static List<String> tokens(String input) {
    return tokens(input.getBytes(StandardCharsets.UTF_8));
  }

  private static List<String> tokens(byte[] input) {
    DirectTokenizerLexer lexer = new DirectTokenizerLexer(input);
    List<String> result = new ArrayList<>();
    String token;
    while ((token = lexer.nextToken()) != null) {
      result.add(token);
    }
    return result;
  }
}
