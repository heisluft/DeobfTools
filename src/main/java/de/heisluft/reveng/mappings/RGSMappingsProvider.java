package de.heisluft.reveng.mappings;

import de.heisluft.function.Tuple2;
import de.heisluft.reveng.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * RGS Files are mappings for RetroGuard.
 * They have way more options than just mappings, providing accessModifiers and such things.
 * 4 Directives are relevant to us: .class_map, .field_map, .method_map and .class.
 * While the first three are self-explanatory
 * the .class allows package relocations and access modifiers.
 * We only parse in the former.
 *
 */
//TODO: Support dynamic package relocation by subclassing Mappings
public class RGSMappingsProvider implements MappingsProvider, Util {

  /**
   * A singleton instance is used for parsing mappings.
   */
  static final RGSMappingsProvider INSTANCE = new RGSMappingsProvider();

  /**
   * A function to join a string tuple, prepending a prefix and inserting a delimiter in between.
   * @param prefix the prefix to prepend
   * @param delim the delimiter to insert
   * @return the joined String
   */
  private Function<Tuple2<String, String>,String> join(String prefix, String delim) {
    return tuple -> prefix + tuple._1 + delim + tuple._2;
  }

  @Override
  public Mappings parseMappings(Path path) throws IOException {
    List<String> lines = Files.readAllLines(path);
    Map<String, String> classNames = new HashMap<>();

    Mappings mappings = new Mappings();
    List<String> globs = new ArrayList<>();
    for(String line : lines) {
      if(line.startsWith("#") || line.isEmpty()) continue;
      String[] words = line.split(" ");
      if(words.length < 2)
        throw new IllegalArgumentException("Directive given with no arguments! (line '" + line + "')");
      switch(words[0]) {
        case ".class":
          if(words.length > 2) break;
          globs.add(words[1]);
          break;
        case ".class_map":
          if(words.length < 3)
            throw new IllegalArgumentException("Error on line '" + line + "'. Expected at least 2 arguments, got" + (words.length - 1));
          classNames.put(words[1], words[2]);
          break;
        case ".field_map":
          if(words.length < 3)
            throw new IllegalArgumentException("Error on line '" + line + "'. Expected at least 2 arguments, got" + (words.length - 1));
          String[] fd = splitAt(words[1], words[1].lastIndexOf('/'));
          getOrPut(mappings.fields, fd[0], new HashMap<>()).put(fd[1], words[2]);
          break;
        case ".method_map":
          if(words.length < 4)
            throw new IllegalArgumentException("Error on line '" + line + "'. Expected at least 3 arguments, got" + (words.length - 1));
          String[] md = splitAt(words[1], words[1].lastIndexOf('/'));
          getOrPut(mappings.methods, md[0], new HashMap<>()).put(new Tuple2<>(md[1], words[2]), words[3]);
          break;
      }
    }
    mappings.classes.putAll(classNames); // We would need a subclass for RGSMappings if we were to support package relocation.
    return mappings;
  }

  public static void main(String[] args) throws IOException{
    RGSMappingsProvider.INSTANCE.parseMappings(Paths.get(args[0]));
  }
}