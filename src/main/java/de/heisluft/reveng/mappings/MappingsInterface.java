package de.heisluft.reveng.mappings;

import java.io.IOException;
import java.nio.file.Path;

/**
 * This Class is the main interface for generating, reading and writing Mappings without having to
 * know implementation details for each available Mappings file format
 */
public class MappingsInterface {
  /**
   * Finds a {@link MappingProvider} for a given file. Currently available MappingsProviders are:
   * <ol>
   *   <li>Fergie, handling .frg files</li>
   *   <li>RGSMappingsProvider, handling .rgs files</li>
   * </ol>
   *
   * @param filename
   *     the name of the file to find a provider for
   *
   * @return an instance of a MappingsProvider or {@code null} if no such handler exists for the
   *     given file extension
   */
  public static MappingProvider findProvider(String filename) {
    switch(filename.substring(filename.lastIndexOf('.') + 1)) {
      case "frg":
        return Fergie.INSTANCE;
      case "rgs":
        return RGSMappingsProvider.INSTANCE;
      default:
        return null;
    }
  }

  /**
   * Generates default mappings for a given jar. These mappings are guaranteed to generate unique
   * method names.
   *
   * @param input
   *     the jar to generate for
   *
   * @return the generated mappings
   *
   * @throws IOException
   *     if the jar file could not be read correctly
   */
  public static Mappings generateMappings(Path input) throws IOException {
    return new Fergie().generateMappings(input);
  }

  /**
   * Emits Mappings in frg file format to the given output path
   *
   * @param mappings
   *     the mappings to serialize
   * @param output
   *     the path to write to
   *
   * @throws IOException
   *     if the mappings could not be written
   */
  public static void writeFergieMappings(Mappings mappings, Path output) throws IOException {
    new Fergie().writeMappings(mappings, output);
  }
}