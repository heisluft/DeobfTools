package de.heisluft.deobf.mappings.handlers;

import de.heisluft.deobf.mappings.Mappings;
import de.heisluft.deobf.mappings.MappingsHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * RGS Files are mappings for RetroGuard.
 * They have way more options than just mappings, providing accessModifiers and such things.
 * 4 Directives are relevant to us: .class_map, .field_map, .method_map and .class.
 * While the first three are self-explanatory
 * the .class allows package relocations and access modifiers.
 * We only parse in the former.
 *
 */
public final class RGSMappingsHandler implements MappingsHandler {

  @Override
  public Collection<String> fileExts() {
    return Collections.singleton("rgs");
  }

  @Override
  public Mappings parseMappings(Path path) throws IOException {
    RGSMappings mappings = new RGSMappings();
    List<String> lines = Files.readAllLines(path);
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
          if(words.length < 3) throw new IllegalArgumentException(argMismatch(line, 2, words.length - 1));
          mappings.addClassMapping(words[1], words[2]);
          break;
        case ".field_map":
          if(words.length < 3) throw new IllegalArgumentException(argMismatch(line, 2, words.length - 1));
          String[] fd = splitAt(words[1], words[1].lastIndexOf('/'));
          mappings.addFieldMapping(fd[0], fd[1], words[2]);
          break;
        case ".method_map":
          if(words.length < 4) throw new IllegalArgumentException(argMismatch(line, 3, words.length - 1));
          String[] md = splitAt(words[1], words[1].lastIndexOf('/'));
          mappings.addMethodMapping(md[0], md[1], words[2], words[3]);
          break;
      }
    }
    for(int i = 0; i < globs.size(); i++) {
      String regex = "^" + globs.get(i).substring(0, globs.get(i).lastIndexOf('*')) + "[^\\/]+$";
      String newPackage = globs.get(++i).replace("**", "");
      mappings.packages.put(regex, newPackage);
    }
    return mappings;
  }

  private String argMismatch(String line, int expected, int actual) {
    return "Error on line '" + line + "'. Expected at least " + expected + " arguments, got " + actual;
  }

  /**
   * Splits a String at the given index.
   *
   * @param toSplit
   *     the String to be split
   * @param index
   *     the index on which to split on
   *
   * @return the pair of split halves
   */
  private static String[] splitAt(String toSplit, int index) {
    return new String[]{toSplit.substring(0, index), toSplit.substring(index + 1)};
  }
}
