package de.heisluft.deobf.tooling;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

/**
 * This Interface provides convenience methods to its implementors
 */
public interface Util {
  /**
   * A singleton Map mapping "create" -> "true", used as arguments for the file system creation within createFS;
   */
  Map<String, String> PROPS = Collections.singletonMap("create", "true");

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
    try {
      return FileSystems.newFileSystem(new URI("jar", path.toAbsolutePath().toUri().toString(), null), PROPS);
    } catch (URISyntaxException ex) {
      throw new AssertionError("A pre-validated uri is invalid?????", ex);
    }
  }

  /**
   * Parses all class files from a given jar file and groups them by their names.
   *
   * @param path
   *     the path to parse from
   * @return the resulting map, keys are ClassNode#name, values are the class nodes themselves
   * @throws IOException if any of the jars classes could not be parsed
   */
  default Map<String, ClassNode> parseClasses(Path path) throws IOException {
    return parseClasses(path, Collections.emptyList(), 0);
  }

  /**
   * Parses all class files from a given jar file and groups them by their names, excluding all files whose path start
   * with any of the strings provided by the list of ignored paths.
   *
   * @param path
   *     the path to parse from
   * @param ignored
   *     a list of strings to exclude a class from being parsed if its path starts with any of the given patterns.
   * @param parseFlags
   *     the flags passed to the class reader
   * @return the resulting map, keys are ClassNode#name, values are the class nodes themselves
   * @throws IOException if any of the jars classes could not be parsed
   */
  default Map<String, ClassNode> parseClasses(Path path, List<String> ignored, int parseFlags) throws IOException {
    Map<String, ClassNode> result = new HashMap<>();
    try(ZipFile zf = new ZipFile(path.toFile())) {
      zf.stream()
          .filter(e -> ignored.stream().noneMatch(e.getName()::startsWith) && e.getName().endsWith(".class"))
          .map(e -> {
            try(InputStream is = zf.getInputStream(e)) {
              ClassReader cr = new ClassReader(is);
              ClassNode node = new ClassNode(Opcodes.ASM9);
              cr.accept(node, parseFlags);
              return node;
            } catch(IOException ex) {
              throw new UncheckedIOException(ex);
            }
          })
          .forEach(n -> result.put(n.name, n));
    } catch (UncheckedIOException e) {
      throw new IOException(e.getCause()); // rethrow the lambdas IOException
    }
    return result;
  }

  /**
   * Parses the given file to a ClassNode
   *
   * @param path
   *     the path to parse
   * @param parseFlags
   *     the flags passed to the class reader
   *
   * @return the parsed node
   *
   * @throws IOException
   *     if the path could not be read
   */
  default ClassNode parseClass(Path path, int parseFlags) throws IOException {
    ClassReader cr = new ClassReader(Files.readAllBytes(path));
    ClassNode result = new ClassNode(Opcodes.ASM9);
    cr.accept(result, parseFlags);
    return result;
  }
}
