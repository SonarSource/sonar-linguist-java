/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.eclipse.jgit.attributes.Attribute;
import org.eclipse.jgit.attributes.Attributes;
import org.eclipse.jgit.attributes.AttributesNode;
import org.eclipse.jgit.attributes.AttributesNodeProvider;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Full committed-tree scan; no worktree reads, checkout, or cache mutation. */
final class RepositoryAnalyzer {
  private static final int MAX_DETECTION_BYTES = 128 * 1024;
  private static final int MAX_TREE_SIZE = 100_000;
  private static final AttributesNodeProvider COMMITTED_ONLY = new AttributesNodeProvider() {
    @Override
    public AttributesNode getGlobalAttributesNode() {
      return null;
    }

    @Override
    public AttributesNode getInfoAttributesNode() {
      return null;
    }
  };
  private final Linguist linguist;
  private final LanguageCatalog catalog;
  private final int maxTreeSize;

  RepositoryAnalyzer(Linguist linguist, LanguageCatalog catalog) {
    this(linguist, catalog, MAX_TREE_SIZE);
  }

  RepositoryAnalyzer(Linguist linguist, LanguageCatalog catalog, int maxTreeSize) {
    this.linguist = linguist;
    this.catalog = catalog;
    this.maxTreeSize = maxTreeSize;
  }

  RepositoryAnalysis analyze(Path path, String revision) {
    try (Repository repository = open(path); RevWalk revisions = new RevWalk(repository)) {
      ObjectId commitId = resolveCommit(repository, revision);
      if (commitId == null) {
        if (revision.equals("HEAD") && repository.exactRef(Constants.HEAD) != null
            && repository.exactRef(Constants.HEAD).getObjectId() == null) {
          return new RepositoryAnalysis("", 0, Map.of(), Map.of());
        }
        throw new IllegalArgumentException("Unknown commit revision: " + revision);
      }
      RevCommit commit = revisions.parseCommit(commitId);
      return scan(repository, commit);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot analyze repository: " + path, e);
    }
  }

  private static ObjectId resolveCommit(Repository repository, String revision) throws IOException {
    try {
      return repository.resolve(revision + "^{commit}");
    } catch (MissingObjectException | IncorrectObjectTypeException | AmbiguousObjectException e) {
      throw new IllegalArgumentException("Unknown commit revision: " + revision, e);
    }
  }

  private RepositoryAnalysis scan(Repository repository, RevCommit commit) throws IOException {
    if (treeSize(repository, commit.getTree(), maxTreeSize) >= maxTreeSize) {
      return new RepositoryAnalysis(commit.name(), 0, Map.of(), Map.of());
    }
    Map<String, Language> files = new TreeMap<>();
    Map<Language, Long> sizes = new TreeMap<>(java.util.Comparator.comparing(Language::name));
    try (TreeWalk tree = new TreeWalk(repository)) {
      tree.addTree(commit.getTree());
      tree.setRecursive(true);
      tree.setOperationType(TreeWalk.OperationType.CHECKOUT_OP);
      // Exclude .git/info/attributes and global configuration: results
      // depend solely on the selected commit's .gitattributes files.
      tree.setAttributesNodeProvider(COMMITTED_ONLY);
      while (tree.next()) {
        FileMode mode = tree.getFileMode(0);
        if (!mode.equals(FileMode.REGULAR_FILE) && !mode.equals(FileMode.EXECUTABLE_FILE)) {
          continue;
        }
        ObjectLoader blob = repository.open(tree.getObjectId(0), Constants.OBJ_BLOB);
        byte[] content;
        try (InputStream input = blob.openStream()) {
          content = input.readNBytes(MAX_DETECTION_BYTES);
        }
        Detection detected = linguist.detect(tree.getPathString(), content);
        Attributes attributes = tree.getAttributes();
        Optional<Language> language = detected.language();
        Attribute override = attributes.get("linguist-language");
        if (override != null && override.getState() == Attribute.State.CUSTOM) {
          language = catalog.byAlias(Objects.requireNonNull(override.getValue(), "linguist-language value"));
        }
        if (language.isPresent()) {
          Language detectedLanguage = language.orElseThrow();
          if (included(detected, detectedLanguage, attributes)) {
            Language group = LanguageCatalog.group(detectedLanguage);
            files.put(tree.getPathString(), group);
            sizes.merge(group, blob.getSize(), Long::sum);
          }
        }
      }
    }
    long total = sizes.values().stream().mapToLong(Long::longValue).sum();
    Map<Language, LanguageStats> statistics = new LinkedHashMap<>();
    sizes.forEach((language, bytes) -> statistics.put(language,
        new LanguageStats(bytes, total == 0 ? 0 : (bytes * 100.0 / total))));
    return new RepositoryAnalysis(commit.name(), total, statistics, files);
  }

  static int treeSize(Repository repository, RevTree root, int limit) throws IOException {
    int blobCount = 0;
    int treeCount = 0;
    try (TreeWalk tree = new TreeWalk(repository)) {
      tree.addTree(root);
      while (tree.next()) {
        if (tree.isSubtree()) {
          treeCount++;
          if (treeCount >= limit) {
            return limit;
          }
          tree.enterSubtree();
        } else if (tree.getFileMode(0).getObjectType() == Constants.OBJ_BLOB) {
          blobCount++;
          if (blobCount >= limit) {
            return limit;
          }
        }
      }
    }
    return blobCount;
  }

  private static boolean included(Detection detected, Language language, Attributes attributes) {
    if (detected.binary() || lfsTrackedPointer(detected, attributes)
        || flag(attributes, "linguist-generated", detected.generated())
        || flag(attributes, "linguist-vendored", detected.vendored())
        || flag(attributes, "linguist-documentation", detected.documentation())) {
      return false;
    }
    LanguageType type = language.type();
    return flag(attributes, "linguist-detectable",
        type == LanguageType.PROGRAMMING || type == LanguageType.MARKUP);
  }

  private static boolean lfsTrackedPointer(Detection detected, Attributes attributes) {
    Attribute filter = attributes.get("filter");
    return detected.lfsPointer() && filter != null
      && filter.getState() == Attribute.State.CUSTOM && "lfs".equals(filter.getValue());
  }

  private static boolean flag(Attributes attributes, String name, boolean fallback) {
    Attribute attribute = attributes.get(name);
    if (attribute == null) {
      return fallback;
    }
    return switch (attribute.getState()) {
      case SET -> true;
      case UNSET -> false;
      case CUSTOM -> !"false".equals(attribute.getValue());
      case UNSPECIFIED -> fallback;
    };
  }

  private static Repository open(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    if (!Files.isDirectory(absolute)) {
      throw new IllegalArgumentException("Repository directory does not exist: " + path);
    }
    FileRepositoryBuilder builder = new FileRepositoryBuilder().setMustExist(true);
    if (Files.isDirectory(absolute.resolve("objects")) && Files.exists(absolute.resolve("HEAD"))) {
      builder.setGitDir(absolute.toFile());
    } else {
      builder.findGitDir(absolute.toFile());
      if (builder.getGitDir() == null) {
        throw new IllegalArgumentException("Not a Git repository: " + path);
      }
    }
    return builder.build();
  }
}
