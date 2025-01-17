package de.heisluft.deobf.mappings.handlers;

import de.heisluft.deobf.mappings.Mappings;
import de.heisluft.deobf.mappings.MappingsBuilder;
import de.heisluft.deobf.mappings.MappingsHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A MappingsHandler capable of reading SRG files.
 */
//TODO: Implement Packages
public final class SRGMappingsHandler implements MappingsHandler {

  private static final int OBFED_INDEX = 1, DEOBFED_INDEX = 2, MD_DESCRIPTOR_INDEX = 2, MD_DEOBFED_INDEX = 3;

  @Override
  public Mappings parseMappings(Path input) throws IOException {
    MappingsBuilder builder = new MappingsBuilder();
    List<String> lines = Files.readAllLines(input);
    int lCounter = 0;
    for (String line : lines) {
      if(!line.contains(" ")) throw parseError(lCounter, "Line does not contain command");
      String[] split = line.split(" ");
      String cmd = split[0];
      switch (cmd) {
        case "CL:":
          if (split.length != 3)
            throw parseError(lCounter, "Class mappings need 2 arguments, " + (split.length - 1) + " given");
          builder.addClassMapping(split[OBFED_INDEX], split[DEOBFED_INDEX]);
          break;
        case "MD:":
          if (split.length != 5)
            throw parseError(lCounter, "Method mappings need 3 arguments, " + (split.length - 1) + " given");
          String obfName = split[OBFED_INDEX];
          String deobfName = split[MD_DEOBFED_INDEX];
          if(!obfName.contains("/") || !deobfName.contains("/"))
            throw parseError(lCounter, "Class member names must contain slash");
          int lastSlash = obfName.lastIndexOf('/');
          builder.addMethodMapping(
              obfName.substring(0, lastSlash),
              obfName.substring(lastSlash + 1),
              split[MD_DESCRIPTOR_INDEX],
              deobfName.substring(deobfName.lastIndexOf('/') + 1)
          );
          break;
        case "FD:":
          if (split.length != 3)
            throw parseError(lCounter, "Field mappings need 3 arguments, " + (split.length - 1) + " given");
          obfName = split[OBFED_INDEX];
          deobfName = split[DEOBFED_INDEX];
          if(!obfName.contains("/") || !deobfName.contains("/"))
            throw parseError(lCounter, "Class member names must contain slash");
          lastSlash = obfName.lastIndexOf('/');
          builder.addFieldMapping(
              obfName.substring(0, lastSlash),
              obfName.substring(lastSlash + 1),
              deobfName.substring(deobfName.lastIndexOf('/') + 1)
          );
          break;
        case "PK:":
          break;
        default: throw parseError(lCounter, "Unknown entry '" + cmd + "'");
      }
      lCounter++;
    }
    return builder.build();
  }

  private IOException parseError(int lCounter, String message) {
    return new IOException("Error reading line " + lCounter + ": " + message);
  }

  @Override
  public void writeMappings(Mappings mappings, Path output) throws IOException {
    MappingsHandler.super.writeMappings(mappings, output);
  }

  @Override
  public Collection<String> fileExts() {
    return Collections.singleton( "srg");
  }
}
