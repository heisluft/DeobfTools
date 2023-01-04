package de.heisluft.deobf.mappings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class FRGMappingsHandler implements MappingsHandler {

  private static final int FRG_MAPPING_TYPE_INDEX = 0;
  private static final int FRG_ENTITY_CLASS_NAME_INDEX = 1;
  private static final int FRG_MAPPED_CLASS_NAME_INDEX = 2;
  private static final int FRG_ENTITY_NAME_INDEX = 2;
  private static final int FRG_MAPPED_FIELD_NAME_INDEX = 3;
  private static final int FRG_METHOD_DESCRIPTOR_INDEX = 3;
  private static final int FRG_MAPPED_METHOD_NAME_INDEX = 4;

  public Mappings parseMappings(Path input) throws IOException {
    Mappings mappings = new Mappings();
    List<String> lines = Files.readAllLines(input);
    for (String line : lines) {
      String[] split = line.split(" ");
      if ("MD:".equals(split[FRG_MAPPING_TYPE_INDEX])) {
        if (split.length < 5)  throw new IllegalArgumentException("Not enough arguments supplied. (" + line + "), expected at least 4 got" + (split.length - 1));
        String clsName = split[FRG_ENTITY_CLASS_NAME_INDEX];
        String obfName = split[FRG_ENTITY_NAME_INDEX];
        String obfDesc = split[FRG_METHOD_DESCRIPTOR_INDEX];
        mappings.methods.computeIfAbsent(clsName, s -> new HashMap<>()).put(new MdMeta(obfName, obfDesc), split[FRG_MAPPED_METHOD_NAME_INDEX]);
        for (int i = 5; i < split.length; i++)
          mappings.extraData.computeIfAbsent(clsName + obfName + obfDesc, s -> new MdExtra()).exceptions.add(split[i]);
      } else if ("FD:".equals(split[FRG_MAPPING_TYPE_INDEX])) {
        if (split.length != 4)
          throw new IllegalArgumentException("Illegal amount of Arguments supplied. (" + line + "), expected 3 got" + (split.length - 1));
        mappings.fields.computeIfAbsent(split[FRG_ENTITY_CLASS_NAME_INDEX], s -> new HashMap<>())
            .put(split[FRG_ENTITY_NAME_INDEX], split[FRG_MAPPED_FIELD_NAME_INDEX]);
      } else if ("CL:".equals(split[FRG_MAPPING_TYPE_INDEX])) {
        if (split.length != 3)
          throw new IllegalArgumentException("Illegal amount of Arguments supplied. (" + line + "), expected 2 got" + (split.length - 1));
        mappings.classes.put(split[FRG_ENTITY_CLASS_NAME_INDEX], split[FRG_MAPPED_CLASS_NAME_INDEX]);
      } else {
        System.out.print("Not operating on line '" + line + "'!");
      }
    }
    return mappings;
  }

  @Override
  public String fileExt() {
    return "frg";
  }

  @Override
  public void writeMappings(Mappings mappings, Path to) throws IOException {
    List<String> lines = new ArrayList<>();
    mappings.classes.forEach((k, v) -> lines.add("CL: " + k + " " + v));
    mappings.fields.forEach((clsName, map) -> map.forEach((obfFd, deobfFd) -> lines.add("FD: " + clsName + " " + obfFd + " " + deobfFd)));
    mappings.methods.forEach((clsName, map) -> map.forEach((obfMet, deobfName) -> {
      StringBuilder line = new StringBuilder("MD: " + clsName + " " + obfMet.name + " " + obfMet.desc + " " + deobfName);
      mappings.getExceptions(clsName, obfMet.name, obfMet.desc).stream().sorted().forEach(s -> line.append(" ").append(s));
      lines.add(line.toString());
    }));
    lines.sort(Comparator.naturalOrder());
    Files.write(to, lines);
  }
}
