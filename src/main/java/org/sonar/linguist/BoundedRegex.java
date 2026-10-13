/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

final class BoundedRegex {
  private static final int DEFAULT_CHARACTER_ACCESS_LIMIT = 5_000_000;

  private final Pattern pattern;
  private final int maximumInputBytes;
  private final int characterAccessLimit;

  private BoundedRegex(Pattern pattern, int maximumInputBytes, int characterAccessLimit) {
    this.pattern = pattern;
    this.maximumInputBytes = maximumInputBytes;
    this.characterAccessLimit = characterAccessLimit;
  }

  static BoundedRegex compile(String source, int maximumInputBytes) {
    return compile(source, maximumInputBytes, DEFAULT_CHARACTER_ACCESS_LIMIT);
  }

  static BoundedRegex compile(String source, int maximumInputBytes, int characterAccessLimit) {
    if (maximumInputBytes <= 0 || characterAccessLimit <= 0) {
      throw new IllegalArgumentException("Regex limits must be positive");
    }
    return new BoundedRegex(Pattern.compile(source, Pattern.MULTILINE), maximumInputBytes, characterAccessLimit);
  }

  boolean matches(byte[] content) {
    if (content.length > maximumInputBytes) {
      throw new EvaluationException("Regex input exceeds " + maximumInputBytes + " bytes");
    }
    String value = new String(content, StandardCharsets.ISO_8859_1);
    try {
      return pattern.matcher(new BudgetedCharSequence(value, new Budget(characterAccessLimit))).find();
    } catch (StackOverflowError error) {
      throw new EvaluationException("Regex exhausted the stack", error);
    }
  }

  static final class EvaluationException extends RuntimeException {
    private EvaluationException(String message) {
      super(message);
    }

    private EvaluationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final class Budget {
    private int remaining;

    private Budget(int remaining) {
      this.remaining = remaining;
    }

    private void consume() {
      remaining--;
      if (remaining < 0) {
        throw new EvaluationException("Regex execution budget exhausted");
      }
    }
  }

  private record BudgetedCharSequence(String value, int start, int end, Budget budget) implements CharSequence {
    private BudgetedCharSequence(String value, Budget budget) {
      this(value, 0, value.length(), budget);
    }

    @Override
    public int length() {
      return end - start;
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= length()) {
        throw new IndexOutOfBoundsException(index);
      }
      budget.consume();
      return value.charAt(start + index);
    }

    @Override
    public CharSequence subSequence(int subStart, int subEnd) {
      if (subStart < 0 || subEnd > length() || subStart > subEnd) {
        throw new IndexOutOfBoundsException(subStart + ".." + subEnd);
      }
      return new BudgetedCharSequence(value, start + subStart, start + subEnd, budget);
    }

    @Override
    public String toString() {
      return value.substring(start, end);
    }
  }
}
