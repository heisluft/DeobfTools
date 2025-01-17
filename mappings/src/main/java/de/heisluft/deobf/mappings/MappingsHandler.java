package de.heisluft.deobf.mappings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;

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
   * Returns the file extension handled by this instance. Each entry will be used to parameterize
   * the instance with {@link #withFileExt(String)}
   *
   * @return the file extension, never {@code null}
   *
   * @deprecated use {@link #fileExts()} instead. This method will be removed in a future release,
   * only override for backwards compatibility (using your own handler with an older lib version)
   */
  @Deprecated
  default String fileExt() {
    throw new UnsupportedOperationException();
  }

  /**
   * Parameterizes this Service with one of the file extensions returned by {@link #fileExts()}.
   * Handlers providing multiple extensions might want to return new instances here
   *
   * @param fileExt the file extension to use
   * @return a maybe new MappingsHandler instance, never {@code null}.
   */
  default MappingsHandler withFileExt(String fileExt) {
    return this;
  }

  /**
   * Returns a collection of handled file extensions. NOTE: Implementations are strongly encouraged
   * to override this method to ensure forward compatability, this method WILL become abstract in
   * a future release.
   *
   * @return a collection of file extensions handled by this instance, never {@code null}
   */
  default Collection<String> fileExts() {
    return Collections.singleton(fileExt());
  }

  /**
   * Checks whether this handler can (de-)serialize exception data.
   *
   * @return whether this handler can (de-)serialize exception data
   */
  default boolean supportsExceptionData() {
    return false;
  }

  /**
   * Checks whether this handler can (de-)serialize parameter mappings.
   *
   * @return whether this handler can (de-)serialize parameter mappings
   */
  default boolean supportsParameterData() {
    return false;
  }

  /**
   * Checks whether this handler can distinguish fields by their descriptors.
   *
   * @return whether this handler can distinguish fields by their descriptors
   */
  default boolean supportsFieldDescriptors() {
    return false;
  }

  /**
   * Checks whether this handler can (de-)serialize class, method and field mappings.
   *
   * @return whether this handler can (de-)serialize class, method and field mappings
   */
  default boolean supportsRemappingData() {
    return true;
  }
}
