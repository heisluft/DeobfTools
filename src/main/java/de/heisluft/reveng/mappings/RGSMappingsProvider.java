package de.heisluft.reveng.mappings;

import de.heisluft.function.Tuple2;
import de.heisluft.reveng.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class RGSMappingsProvider implements MappingsProvider, Util {

  public static final RGSMappingsProvider INSTANCE = new RGSMappingsProvider();

  private void convert() throws Exception {
    List<String> lines = Files.readAllLines(Paths.get("test-rgs/minecraft.rgs"));
    List<String> out = new ArrayList<>();
    lines.stream().filter(line -> line.startsWith(".class_map")).map(s -> s.replace(".class_map", "CL:"))
        .sorted(String::compareTo).forEach(out::add);
    lines.stream().filter(line -> line.startsWith(".field_map")).map(s -> s.substring(11))
        .map(s -> s.split(" ")).map(Tuple2::from)
        .map(t -> t.map1(s -> splitAt(s, s.lastIndexOf('/'))).map1(Tuple2::from))
        .filter(t -> !t._2.equals(t._1._2)).map(t -> t.map1(join("FD: ", " ")))
        .map(join("", " ")).sorted(String::compareTo).forEach(out::add);
    lines.stream().filter(line -> line.startsWith(".method_map")).map(s -> s.substring(12))
        .map(s -> s.split(" ")).map(args -> {
          String[] split = splitAt(args[0], args[0].lastIndexOf('/'));
          if(split[1].equals(args[2])) return "";
          return "MD: " + split[0] + " " + split[1] + " " + args[1] + " " + args[2];
        }).filter(s -> !s.isEmpty()).sorted(String::compareTo).forEach(out::add);
    Files.write(Paths.get("a1.1.2_01.frg"), out);
  }

  private Function<Tuple2<String, String>,String> join(String prefix, String delim) {
    return tuple -> prefix + tuple._1 + delim + tuple._2;
  }

  @Override
  public Mappings parseMappings(Path path) throws IOException {
    List<String> lines = Files.readAllLines(path);
    Mappings mappings = new Mappings();
    //TODO: Find a way to conveniently generate
    return mappings;
  }
}