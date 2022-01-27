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

public interface Util {
   default FileSystem createFS(Path path) throws IOException {
    Map<String, String> map = new HashMap<>(1);
    map.put("create", "true");
    URI uri = URI.create("jar:file:/" + path.toAbsolutePath().toString().replace('\\', '/'));
    return FileSystems.newFileSystem(uri, map);
  }

  /**
   * Parses the given file to a ClassNode
   * @param path the path to parse
   * @return the parsed node
   * @throws IOException if the path could not be read
   */
  default ClassNode parseClass(Path path) throws IOException {
    ClassReader cr = new ClassReader(Files.readAllBytes(path));
    ClassNode result = new ClassNode(Opcodes.ASM7);
    cr.accept(result, 0);
    return result;
  }

  default boolean isClass(Path p) {
     return p.toString().endsWith(".class");
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

}
