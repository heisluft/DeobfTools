package de.heisluft.reveng.mappings;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A MappingProvider is a class capable of parsing mapping files.
 * Each implementing class is only capable of parsing a single file format.
 * A mapping interface is acquired by a call to
 * {@link MappingsInterface#findProvider(String) findProvider}
 * within {@link MappingsInterface}
 */
public interface MappingProvider {
  /**
   * Parses the MappingsFile at {@code input} and returns the resulting Mappings.
   *
   * @param input the input where the mappings are located
   * @return the parsed mappings
   * @throws IOException if the input path could not be read
   */
  Mappings parseMappings(Path input) throws IOException;
}
