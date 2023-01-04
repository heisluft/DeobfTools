package de.heisluft.deobf.mappings;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A MappingsHandler is a class capable of parsing mapping files.
 * Each implementing class is only capable of parsing a single file format.
 * A MappingsHandler instance is acquired by a call to {@link MappingsHandlers#findHandler(String)}.
 * MappingsHandlers are loaded via ServiceLoader on the first call to this method.
 */
public interface MappingsHandler {
  /**
   * Parses the MappingsFile at {@code input} and returns the resulting Mappings.
   *
   * @param input the input where the mappings are located
   * @return the parsed mappings
   * @throws IOException if the input path could not be read
   */
  Mappings parseMappings(Path input) throws IOException;

  /**
   * Writes Mappings in this handlers format to the given output path.
   *
   * @param mappings
   *     the mappings to serialize
   * @param output
   *     the path to write to
   *
   * @throws IOException
   *     If the path could not be written to
   */
  default void writeMappings(Mappings mappings, Path output) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the file extension handled by this instance.
   *
   * @return the file extension, never {@code null}
   */
  String fileExt();

  /**
   * @return whether this handler can (de-)serialize exception data
   */
  default boolean supportsExceptionData() {
    return false;
  }

  /**
   * @return whether this handler can (de-)serialize parameter mappings
   */
  default boolean supportsParameterData() {
    return false;
  }

  /**
   * @return whether this handler can (de-)serialize class, method and field mappings
   */
  default boolean supportsRemappingData() {
    return true;
  }
}
