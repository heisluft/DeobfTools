package de.heisluft.deobf.mappings.handlers;

import de.heisluft.deobf.mappings.Mappings;
import de.heisluft.deobf.mappings.MappingsBuilder;
import de.heisluft.deobf.mappings.MappingsHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A MappingsHandler for reading EXC files. These contain Parameter and exception data only.
 */
public final class EXCMappingsHandler implements MappingsHandler {
  @Override
  public Mappings parseMappings(Path input) throws IOException {
    MappingsBuilder b = new MappingsBuilder();
    List<String> lines = Files.readAllLines(input);
    for (String line : lines) {
      int dot = line.indexOf('.'),
          openBrace = line.indexOf('('),
          eqSign = line.indexOf('='),
          vertBar = line.indexOf('|');
      String cName = line.substring(0, dot);
      String mName = line.substring(dot + 1, openBrace);
      String mDesc = line.substring(openBrace, eqSign);
      b.addExceptions(cName, mName, mDesc, Arrays.asList(line.substring(eqSign + 1, vertBar).split(",")));
      b.setParameters(cName, mName, mDesc, Arrays.asList(line.substring(vertBar+1).split(",")));
    }
    return b.build();
  }

  @Override
  public Collection<String> fileExts() {
    return Collections.singleton( "exc");
  }

  @Override
  public boolean supportsExceptionData() {
    return true;
  }

  @Override
  public boolean supportsParameterData() {
    return true;
  }

  @Override
  public boolean supportsRemappingData() {
    return false;
  }
}
