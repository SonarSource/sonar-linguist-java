/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.IOException;
import java.io.InputStream;

final class BundledClassifier {
  private static final String MODEL_RESOURCE = "classifier-model.bin";

  private BundledClassifier() {
  }

  private static final class Holder {
    private static final Classifier INSTANCE = load();

    private static Classifier load() {
      try (InputStream input = BundledClassifier.class.getResourceAsStream(MODEL_RESOURCE)) {
        if (input == null) {
          throw new IllegalStateException("Missing classifier model resource: " + MODEL_RESOURCE);
        }
        return new Classifier(ClassifierModelCodec.read(input));
      } catch (IOException e) {
        throw new IllegalStateException("Cannot load classifier model resource: " + MODEL_RESOURCE, e);
      }
    }
  }

  static Classifier get() {
    return Holder.INSTANCE;
  }
}
