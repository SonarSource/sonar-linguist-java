/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShebangDetector {
  private static final int MULTILINE_SEARCH_LINES = 5;
  private static final Pattern SHEBANG_PATH = Pattern.compile("^#!\\s*\\S+");
  private static final Pattern ENV_OPTION = Pattern.compile("(?:-[i0uCSv]*|--\\S++)\\s++");
  private static final Pattern ENV_ASSIGNMENT = Pattern.compile("[^\\s=]++=\\S++\\s++");
  private static final Pattern NEXT_WORD = Pattern.compile("\\S+");
  private static final Pattern VERSION_SUFFIX = Pattern.compile("(\\.\\d+)$");
  private static final Pattern EXEC_HACK = Pattern.compile("exec (\\w+)[\\s\"']+\\$0[\\s\"']+\\$@");
  private static final Pattern OSASCRIPT_LANGUAGE = Pattern.compile("-l\\s?");

  private ShebangDetector() {
  }

  static List<Language> detect(FileContent content, LanguageCatalog catalog, List<Language> candidates) {
    Optional<String> interpreter = interpreter(content);
    if (interpreter.isEmpty()) {
      return List.of();
    }
    List<Language> languages = catalog.findByInterpreter(interpreter.orElseThrow());
    if (candidates.isEmpty()) {
      return languages;
    }
    return candidates.stream().filter(languages::contains).toList();
  }

  static Optional<String> interpreter(FileContent content) {
    String data = new String(content.bytes(), StandardCharsets.ISO_8859_1);
    if (!data.startsWith("#!")) {
      return Optional.empty();
    }
    int firstLineEnd = data.indexOf('\n');
    String shebang = firstLineEnd < 0 ? data : data.substring(0, firstLineEnd);
    Matcher pathMatcher = SHEBANG_PATH.matcher(shebang);
    if (!pathMatcher.find()) {
      return Optional.empty();
    }

    String script = basename(pathMatcher.group());
    int position = pathMatcher.end();
    if (script.equals("env")) {
      position = skipWhitespace(shebang, position);
      position = skipEnvArguments(shebang, position);
      Matcher scriptMatcher = matcherAt(NEXT_WORD, shebang, position);
      if (!scriptMatcher.lookingAt()) {
        return Optional.empty();
      }
      script = scriptMatcher.group();
      position = scriptMatcher.end();
    }

    script = VERSION_SUFFIX.matcher(script).replaceFirst("");
    script = script.replaceFirst("^#!\\s*", "");
    if (script.equals("sh")) {
      Matcher exec = EXEC_HACK.matcher(firstLines(data, MULTILINE_SEARCH_LINES));
      if (exec.find()) {
        script = exec.group(1);
      }
    }
    if (script.equals("osascript") && OSASCRIPT_LANGUAGE.matcher(shebang.substring(position)).find()) {
      return Optional.empty();
    }
    return Optional.of(basename(script));
  }

  private static int skipWhitespace(String value, int position) {
    while (position < value.length() && Character.isWhitespace(value.charAt(position))) {
      position++;
    }
    return position;
  }

  private static int skipEnvArguments(String value, int position) {
    int nextPosition = envArgumentEnd(value, position);
    while (nextPosition > position) {
      position = nextPosition;
      nextPosition = envArgumentEnd(value, position);
    }
    return position;
  }

  private static int envArgumentEnd(String value, int position) {
    Matcher option = matcherAt(ENV_OPTION, value, position);
    if (option.lookingAt()) {
      return option.end();
    }
    Matcher assignment = matcherAt(ENV_ASSIGNMENT, value, position);
    return assignment.lookingAt() ? assignment.end() : position;
  }

  private static Matcher matcherAt(Pattern pattern, String value, int position) {
    Matcher matcher = pattern.matcher(value);
    matcher.region(position, value.length());
    return matcher;
  }

  private static String firstLines(String data, int count) {
    int index = 0;
    for (int line = 0; line < count && index < data.length(); line++) {
      int newline = data.indexOf('\n', index);
      if (newline < 0) {
        return data;
      }
      index = newline + 1;
    }
    return data.substring(0, index);
  }

  private static String basename(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }
}
