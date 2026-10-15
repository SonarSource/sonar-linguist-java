/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

/** The ordered upstream Linguist stages used to detect a file's language. */
public enum DetectionStrategy {
  MODELINE("Modeline"),
  FILENAME("Filename"),
  SHEBANG("Shebang"),
  EXTENSION("Extension"),
  XML("XML"),
  MANPAGE("Manpage"),
  HEURISTICS("Heuristics"),
  CLASSIFIER("Classifier"),
  NO_MATCH("No match");

  private final String upstreamName;

  DetectionStrategy(String upstreamName) {
    this.upstreamName = upstreamName;
  }

  /** Returns the stable name used by upstream strategy diagnostics. */
  public String upstreamName() {
    return upstreamName;
  }
}
