/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class XmlDetector {
  private static final int SEARCH_LINES = 2;
  private static final String XML_DECLARATION = "xml version=";

  private XmlDetector() {
  }

  static List<Language> detect(FileContent content, LanguageCatalog catalog, List<Language> candidates) {
    if (!candidates.isEmpty() || !firstLines(content).contains(XML_DECLARATION)) {
      return candidates;
    }
    return catalog.byAlias("XML").map(List::of).orElseGet(List::of);
  }

  private static String firstLines(FileContent content) {
    int lines = 0;
    int index = 0;
    while (index < content.length() && lines < SEARCH_LINES) {
      int newlineLength = newlineLength(content, index);
      index += Math.max(newlineLength, 1);
      if (newlineLength > 0) {
        lines++;
      }
    }
    return new String(content.bytes(), 0, index, StandardCharsets.ISO_8859_1);
  }

  private static int newlineLength(FileContent content, int index) {
    if (content.byteAt(index) == '\r') {
      return index + 1 < content.length() && content.byteAt(index + 1) == '\n' ? 2 : 1;
    }
    return content.byteAt(index) == '\n' ? 1 : 0;
  }
}
