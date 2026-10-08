/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenizerTest {
  @Test
  void skipsQuotedStringsAndNumbers() {
    assertThat(Tokenizer.tokenize("print \"Hello \\\"Josh\\\"\", 'world' + 1.23e-04"))
      .containsExactly("print", ",", "+");
  }

  @Test
  void transitionsThroughEveryMultilineCommentState() {
    assertThat(Tokenizer.tokenize("before /* c */ after")).containsExactly("before", "COMMENT/*", "after");
    assertThat(Tokenizer.tokenize("before <!-- c --> after")).containsExactly("before", "COMMENT<!--", "after");
    assertThat(Tokenizer.tokenize("before {- c -} after")).containsExactly("before", "COMMENT{-", "after");
    assertThat(Tokenizer.tokenize("before (* c *) after")).containsExactly("before", "COMMENT(*", "after");
    assertThat(Tokenizer.tokenize("before \"\"\"c\"\"\" after")).containsExactly("before", "COMMENT\"\"\"", "after");
    assertThat(Tokenizer.tokenize("before '''c''' after")).containsExactly("before", "COMMENT'''", "after");
    assertThat(Tokenizer.tokenize(".ig\nc\n..\nafter")).containsExactly("COMMENT.ig", "after");
    assertThat(Tokenizer.tokenize("before /- c -/ after")).containsExactly("before", "COMMENT/-", "after");
  }

  @Test
  void emitsLineCommentKinds() {
    assertThat(Tokenizer.tokenize("# comment\n//! comment\n-- comment\n% comment\n; comment\n$! comment\n"))
      .containsExactly("COMMENT#", "COMMENT//!", "COMMENT--", "COMMENT%", "COMMENT;", "COMMENT$!");
  }

  @Test
  void resumesAfterShebangLine() {
    assertThat(Tokenizer.tokenize("#!/usr/bin/env ruby --disable-gems\nputs 'hello'"))
      .containsExactly("SHEBANG#!ruby", "puts");
  }

  @Test
  void preservesIdentifiersAndOperators() {
    assertThat(Tokenizer.tokenize("a->b != c ${value} (?:pattern)"))
      .containsExactly("a", "->", "b", "!=", "c", "${", "value", "}", "(?:", "pattern", ")");
  }

  @Test
  void preservesNativeLongestMatchAndRuleOrder() {
    assertThat(Tokenizer.tokenize("/**/ /***/\n//***\nG1 123 <=> -/"))
      .containsExactly("COMMENT/*", "COMMENT/**", "COMMENT//", "G1", "<=>", "-", "/");
  }

  @Test
  void treatsInputAsBytesWithoutRequiringValidUtf8() {
    assertThat(Tokenizer.tokenize(new byte[] {'a', (byte) 0xFF, '+', (byte) 0xC3, 'b'}))
      .containsExactly("a", "+", "b");
  }

  @Test
  void limitsEvaluationToFirstHundredThousandBytes() {
    byte[] outsideLimit = new byte[100_001];
    Arrays.fill(outsideLimit, (byte) ' ');
    outsideLimit[100_000] = '+';

    byte[] insideLimit = outsideLimit.clone();
    insideLimit[99_999] = '+';

    assertThat(Tokenizer.tokenize(outsideLimit)).isEmpty();
    assertThat(Tokenizer.tokenize(insideLimit)).containsExactly("+");
  }

  @Test
  void truncatesDynamicTokensToSixteenBytes() {
    assertThat(Tokenizer.tokenize("a".repeat(100))).containsExactly("a".repeat(16));
    assertThat(Tokenizer.tokenize(".".repeat(32 * 1024 + 1))).containsExactly(".".repeat(16));
    assertThat(Tokenizer.tokenize("#!/usr/bin/abcdefghijklmnopq\necho"))
      .containsExactly("SHEBANG#!abcdefghijklmnop", "echo");
  }
}
