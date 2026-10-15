/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** Immutable indexes over the merged language definitions. */
final class LanguageCatalog {
  private static final String EXTENSIONS_FIELD = "extensions";
  private static final String GROUP_FIELD = "group";
  private static final Map<String, String> EXTENSION_DEFAULTS =
      Map.of(".sql", "SQL", ".h", "C", ".rs", "Rust", ".html", "HTML");
  private static final Set<String> LANGUAGE_FIELDS = Set.of(
    "ace_mode",
    "aliases",
    "codemirror_mime_type",
    "codemirror_mode",
    "color",
    EXTENSIONS_FIELD,
    "filenames",
    "fs_name",
    GROUP_FIELD,
    "interpreters",
    "language_id",
    "searchable",
    "tm_scope",
    "type",
    "wrap");

  private final Map<String, Language> languages;
  private final Map<String, Language> aliases;
  private final Map<String, Language> classifierNames;
  private final Map<String, List<Language>> filenames;
  private final Map<String, List<Language>> extensions;
  private final Map<String, List<Language>> interpreters;
  private final List<String> genericExtensions;

  private static final class BundledCatalog {
    private static final BundledData DATA = loadBundledData();
    private static final LanguageCatalog INSTANCE = new LanguageCatalog(DATA, Map.of());

    private static BundledData loadBundledData() {
      Map<Integer, Definition> definitions = parseDefinitions(loadYaml("languages.yml"));
      Object popularYaml = loadYaml("popular.yml");
      Object genericYaml = loadYaml("generic.yml");
      CatalogValidator.validate(
        definitions.values(),
        popularYaml,
        genericYaml,
        loadYaml("heuristics.yml"));
      return new BundledData(
        immutableMap(definitions),
        Set.copyOf(stringList(popularYaml, "popular languages")),
        List.copyOf(stringList(((Map<?, ?>) genericYaml).get(EXTENSIONS_FIELD), "generic extensions")));
    }

    private static List<String> stringList(Object value, String description) {
      if (!(value instanceof List<?> values) || values.stream().anyMatch(item -> !(item instanceof String))) {
        throw new IllegalArgumentException(description + " must be a list of strings");
      }
      return values.stream().map(String.class::cast).toList();
    }
  }

  static LanguageCatalog bundled() {
    return BundledCatalog.INSTANCE;
  }

  LanguageCatalog(InputStream customLanguages) {
    this(BundledCatalog.DATA, parseDefinitions(loadCustomYaml(customLanguages, "custom language")));
  }

  private LanguageCatalog(BundledData bundledData, Map<Integer, Definition> customDefinitions) {
    Map<Integer, Definition> definitions = new LinkedHashMap<>(bundledData.definitions());
    definitions.putAll(customDefinitions);
    Map<String, Definition> definitionsByName = new LinkedHashMap<>();
    Set<String> normalizedNames = new HashSet<>();
    for (Definition definition : definitions.values()) {
      if (definitionsByName.putIfAbsent(definition.name(), definition) != null
        || !normalizedNames.add(normalizeAlias(definition.name()))) {
        throw new IllegalArgumentException("Conflicting language name: " + definition.name());
      }
    }
    Map<String, Language> constructed = new HashMap<>();
    for (String name : definitionsByName.keySet()) {
      buildLanguage(name, definitionsByName, bundledData.popularNames(), constructed, new HashSet<>());
    }
    Map<String, Language> languageIndex = new LinkedHashMap<>();
    Map<String, Language> aliasIndex = new LinkedHashMap<>();
    Map<String, List<Language>> filenameIndex = new LinkedHashMap<>();
    Map<String, List<Language>> extensionIndex = new LinkedHashMap<>();
    Map<String, List<Language>> interpreterIndex = new LinkedHashMap<>();
    for (String name : definitionsByName.keySet()) {
      Language language = constructed.get(name);
      languageIndex.put(name, language);
      index(filenameIndex, language.filenames(), language);
      indexExtensions(extensionIndex, language.extensions(), language);
      index(interpreterIndex, language.interpreters(), language);
    }
    // Canonical names win over conflicting aliases.
    for (Language language : languageIndex.values()) {
      aliasIndex.put(normalizeAlias(language.name()), language);
    }
    Map<String, Language> classifierNameIndex = new LinkedHashMap<>(languageIndex);
    languageIndex.values().stream()
        .sorted(Comparator.comparing(Language::name))
        .forEach(language -> {
          for (String alias : language.aliases()) {
            aliasIndex.putIfAbsent(normalizeAlias(alias), language);
          }
          language.filesystemName().ifPresent(filesystemName -> {
            Language conflict = classifierNameIndex.putIfAbsent(filesystemName, language);
            if (conflict != null && conflict != language) {
              throw new IllegalArgumentException("Conflicting classifier name: " + filesystemName);
            }
          });
        });
    languages = immutableMap(languageIndex);
    aliases = immutableMap(aliasIndex);
    classifierNames = immutableMap(classifierNameIndex);
    filenames = immutableIndex(filenameIndex);
    extensions = immutableIndex(extensionIndex);
    interpreters = immutableIndex(interpreterIndex);
    genericExtensions = bundledData.genericExtensions();
  }

  private record BundledData(
    Map<Integer, Definition> definitions,
    Set<String> popularNames,
    List<String> genericExtensions) {}

  record Definition(
    int languageId,
    String name,
    LanguageType type,
    List<String> aliases,
    List<String> extensions,
    List<String> filenames,
    List<String> interpreters,
    @Nullable String filesystemName,
    @Nullable String color,
    @Nullable String group,
    String tmScope,
    String aceMode,
    @Nullable String codeMirrorMode,
    @Nullable String codeMirrorMimeType,
    boolean wrap) {}

  static Map<Integer, Definition> parseDefinitions(@Nullable Object yaml) {
    Map<Integer, Definition> definitions = new LinkedHashMap<>();
    if (yaml == null) {
      return definitions;
    }
    if (!(yaml instanceof Map<?, ?> entries)) {
      throw new IllegalArgumentException("Language definitions must be a YAML mapping");
    }
    for (var entry : entries.entrySet()) {
      Definition definition = parseDefinition(entry);
      if (definitions.putIfAbsent(definition.languageId(), definition) != null) {
        throw new IllegalArgumentException("Duplicate language_id: " + definition.languageId());
      }
    }
    return definitions;
  }

  private static Definition parseDefinition(Map.Entry<?, ?> entry) {
    String name = languageName(entry.getKey());
    Map<?, ?> fields = definitionFields(entry.getValue(), name);
    validateFields(fields, name);
    optionalBoolean(fields, "searchable", name, true);
    return new Definition(
      languageId(fields, name),
      name,
      languageType(fields, name),
      stringList(fields, "aliases", name),
      stringList(fields, EXTENSIONS_FIELD, name),
      stringList(fields, "filenames", name),
      stringList(fields, "interpreters", name),
      optionalString(fields, "fs_name", name),
      optionalString(fields, "color", name),
      optionalGroup(fields, name),
      optionalString(fields, "tm_scope", name, "none"),
      optionalString(fields, "ace_mode", name, "text"),
      optionalString(fields, "codemirror_mode", name),
      optionalString(fields, "codemirror_mime_type", name),
      optionalBoolean(fields, "wrap", name, false));
  }

  private static void validateFields(Map<?, ?> fields, String language) {
    for (Object field : fields.keySet()) {
      if (!(field instanceof String fieldName) || !LANGUAGE_FIELDS.contains(fieldName)) {
        throw new IllegalArgumentException("Unknown language field for " + language + ": " + field);
      }
    }
  }

  private static String languageName(Object key) {
    if (!(key instanceof String name) || name.isBlank()) {
      throw new IllegalArgumentException("Language names must be nonblank strings");
    }
    return name;
  }

  private static Map<?, ?> definitionFields(Object value, String name) {
    if (!(value instanceof Map<?, ?> fields)) {
      throw new IllegalArgumentException("Language definition must be a mapping: " + name);
    }
    return fields;
  }

  private static int languageId(Map<?, ?> fields, String name) {
    if (!(fields.get("language_id") instanceof Integer id)) {
      throw new IllegalArgumentException("language_id must be a 32-bit integer for " + name);
    }
    return id;
  }

  private static LanguageType languageType(Map<?, ?> fields, String name) {
    try {
      if (!(fields.get("type") instanceof String value)) {
        throw new IllegalArgumentException("Missing type");
      }
      return LanguageType.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid language type for " + name + ": " + fields.get("type"), e);
    }
  }

  private static String optionalGroup(Map<?, ?> fields, String name) {
    Object group = fields.get(GROUP_FIELD);
    if (fields.containsKey(GROUP_FIELD) && (!(group instanceof String value) || value.isBlank())) {
      throw new IllegalArgumentException("group must be a nonblank string for " + name);
    }
    return (String) group;
  }

  private static List<String> stringList(Map<?, ?> fields, String field, String language) {
    if (!fields.containsKey(field)) {
      return List.of();
    }
    if (!(fields.get(field) instanceof List<?> values)
        || values.stream().anyMatch(value -> !(value instanceof String string) || string.isBlank())) {
      throw new IllegalArgumentException(field + " must be a list of nonblank strings for " + language);
    }
    return values.stream().map(String.class::cast).toList();
  }

  private static String optionalString(Map<?, ?> fields, String field, String language) {
    return optionalString(fields, field, language, null);
  }

  private static String optionalString(Map<?, ?> fields, String field, String language, @Nullable String defaultValue) {
    if (!fields.containsKey(field)) {
      return defaultValue;
    }
    Object value = fields.get(field);
    if (!(value instanceof String string) || string.isBlank()) {
      throw new IllegalArgumentException(field + " must be a nonblank string for " + language);
    }
    return string;
  }

  private static boolean optionalBoolean(Map<?, ?> fields, String field, String language, boolean defaultValue) {
    if (!fields.containsKey(field)) {
      return defaultValue;
    }
    if (!(fields.get(field) instanceof Boolean value)) {
      throw new IllegalArgumentException(field + " must be a boolean for " + language);
    }
    return value;
  }

  private static Language buildLanguage(
    String name,
    Map<String, Definition> definitions,
    Set<String> popularNames,
    Map<String, Language> constructed,
    Set<String> visiting) {
    Language existing = constructed.get(name);
    if (existing != null) {
      return existing;
    }
    Definition definition = definitions.get(name);
    if (definition == null) {
      throw new IllegalArgumentException("Unknown language group: " + name);
    }
    if (!visiting.add(name)) {
      throw new IllegalArgumentException("Language group cycle: " + name);
    }
    try {
      Language group = null;
      if (definition.group() != null && !definition.group().equals(name)) {
        if (!definitions.containsKey(definition.group())) {
          throw new IllegalArgumentException(
            "Unknown language group for " + name + ": " + definition.group());
        }
        group = buildLanguage(definition.group(), definitions, popularNames, constructed, visiting);
      }
      List<String> interpreters = new ArrayList<>(definition.interpreters());
      interpreters.sort(String::compareTo);
      Language language = new Language(
        definition.languageId(),
        name,
        definition.type(),
        definition.aliases(),
        definition.extensions(),
        definition.filenames(),
        interpreters,
        definition.filesystemName(),
        definition.color(),
        group,
        definition.tmScope(),
        definition.aceMode(),
        definition.codeMirrorMode(),
        definition.codeMirrorMimeType(),
        definition.wrap(),
        popularNames.contains(name));
      constructed.put(name, language);
      return language;
    } finally {
      visiting.remove(name);
    }
  }

  static Object loadCustomYaml(InputStream input, String description) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    try {
      return new Yaml(new SafeConstructor(options)).load(input);
    } catch (YAMLException e) {
      for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
        if (cause instanceof IOException io) {
          throw new UncheckedIOException("Cannot read " + description + " YAML", io);
        }
      }
      throw new IllegalArgumentException("Invalid " + description + " YAML: " + e.getMessage(), e);
    }
  }

  Optional<Language> detect(String path) {
    List<Language> named = findByFilename(path);
    if (!named.isEmpty()) {
      return Optional.of(preferredLanguage(null, named));
    }
    return detectByExtension(path);
  }

  List<Language> findByFilename(String path) {
    String filename = path.substring(path.lastIndexOf('/') + 1);
    return filenames.getOrDefault(filename, List.of());
  }

  List<Language> findByExtension(String path) {
    String filename = path.substring(path.lastIndexOf('/') + 1);
    return extensionMatch(filename).map(ExtensionMatch::candidates).orElseGet(List::of);
  }

  boolean hasGenericExtension(String path) {
    String filename = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    return genericExtensions.stream().anyMatch(filename::endsWith);
  }

  List<Language> findByInterpreter(String interpreter) {
    return interpreters.getOrDefault(interpreter, List.of());
  }

  private Optional<Language> detectByExtension(String filename) {
    return extensionMatch(filename).map(match -> preferredLanguage(match));
  }

  private Optional<ExtensionMatch> extensionMatch(String filename) {
    // Visit suffixes from longest to shortest, preserving case-specific
    // extensions where upstream distinguishes upper and lower case.
    for (int dot = filename.indexOf('.'); dot >= 0; dot = filename.indexOf('.', dot + 1)) {
      Optional<ExtensionMatch> match = indexedExtension(filename.substring(dot));
      if (match.isPresent()) {
        return match;
      }
    }
    return Optional.empty();
  }

  private Optional<ExtensionMatch> indexedExtension(String extension) {
    String normalizedExtension = extension.toLowerCase(Locale.ROOT);
    List<Language> candidates = extensions.get(normalizedExtension);
    return candidates == null
      ? Optional.empty()
      : Optional.of(new ExtensionMatch(normalizedExtension, candidates));
  }

  private static Language preferredLanguage(ExtensionMatch match) {
    return preferredLanguage(EXTENSION_DEFAULTS.get(match.extension()), match.candidates());
  }

  private static Language preferredLanguage(@Nullable String preferred, List<Language> candidates) {
    if (preferred == null) {
      return alphabeticallyFirst(candidates);
    }
    return candidates.stream()
      .filter(language -> language.name().equals(preferred))
      .findFirst()
      .orElseGet(() -> alphabeticallyFirst(candidates));
  }

  private static Language alphabeticallyFirst(List<Language> candidates) {
    return candidates.stream().min(Comparator.comparing(Language::name)).orElseThrow();
  }

  private record ExtensionMatch(String extension, List<Language> candidates) {}

  Optional<Language> byAlias(String alias) {
    return Optional.ofNullable(aliases.get(normalizeAlias(alias)));
  }

  Optional<Language> byName(String name) {
    return Optional.ofNullable(languages.get(name));
  }

  Optional<Language> byClassifierName(String name) {
    return Optional.ofNullable(classifierNames.get(name));
  }

  static Language group(Language language) {
    return language.group();
  }

  static Object loadYaml(String name) {
    try (InputStream input = LanguageCatalog.class.getResourceAsStream(name)) {
      if (input == null) {
        throw new IllegalStateException("Missing language resource: " + name);
      }
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      return new Yaml(new SafeConstructor(options)).load(input);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load language resource: " + name, e);
    }
  }

  static String normalizeAlias(String alias) {
    return alias.toLowerCase(Locale.ROOT).replace(' ', '-');
  }

  private static void index(Map<String, List<Language>> index, List<String> values, Language language) {
    for (String name : values) {
      index.computeIfAbsent(name, key -> new ArrayList<>()).add(language);
    }
  }

  private static void indexExtensions(Map<String, List<Language>> index, List<String> values, Language language) {
    index(index, values.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList(), language);
  }

  private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  private static Map<String, List<Language>> immutableIndex(Map<String, List<Language>> index) {
    Map<String, List<Language>> immutable = new LinkedHashMap<>();
    index.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
    return Collections.unmodifiableMap(immutable);
  }

}
