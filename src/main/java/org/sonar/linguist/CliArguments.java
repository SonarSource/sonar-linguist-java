/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

record CliArguments(List<Path> files, DetectionOptions detectionOptions) {
  private static final String ALLOW_EMPTY = "--allow-empty";

  CliArguments {
    files = List.copyOf(files);
    Objects.requireNonNull(detectionOptions, "detectionOptions");
  }

  static CliArguments parse(String[] arguments) {
    Objects.requireNonNull(arguments, "arguments");
    List<Path> files = new ArrayList<>();
    boolean allowEmpty = false;
    boolean optionsEnded = false;
    for (String argument : arguments) {
      Objects.requireNonNull(argument, "argument");
      if (!optionsEnded && argument.equals("--")) {
        optionsEnded = true;
      } else if (!optionsEnded && argument.equals(ALLOW_EMPTY)) {
        allowEmpty = true;
      } else if (!optionsEnded && argument.startsWith("-")) {
        throw new IllegalArgumentException("Unknown option: " + argument);
      } else if (argument.isBlank()) {
        throw new IllegalArgumentException("File path must not be blank");
      } else {
        files.add(Path.of(argument));
      }
    }
    if (files.isEmpty()) {
      throw new IllegalArgumentException("At least one file is required");
    }
    return new CliArguments(files, new DetectionOptions(allowEmpty));
  }
}
