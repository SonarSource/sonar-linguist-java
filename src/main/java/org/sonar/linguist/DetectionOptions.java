/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

/** Options controlling standalone-file language detection. */
public record DetectionOptions(boolean allowEmpty) {
  private static final DetectionOptions DEFAULTS = new DetectionOptions(false);

  /** Returns the default options, which reject empty files before running detection strategies. */
  public static DetectionOptions defaults() {
    return DEFAULTS;
  }
}
