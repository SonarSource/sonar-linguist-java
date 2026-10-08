/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

final class ManpageDetector {
  private static final Pattern MANPAGE_EXTENSION = Pattern.compile(
    "\\.(?:[1-9](?!\\d)[a-z_\\d]*|0p|n|man|mdoc)(?:\\.in)?$",
    Pattern.CASE_INSENSITIVE);
  private static final List<String> LANGUAGES = List.of("Roff Manpage", "Roff");

  private ManpageDetector() {
  }

  static List<Language> detect(String path, LanguageCatalog catalog, List<Language> candidates) {
    if (!candidates.isEmpty() || !MANPAGE_EXTENSION.matcher(path).find()) {
      return candidates;
    }
    return LANGUAGES.stream()
      .map(catalog::byAlias)
      .flatMap(Optional::stream)
      .toList();
  }
}
