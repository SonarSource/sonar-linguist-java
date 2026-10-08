/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/** A recognized language or a language group used in repository statistics. */
public final class Language {
  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private final int languageId;
  private final String name;
  private final LanguageType type;
  private final List<String> aliases;
  private final List<String> extensions;
  private final List<String> filenames;
  private final List<String> interpreters;
  private final String filesystemName;
  private final String color;
  private final Language group;
  private final String tmScope;
  private final String aceMode;
  private final String codeMirrorMode;
  private final String codeMirrorMimeType;
  private final boolean wrap;
  private final boolean popular;

  public Language(int languageId, String name, LanguageType type) {
    this(languageId, name, type, List.of(), List.of(), List.of(), List.of(), null, null, null,
      "none", "text", null, null, false, false);
  }

  Language(
    int languageId,
    String name,
    LanguageType type,
    List<String> configuredAliases,
    List<String> extensions,
    List<String> filenames,
    List<String> interpreters,
    @Nullable String filesystemName,
    @Nullable String color,
    @Nullable Language group,
    String tmScope,
    String aceMode,
    @Nullable String codeMirrorMode,
    @Nullable String codeMirrorMimeType,
    boolean wrap,
    boolean popular) {
    this.languageId = languageId;
    this.name = Objects.requireNonNull(name);
    this.type = Objects.requireNonNull(type);
    ArrayList<String> effectiveAliases = new ArrayList<>(configuredAliases.size() + 1);
    effectiveAliases.add(defaultAlias(name));
    effectiveAliases.addAll(configuredAliases);
    this.aliases = List.copyOf(effectiveAliases);
    this.extensions = List.copyOf(extensions);
    this.filenames = List.copyOf(filenames);
    this.interpreters = List.copyOf(interpreters);
    this.filesystemName = filesystemName;
    this.color = color;
    this.group = group;
    this.tmScope = Objects.requireNonNull(tmScope);
    this.aceMode = Objects.requireNonNull(aceMode);
    this.codeMirrorMode = codeMirrorMode;
    this.codeMirrorMimeType = codeMirrorMimeType;
    this.wrap = wrap;
    this.popular = popular;
  }

  /** Returns the upstream Linguist {@code language_id}, which is stable across language renames. */
  public int languageId() {
    return languageId;
  }

  /** Returns the upstream Linguist language name. */
  public String name() {
    return name;
  }

  /** Returns the upstream-compatible URL-escaped language name. */
  public String escapedName() {
    byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
    StringBuilder escaped = new StringBuilder(bytes.length);
    for (byte value : bytes) {
      int unsigned = Byte.toUnsignedInt(value);
      if (isUnescaped(unsigned)) {
        escaped.append((char) unsigned);
      } else {
        escaped.append('%');
        escaped.append(HEX[unsigned >>> 4]);
        escaped.append(HEX[unsigned & 0x0f]);
      }
    }
    return escaped.toString();
  }

  /** Returns the language category. */
  public LanguageType type() {
    return type;
  }

  /** Returns the alias derived from the canonical language name. */
  public String defaultAlias() {
    return aliases.getFirst();
  }

  /** Returns the alias derived from the canonical language name. */
  public String defaultAliasName() {
    return defaultAlias();
  }

  /** Returns the default alias followed by configured aliases. */
  public List<String> aliases() {
    return aliases;
  }

  /** Returns recognized filename extensions in upstream order. */
  public List<String> extensions() {
    return extensions;
  }

  /** Returns recognized exact filenames in upstream order. */
  public List<String> filenames() {
    return filenames;
  }

  /** Returns recognized interpreters in upstream-sorted order. */
  public List<String> interpreters() {
    return interpreters;
  }

  /** Returns the filesystem-safe language name when one is configured. */
  public Optional<String> filesystemName() {
    return Optional.ofNullable(filesystemName);
  }

  /** Returns the display color when one is configured. */
  public Optional<String> color() {
    return Optional.ofNullable(color);
  }

  /** Returns the direct parent language group, or this language when ungrouped. */
  public Language group() {
    return group == null ? this : group;
  }

  /** Returns the TextMate scope. */
  public String tmScope() {
    return tmScope;
  }

  /** Returns the Ace editor mode. */
  public String aceMode() {
    return aceMode;
  }

  /** Returns the CodeMirror mode when one is configured. */
  public Optional<String> codeMirrorMode() {
    return Optional.ofNullable(codeMirrorMode);
  }

  /** Returns the CodeMirror MIME type when one is configured. */
  public Optional<String> codeMirrorMimeType() {
    return Optional.ofNullable(codeMirrorMimeType);
  }

  /** Returns whether editors should wrap this language's lines. */
  public boolean wrap() {
    return wrap;
  }

  /** Returns whether upstream marks this language as popular. */
  public boolean popular() {
    return popular;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Language language)) {
      return false;
    }
    return languageId == language.languageId && Objects.equals(name, language.name);
  }

  @Override
  public int hashCode() {
    int result = Integer.hashCode(languageId);
    return 31 * result + Objects.hashCode(name);
  }

  @Override
  public String toString() {
    return name;
  }

  private static String defaultAlias(String name) {
    return name.toLowerCase(Locale.ROOT).replaceAll("\\s", "-");
  }

  private static boolean isUnescaped(int value) {
    return (value >= 'a' && value <= 'z')
      || (value >= 'A' && value <= 'Z')
      || (value >= '0' && value <= '9')
      || value == '_'
      || value == '-'
      || value == '.'
      || value == '~';
  }
}
