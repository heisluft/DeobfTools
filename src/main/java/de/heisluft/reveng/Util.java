package de.heisluft.reveng;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * This Interface provides convenience methods to its implementors
 */
public interface Util {
  /**
   * Returns if a given value has none of the given flags. For each flag {@code (value & flag) !=
   * flag} must hold true
   *
   * @param value
   *     The value to check
   * @param flags
   *     all flags that must not be present
   *
   * @return if none of the given flags are present
   */
  static boolean hasNone(int value, int... flags) {
    for(int flag : flags)
      if((value & flag) == flag) return false;
    return true;
  }

  /**
   * Creates a zip file system for a given path and returns it. If the requested path does not exist
   * it will be created.
   *
   * @param path
   *     the path to create a file system for
   *
   * @return the created file system
   *
   * @throws IOException
   *     if the FileSystem could not be created
   */
  default FileSystem createFS(Path path) throws IOException {
    Map<String, String> map = new HashMap<>(1);
    map.put("create", "true");
    URI uri = URI.create("jar:file:/" + path.toAbsolutePath().toString().replace('\\', '/'));
    return FileSystems.newFileSystem(uri, map);
  }

  /**
   * Retrieves a value from a map with a fallback. If a mapping for the key does not exist, it will
   * be created within the map and fallback is returned.
   *
   * @param map
   *     the map to look in
   * @param key
   *     the key to retrieve / store a value for
   * @param fallback
   *     the value to map to key if key is not already mapped
   * @param <K>
   *     the keys type
   * @param <V>
   *     the values type
   *
   * @return the value mapped to the specified key
   */
  default <K, V> V getOrPut(Map<K, V> map, K key, V fallback) {
    if(map.containsKey(key)) return map.get(key);
    map.put(key, fallback);
    return fallback;
  }

  /**
   * Parses the given file to a ClassNode
   *
   * @param path
   *     the path to parse
   *
   * @return the parsed node
   *
   * @throws IOException
   *     if the path could not be read
   */
  default ClassNode parseClass(Path path) throws IOException {
    ClassReader cr = new ClassReader(Files.readAllBytes(path));
    ClassNode result = new ClassNode(Opcodes.ASM7);
    cr.accept(result, 0);
    return result;
  }

  /**
   * Returns whether a given Class has an the .class file extension
   *
   * @param path
   *     the path to test
   *
   * @return whether a given path has a class file extension
   */
  default boolean hasClassExt(Path path) {
    return path.toString().endsWith(".class");
  }

  /**
   * Joins the given Collection of characters to a string
   *
   * @param chars
   *     the chars to be joined
   *
   * @return the joined string
   */
  default String toString(Collection<Character> chars) {
    StringBuilder builder = new StringBuilder();
    chars.forEach(builder::append);
    return builder.toString();
  }

  /**
   * Splits a String at the given index.
   *
   * @param toSplit
   *     the String to be split
   * @param index
   *     the index on which to split on
   *
   * @return the pair of split halves
   */
  default String[] splitAt(String toSplit, int index) {
    return new String[]{toSplit.substring(0, index), toSplit.substring(index + 1)};
  }

  /**
   * Joins an array of strings together with spaces
   *
   * @param toJoin
   *     the String array to join
   *
   * @return the joined string
   */
  default String join(String[] toJoin) {
    StringBuilder builder = new StringBuilder(toJoin[0]);
    for(int i = 1; i < toJoin.length; i++) builder.append(" ").append(toJoin[i]);
    return builder.toString();
  }

}
