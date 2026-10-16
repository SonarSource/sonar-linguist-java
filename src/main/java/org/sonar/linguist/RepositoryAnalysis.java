/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Language statistics for a committed Git repository snapshot.
 *
 * <p>Binary, generated, vendored, documentation, and unrecognized files are
 * normally excluded. Programming and markup languages are included by default;
 * repository attributes can override eligibility. Statistics aggregate by
 * upstream language group. Returned maps are immutable snapshots.
 *
 * @param commitId resolved commit object ID, or empty for an unborn repository
 * @param totalBytes total bytes of included files only
 * @param languages statistics keyed by language group
 * @param includedFiles repository-relative paths mapped to their language groups
 */
public record RepositoryAnalysis(
    String commitId,
    long totalBytes,
    Map<Language, LanguageStats> languages,
    Map<String, Language> includedFiles) {
  public RepositoryAnalysis {
    Objects.requireNonNull(commitId, "commitId");
    languages = Collections.unmodifiableMap(new LinkedHashMap<>(languages));
    includedFiles = Collections.unmodifiableMap(new LinkedHashMap<>(includedFiles));
  }
}
