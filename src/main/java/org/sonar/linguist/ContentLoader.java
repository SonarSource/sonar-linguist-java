/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.IOException;
import java.io.InputStream;

/**
 * Lazily opens the content of a file being detected.
 *
 * <p>Linguist calls {@link #open()} exactly once per detection, reads the stream
 * completely, and closes it. All analysis stages share that byte snapshot.
 */
@FunctionalInterface
public interface ContentLoader {

  /**
   * Opens a new stream on the file content.
   *
   * @return a stream positioned at the beginning of the raw file bytes
   * @throws IOException if the content cannot be read
   */
  InputStream open() throws IOException;
}
