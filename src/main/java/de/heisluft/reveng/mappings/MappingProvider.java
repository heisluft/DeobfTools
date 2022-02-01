package de.heisluft.reveng.mappings;

import java.io.IOException;
import java.nio.file.Path;

public interface MappingProvider {
  Mappings parseMappings(Path path) throws IOException;
}
