/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

final class DirectTokenizerLexer {
  private static final int MAX_INPUT_BYTES = 100_000;
  private static final int MAX_TOKEN_BYTES = 16;
  private static final char[] PUNCTUATION = "-!#$%&*+,.:;?@\\^`|~".toCharArray();

  private final String input;
  private int position;
  private State state = State.INITIAL;

  DirectTokenizerLexer(byte[] input) {
    this.input = new String(input, 0, Math.min(input.length, MAX_INPUT_BYTES), StandardCharsets.ISO_8859_1);
  }

  String nextToken() {
    while (position < input.length()) {
      if (state != State.INITIAL) {
        consumeState();
      } else {
        Match match = initialMatch();
        if (match == null) {
          position++;
        } else {
          position += match.length();
          state = match.nextState();
          String token = match.token();
          if (token != null) {
            return token;
          }
        }
      }
    }
    return null;
  }

  private Match initialMatch() {
    Match best = null;
    if (atBeginningOfLine()) {
      int envShebang = envShebangLength();
      best = better(best, envShebang, envShebangToken(envShebang), State.SHEBANG_REST);
      int directShebang = directShebangLength();
      best = better(best, directShebang, directShebangToken(directShebang), State.SHEBANG_REST);
      best = better(best, hashCommentLength(), "COMMENT#", State.INITIAL);
      best = better(best, prefixedLineCommentLength("//!", true, true), "COMMENT//!", State.INITIAL);
      best = better(best, slashLineCommentLength(), "COMMENT//", State.INITIAL);
      best = better(best, prefixedLineCommentLength("--", true, false), "COMMENT--", State.INITIAL);
      best = better(best, percentCommentLength(), "COMMENT%", State.INITIAL);
      best = better(best, prefixedLineCommentLength("\"", true, false), "COMMENT\"", State.INITIAL);
      best = better(best, semicolonCommentLength(), "COMMENT;", State.INITIAL);
      best = better(best, roffLineCommentLength('.'), "COMMENT.\\\"", State.INITIAL);
      best = better(best, roffLineCommentLength('\''), "COMMENT'\\\"", State.INITIAL);
      best = better(best, dollarBangCommentLength(), "COMMENT$!", State.INITIAL);
    }

    best = better(best, exact("/**/"), "COMMENT/*", State.INITIAL);
    best = better(best, exact("/**"), "COMMENT/**", State.C_COMMENT);
    best = better(best, exact("/*!"), "COMMENT/*!", State.C_COMMENT);
    best = better(best, exact("/*"), "COMMENT/*", State.C_COMMENT);
    best = better(best, exact("<!--"), "COMMENT<!--", State.XML_COMMENT);
    best = better(best, exact("{-"), "COMMENT{-", State.HASKELL_COMMENT);
    best = better(best, exact("(*"), "COMMENT(*", State.OCAML_COMMENT);
    best = better(best, exact("\"\"\""), "COMMENT\"\"\"", State.PYTHON_DOUBLE_COMMENT);
    best = better(best, exact("'''"), "COMMENT'''", State.PYTHON_SINGLE_COMMENT);
    if (atBeginningOfLine()) {
      best = better(best, exact(".ig\n"), "COMMENT.ig", State.ROFF_COMMENT);
    }
    best = better(best, exact("/--"), "COMMENT/-", State.LEAN_COMMENT);
    best = better(best, exact("/-"), "COMMENT/-", State.LEAN_COMMENT);

    best = better(best, Math.max(exact("\"\""), exact("''")), null, State.INITIAL);
    best = better(best, exact("\""), null, State.DOUBLE_QUOTED_STRING);
    best = better(best, exact("'"), null, State.SINGLE_QUOTED_STRING);
    best = better(best, numberLength(), null, State.INITIAL);

    best = dynamic(best, identifierLength());
    best = dynamic(best, pairedRunLength('(', ')'));
    best = dynamic(best, pairedRunLength('{', '}'));
    best = dynamic(best, pairedRunLength('[', ']'));
    best = dynamic(best, runLength('('));
    best = dynamic(best, runLength(')'));
    best = dynamic(best, runLength('{'));
    best = dynamic(best, runLength('}'));
    best = dynamic(best, runLength('['));
    best = dynamic(best, runLength(']'));
    best = dynamic(best, dollarOpeningRunLength());
    best = dynamic(best, maxExact("(...)", "{...}", "[...]"));
    best = dynamic(best, maxExact("&>", "<&", "<&-", "&>>", ">&"));
    best = dynamic(best, maxExact("|&", "&|"));
    best = dynamic(best, followedRunsLength('-', '>'));
    best = dynamic(best, followedRunsLength('<', '-'));
    best = dynamic(best, followedRunsLength('!', '='));
    best = dynamic(best, comparisonLength());
    best = dynamic(best, angleMarkerLength());
    best = dynamic(best, twoCharacterOperatorLength("?%!", '>'));
    best = dynamic(best, anyRunLength("<>/"));
    best = dynamic(best, assignmentOperatorLength());
    best = dynamic(best, tildeOperatorLength());
    best = dynamic(best, exact(":-"));
    best = dynamic(best, dottedRepetitionLength('*'));
    best = dynamic(best, dottedRepetitionLength('+'));
    best = dynamic(best, exact("(?:"));
    for (char punctuation : PUNCTUATION) {
      best = dynamic(best, runLength(punctuation));
    }
    return best;
  }

  private void consumeState() {
    switch (state) {
      case SHEBANG_REST -> consumeShebangRest();
      case C_COMMENT -> consumeUntil("*/");
      case XML_COMMENT -> consumeUntil("-->");
      case HASKELL_COMMENT -> consumeUntil("-}");
      case OCAML_COMMENT -> consumeUntil("*)");
      case PYTHON_DOUBLE_COMMENT -> consumeUntil("\"\"\"");
      case PYTHON_SINGLE_COMMENT -> consumeUntil("'''");
      case ROFF_COMMENT -> consumeUntil("..\n");
      case LEAN_COMMENT -> consumeUntil("-/");
      case DOUBLE_QUOTED_STRING -> consumeQuotedString('"');
      case SINGLE_QUOTED_STRING -> consumeQuotedString('\'');
      case INITIAL -> throw new IllegalStateException("Initial state cannot be consumed");
    }
  }

  private void consumeShebangRest() {
    int lineFeed = input.indexOf('\n', position);
    if (lineFeed < 0) {
      position = input.length();
    } else {
      position = lineFeed + 1;
      state = State.INITIAL;
    }
  }

  private void consumeUntil(String terminator) {
    int end = input.indexOf(terminator, position);
    if (end < 0) {
      position = input.length();
    } else {
      position = end + terminator.length();
      state = State.INITIAL;
    }
  }

  private void consumeQuotedString(char quote) {
    while (position < input.length()) {
      char current = input.charAt(position);
      position++;
      if (current == '\n' || current == quote) {
        state = State.INITIAL;
        return;
      }
      if (current == '\\' && position < input.length()) {
        position++;
      }
    }
  }

  private int envShebangLength() {
    if (!startsWith("#!")) {
      return 0;
    }
    int index = skipHorizontalWhitespace(position + 2);
    int commandStart = index;
    while (index < input.length() && isShebangPath(input.charAt(index))) {
      index++;
    }
    String command = input.substring(commandStart, index);
    if (!(command.equals("env") || command.endsWith("/env"))
      || index == commandStart
      || !hasHorizontalWhitespace(index)) {
      return 0;
    }
    return envInterpreterLength(skipHorizontalWhitespace(index));
  }

  private int envInterpreterLength(int index) {
    while (index < input.length()) {
      int argumentStart = index;
      while (index < input.length() && !hasHorizontalWhitespace(index) && (input.charAt(index) != '\n')) {
        index++;
      }
      String argument = input.substring(argumentStart, index);
      if (!argument.contains("=")) {
        int interpreterLength = leadingInterpreterLength(argument);
        return interpreterLength == 0 ? 0 : (argumentStart + interpreterLength - position);
      }
      if (!hasHorizontalWhitespace(index)) {
        return 0;
      }
      index = skipHorizontalWhitespace(index);
    }
    return 0;
  }

  private int directShebangLength() {
    if (!startsWith("#!")) {
      return 0;
    }
    int index = skipHorizontalWhitespace(position + 2);
    int start = index;
    while ((index < input.length()) && isDirectShebang(input.charAt(index))) {
      index++;
    }
    return index == start ? 0 : (index - position);
  }

  private String envShebangToken(int length) {
    if (length == 0) {
      return null;
    }
    String value = input.substring(position, position + length);
    int separator = value.lastIndexOf(' ');
    String interpreter = separator < 0 ? value : value.substring(separator + 1);
    return "SHEBANG#!" + truncate(interpreter);
  }

  private String directShebangToken(int length) {
    if (length == 0) {
      return null;
    }
    String value = input.substring(position, position + length);
    int separator = value.lastIndexOf('/');
    String interpreter = separator < 0 ? value : value.substring(separator + 1);
    return "env".equals(interpreter) ? null : ("SHEBANG#!" + truncate(interpreter));
  }

  private int hashCommentLength() {
    int index = skipHorizontalWhitespace(position);
    int hashes = runLength(index, '#');
    return hashes == 0 ? 0 : lineCommentLength(index + hashes, false);
  }

  private int percentCommentLength() {
    int index = skipHorizontalWhitespace(position);
    int percents = runLength(index, '%');
    return percents == 0 ? 0 : lineCommentLength(index + percents, false);
  }

  private int semicolonCommentLength() {
    int index = skipHorizontalWhitespace(position);
    int semicolons = runLength(index, ';');
    return semicolons == 0 ? 0 : lineCommentLength(index + semicolons, false);
  }

  private int prefixedLineCommentLength(String prefix, boolean allowWhitespace, boolean includeLineFeed) {
    int index = allowWhitespace ? skipHorizontalWhitespace(position) : position;
    if (!input.startsWith(prefix, index)) {
      return 0;
    }
    return lineCommentLength(index + prefix.length(), includeLineFeed);
  }

  private int slashLineCommentLength() {
    int index = skipHorizontalWhitespace(position);
    if (!input.startsWith("//", index)) {
      return 0;
    }
    return restOfLineLength(index + 2, true);
  }

  private int roffLineCommentLength(char prefix) {
    if (character(position) != prefix) {
      return 0;
    }
    int index = skipHorizontalWhitespace(position + 1);
    if (!input.startsWith("\\\"", index)) {
      return 0;
    }
    return restOfLineLength(index + 2, true);
  }

  private int dollarBangCommentLength() {
    return startsWith("$! ") ? restOfLineLength(position + 3, true) : 0;
  }

  private int lineCommentLength(int tail, boolean includeLineFeed) {
    if (character(tail) == '\n') {
      return tail + 1 - position;
    }
    if (character(tail) != ' ') {
      return 0;
    }
    return restOfLineLength(tail + 1, includeLineFeed);
  }

  private int restOfLineLength(int start, boolean includeLineFeed) {
    int lineFeed = input.indexOf('\n', start);
    if (lineFeed < 0) {
      return input.length() - position;
    }
    return lineFeed - position + (includeLineFeed ? 1 : 0);
  }

  private int numberLength() {
    int index = position;
    if (character(index) == '0' && character(index + 1) == 'x' && isHex(character(index + 2))) {
      index += 3;
      while (isHex(character(index)) || character(index) == '.') {
        index++;
      }
    } else if (isDigit(character(index))) {
      index++;
      while (isDigit(character(index)) || character(index) == '.') {
        index++;
      }
    } else {
      return 0;
    }
    return Math.max(unsignedSuffixEnd(index), floatingSuffixEnd(index)) - position;
  }

  private int unsignedSuffixEnd(int index) {
    if (character(index) != 'u' && character(index) != 'U') {
      return index;
    }
    int suffixEnd = index + 1;
    int maximumEnd = suffixEnd + 2;
    while (suffixEnd < maximumEnd && (character(suffixEnd) == 'l' || character(suffixEnd) == 'L')) {
      suffixEnd++;
    }
    return suffixEnd;
  }

  private int floatingSuffixEnd(int index) {
    if ((character(index) == 'e' || character(index) == 'E')
      && (character(index + 1) == '-' || character(index + 1) == '+')) {
      index++;
      index++;
      while (isDigit(character(index))) {
        index++;
      }
    }
    while ("fFlL".indexOf(character(index)) >= 0) {
      index++;
    }
    return index;
  }

  private int identifierLength() {
    int index = position;
    if (".@#$".indexOf(character(index)) >= 0) {
      index++;
    }
    int start = index;
    while (isAlphaNumeric(character(index)) || (character(index) == '_')) {
      index++;
    }
    return index == start ? 0 : (index - position);
  }

  private int pairedRunLength(char opening, char closing) {
    int openings = runLength(position, opening);
    if (openings == 0) {
      return 0;
    }
    int closings = runLength(position + openings, closing);
    return closings == 0 ? 0 : (openings + closings);
  }

  private int dollarOpeningRunLength() {
    if (character(position) != '$') {
      return 0;
    }
    int openings = Math.max(
      runLength(position + 1, '('),
      Math.max(runLength(position + 1, '{'), runLength(position + 1, '[')));
    return openings == 0 ? 0 : (openings + 1);
  }

  private int followedRunsLength(char first, char second) {
    int firstLength = runLength(position, first);
    if (firstLength == 0) {
      return 0;
    }
    int secondLength = runLength(position + firstLength, second);
    return secondLength == 0 ? 0 : (firstLength + secondLength);
  }

  private int comparisonLength() {
    int index = position;
    while (character(index) == '<' || character(index) == '>') {
      index++;
    }
    int equals = runLength(index, '=');
    if (equals == 0) {
      return 0;
    }
    index += equals;
    while (character(index) == '<' || character(index) == '>') {
      index++;
    }
    return index - position;
  }

  private int angleMarkerLength() {
    if (character(position) != '<') {
      return 0;
    }
    int index = position + 1;
    if (character(index) == '/') {
      index++;
    }
    return "?%!#@".indexOf(character(index)) < 0 ? 0 : (index + 1 - position);
  }

  private int twoCharacterOperatorLength(String firstCharacters, char second) {
    return firstCharacters.indexOf(character(position)) >= 0 && character(position + 1) == second ? 2 : 0;
  }

  private int anyRunLength(String characters) {
    int index = position;
    while (characters.indexOf(character(index)) >= 0) {
      index++;
    }
    return index - position;
  }

  private int assignmentOperatorLength() {
    if ("-+*/%&|^~:".indexOf(character(position)) < 0) {
      return 0;
    }
    int equals = runLength(position + 1, '=');
    return equals == 0 ? 0 : (equals + 1);
  }

  private int tildeOperatorLength() {
    return (character(position) == '!' || character(position) == '=') && character(position + 1) == '~' ? 2 : 0;
  }

  private int dottedRepetitionLength(char repeated) {
    if (character(position) != '.') {
      return 0;
    }
    int repeatedLength = runLength(position + 1, repeated);
    if (repeatedLength == 0) {
      return 0;
    }
    int length = repeatedLength + 1;
    return character(position + length) == '?' ? (length + 1) : length;
  }

  private int maxExact(String... values) {
    int maximum = 0;
    for (String value : values) {
      maximum = Math.max(maximum, exact(value));
    }
    return maximum;
  }

  private int exact(String value) {
    return startsWith(value) ? value.length() : 0;
  }

  private boolean startsWith(String value) {
    return input.startsWith(value, position);
  }

  private boolean atBeginningOfLine() {
    return position == 0 || isLineTerminator(input.charAt(position - 1));
  }

  private static boolean isLineTerminator(char value) {
    return value == '\n' || value == '\r' || value == '\u000B' || value == '\f' || value == '\u0085';
  }

  private int skipHorizontalWhitespace(int index) {
    while (hasHorizontalWhitespace(index)) {
      index++;
    }
    return index;
  }

  private boolean hasHorizontalWhitespace(int index) {
    return character(index) == ' ' || character(index) == '\t';
  }

  private int runLength(char value) {
    return runLength(position, value);
  }

  private int runLength(int start, char value) {
    int index = start;
    while (character(index) == value) {
      index++;
    }
    return index - start;
  }

  private char character(int index) {
    return index < input.length() ? input.charAt(index) : '\0';
  }

  private Match dynamic(Match best, int length) {
    String token = length == 0 ? null : truncate(input.substring(position, position + length));
    return better(best, length, token, State.INITIAL);
  }

  private static Match better(@Nullable Match best, int length, @Nullable String token, State nextState) {
    return length > 0 && (best == null || length > best.length()) ? new Match(length, token, nextState) : best;
  }

  private static int leadingInterpreterLength(String value) {
    int index = 0;
    while (index < value.length() && (isAsciiLetter(value.charAt(index)) || value.charAt(index) == '_')) {
      index++;
    }
    return index;
  }

  private static boolean isShebangPath(char value) {
    return isAlphaNumeric(value) || value == '_' || value == '/';
  }

  private static boolean isDirectShebang(char value) {
    return isAsciiLetter(value) || value == '_' || value == '/';
  }

  private static boolean isAlphaNumeric(char value) {
    return isAsciiLetter(value) || isDigit(value);
  }

  private static boolean isAsciiLetter(char value) {
    return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
  }

  private static boolean isDigit(char value) {
    return value >= '0' && value <= '9';
  }

  private static boolean isHex(char value) {
    return isDigit(value) || (value >= 'A' && value <= 'F') || (value >= 'a' && value <= 'f');
  }

  private static String truncate(String token) {
    return token.length() <= MAX_TOKEN_BYTES ? token : token.substring(0, MAX_TOKEN_BYTES);
  }

  private record Match(int length, @Nullable String token, State nextState) {
  }

  private enum State {
    INITIAL,
    SHEBANG_REST,
    C_COMMENT,
    XML_COMMENT,
    HASKELL_COMMENT,
    OCAML_COMMENT,
    PYTHON_DOUBLE_COMMENT,
    PYTHON_SINGLE_COMMENT,
    ROFF_COMMENT,
    LEAN_COMMENT,
    DOUBLE_QUOTED_STRING,
    SINGLE_QUOTED_STRING
  }
}
