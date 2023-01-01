package de.heisluft.deobf.tooling.mappings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class FRGMappingsHandler implements MappingsHandler {

  private static final int FRG_MAPPING_TYPE_INDEX = 0;
  private static final int FRG_ENTITY_CLASS_NAME_INDEX = 1;
  private static final int FRG_MAPPED_CLASS_NAME_INDEX = 2;
  private static final int FRG_ENTITY_NAME_INDEX = 2;
  private static final int FRG_MAPPED_FIELD_NAME_INDEX = 3;
  private static final int FRG_METHOD_DESCRIPTOR_INDEX = 3;
  private static final int FRG_MAPPED_METHOD_NAME_INDEX = 4;

  public Mappings parseMappings(Path input) throws IOException {
    Mappings mappings = new Mappings();
    Files.readAllLines(input).stream().map(line -> line.split(" ")).forEach(line -> {
      if("MD:".equals(line[FRG_MAPPING_TYPE_INDEX])) {
        if(line.length < 5) throw new IllegalArgumentException("Not enough arguments supplied. (" + join(line) + "), expected at least 4 got" + (line.length - 1));
        String clsName = line[FRG_ENTITY_CLASS_NAME_INDEX];
        String obfName = line[FRG_ENTITY_NAME_INDEX];
        String obfDesc = line[FRG_METHOD_DESCRIPTOR_INDEX];
        mappings.methods.computeIfAbsent(clsName, s -> new HashMap<>()).put(new MdMeta(obfName, obfDesc), line[FRG_MAPPED_METHOD_NAME_INDEX]);
        for(int i = 5; i < line.length; i++)
          mappings.exceptions.computeIfAbsent(clsName + obfName + obfDesc, s -> new HashSet<>()).add(line[i]);
      } else if("FD:".equals(line[FRG_MAPPING_TYPE_INDEX])) {
        if(line.length != 4) throw new IllegalArgumentException("Illegal amount of Arguments supplied. (" + join(line) + "), expected 3 got" + (line.length - 1));
        mappings.fields.computeIfAbsent(line[FRG_ENTITY_CLASS_NAME_INDEX], s -> new HashMap<>())
            .put(line[FRG_ENTITY_NAME_INDEX], line[FRG_MAPPED_FIELD_NAME_INDEX]);
      } else if("CL:".equals(line[FRG_MAPPING_TYPE_INDEX])) {
        if(line.length != 3) throw new IllegalArgumentException("Illegal amount of Arguments supplied. (" + join(line) + "), expected 2 got" + (line.length - 1));
        mappings.classes.put(line[FRG_ENTITY_CLASS_NAME_INDEX], line[FRG_MAPPED_CLASS_NAME_INDEX]);
      } else {
        System.out.print("Not operating on line '" + join(line) + "'!");
      }
    });
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


  /**
   * Joins an array of strings together with spaces
   *
   * @param toJoin
   *     the String array to join
   *
   * @return the joined string
   */
  private String join(String[] toJoin) {
    StringBuilder builder = new StringBuilder(toJoin[0]);
    for(int i = 1; i < toJoin.length; i++) builder.append(" ").append(toJoin[i]);
    return builder.toString();
  }
}
