/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.PrintStream;
import java.util.List;
import java.util.function.Function;

/** Analyzes explicitly listed files. */
public final class Main {
  private static final int ANALYSIS_ERROR = 1;
  private static final int USAGE_ERROR = 2;
  private static final String USAGE = "Usage: linguist [--allow-empty] [--] <file>...";

  private Main() {}

  public static void main(String[] args) {
    int exitCode = run(args, System.out, System.err);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  static int run(String[] args, PrintStream output, PrintStream error) {
    return run(args, output, error, arguments -> new CliAnalyzer(Linguist.create()).analyze(arguments));
  }

  static int run(
    String[] args,
    PrintStream output,
    PrintStream error,
    Function<CliArguments, List<Detection>> analyze) {
    CliArguments arguments;
    try {
      arguments = CliArguments.parse(args);
    } catch (IllegalArgumentException exception) {
      error.println("error: " + exception.getMessage());
      error.println(USAGE);
      return USAGE_ERROR;
    }
    try {
      CliOutput.write(analyze.apply(arguments), output);
      return 0;
    } catch (RuntimeException exception) {
      String message = exception.getMessage();
      error.println("error: " + (message == null || message.isBlank() ? "Analysis failed" : message));
      return ANALYSIS_ERROR;
    }
  }
}
