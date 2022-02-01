package de.heisluft.reveng.mappings;

import java.io.IOException;
import java.nio.file.Path;

public class MappingUtil {
  public static MappingProvider findMappingProvider(String filename) {
    switch(filename.substring(filename.lastIndexOf('.') + 1)) {
      case "frg": return new Fergie();
      case "rgs": return RGSMappingsProvider.INSTANCE;
      default:return null;
    }
  }

  public static Mappings generateMappings(Path input) throws IOException {
    return Fergie.INSTANCE.generateMappings(input);
  }

  public static void writeFergieMappings(Mappings mappings, Path output) throws IOException {
    Fergie.INSTANCE.writeMappings(mappings, output);
  }
}