/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class Tokenizer {
  private Tokenizer() {
  }

  static List<String> tokenize(String content) {
    return tokenize(content.getBytes(StandardCharsets.UTF_8));
  }

  static List<String> tokenize(byte[] content) {
    DirectTokenizerLexer lexer = new DirectTokenizerLexer(content);
    List<String> tokens = new ArrayList<>();
    String token;
    while ((token = lexer.nextToken()) != null) {
      tokens.add(token);
    }
    return List.copyOf(tokens);
  }
}
