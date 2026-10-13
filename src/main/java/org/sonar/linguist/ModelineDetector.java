/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ModelineDetector {
  private static final int SEARCH_LINES = 5;
  private static final Pattern EMACS_MODELINE = Pattern.compile("-\\*-([^\\r\\n]*?)-\\*-");
  private static final Pattern EMACS_SIMPLE_MODE = Pattern.compile("^[ \\t]*([^:;\\s]+)[ \\t]*$");
  private static final Pattern EMACS_PROPERTY_MODE = Pattern.compile(
    "(?:^|[ \\t;])mode[ \\t]*:[ \\t]*([^:;\\s]+)(?=$|[ \\t;])",
    Pattern.CASE_INSENSITIVE);
  private static final Pattern VIM_MODELINE = Pattern.compile(
    "(?:(?:^|[ \\t])vi(?:m(?:[<=>]?\\d+)?)?|[ \\t]ex):",
    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
  private static final Pattern VIM_SET = Pattern.compile("^[ \\t]*set?[ \\t]", Pattern.CASE_INSENSITIVE);
  private static final Pattern VIM_LANGUAGE = Pattern.compile(
    "(?:^|[ \\t:])(?:filetype|ft|syntax)[ \\t]*=(\\w+)(?=$|\\s|:)",
    Pattern.CASE_INSENSITIVE);

  private ModelineDetector() {
  }

  static Optional<Language> detect(FileContent content, LanguageCatalog catalog) {
    ScopedLines lines = scopedLines(content);
    if (lines.header().contains("UseVimball")) {
      return Optional.empty();
    }
    String scope = lines.header() + lines.footer();
    return emacsMode(scope)
      .or(() -> vimMode(scope))
      .flatMap(catalog::byAlias);
  }

  private static Optional<String> emacsMode(String content) {
    Matcher modeline = EMACS_MODELINE.matcher(content);
    while (modeline.find()) {
      String options = modeline.group(1);
      Optional<String> simpleMode = mode(EMACS_SIMPLE_MODE, options);
      if (simpleMode.isPresent()) {
        return simpleMode;
      }
      Optional<String> propertyMode = mode(EMACS_PROPERTY_MODE, options);
      if (propertyMode.isPresent()) {
        return propertyMode;
      }
    }
    return Optional.empty();
  }

  private static Optional<String> mode(Pattern pattern, String content) {
    Matcher matcher = pattern.matcher(content);
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  private static Optional<String> vimMode(String content) {
    Matcher modeline = VIM_MODELINE.matcher(content);
    while (modeline.find()) {
      int lineEnd = content.indexOf('\n', modeline.end());
      String options = content.substring(modeline.end(), lineEnd < 0 ? content.length() : lineEnd);
      if (VIM_SET.matcher(options).find() && options.indexOf(':') < 0) {
        continue;
      }
      Matcher language = VIM_LANGUAGE.matcher(options);
      if (language.find()) {
        return Optional.of(language.group(1));
      }
    }
    return Optional.empty();
  }

  private static ScopedLines scopedLines(FileContent content) {
    List<Range> first = new ArrayList<>(SEARCH_LINES);
    Deque<Range> last = new ArrayDeque<>(SEARCH_LINES);
    int start = 0;
    int index = 0;
    while (index < content.length()) {
      int newlineLength = newlineLength(content, index);
      if (newlineLength == 0) {
        index++;
        continue;
      }
      addLine(new Range(start, index), first, last);
      index += newlineLength;
      start = index;
    }
    if (start < content.length()) {
      addLine(new Range(start, content.length()), first, last);
    }
    return new ScopedLines(join(content, first), join(content, last));
  }

  private static int newlineLength(FileContent content, int index) {
    if (content.byteAt(index) == '\r') {
      return index + 1 < content.length() && content.byteAt(index + 1) == '\n' ? 2 : 1;
    }
    return content.byteAt(index) == '\n' ? 1 : 0;
  }

  private static void addLine(Range range, List<Range> first, Deque<Range> last) {
    if (first.size() < SEARCH_LINES) {
      first.add(range);
    }
    if (last.size() == SEARCH_LINES) {
      last.removeFirst();
    }
    last.addLast(range);
  }

  private static String join(FileContent content, Iterable<Range> ranges) {
    StringBuilder result = new StringBuilder();
    for (Range range : ranges) {
      if (!result.isEmpty()) {
        result.append('\n');
      }
      result.append(new String(content.bytes(), range.start(), range.length(), StandardCharsets.ISO_8859_1));
    }
    return result.toString();
  }

  private record Range(int start, int end) {
    int length() {
      return end - start;
    }
  }

  private record ScopedLines(String header, String footer) {
  }
}
