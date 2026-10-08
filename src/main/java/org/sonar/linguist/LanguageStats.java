/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

/**
 * Statistics for one language group across included repository files.
 *
 * @param bytes total size in bytes of included files in this group
 * @param percentage share of included repository bytes, in the range 0 to 100
 */
public record LanguageStats(long bytes, double percentage) {}
