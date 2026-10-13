/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.List;

final class ExtensionDetector {
  private ExtensionDetector() {
  }

  static List<Language> detect(String path, LanguageCatalog catalog, List<Language> candidates) {
    if (catalog.hasGenericExtension(path)) {
      return candidates;
    }
    List<Language> languages = catalog.findByExtension(path);
    if (candidates.isEmpty()) {
      return languages;
    }
    return candidates.stream().filter(languages::contains).toList();
  }
}
