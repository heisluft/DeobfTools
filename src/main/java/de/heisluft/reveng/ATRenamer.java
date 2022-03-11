package de.heisluft.reveng;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ATRenamer implements Util {
  public static void main(String[] args) throws IOException {
    if(args.length < 3) {
      System.err.println("Usage: ATRenamer <input> <mappings> <output>");
      System.exit(1);
    }
    new ATRenamer().renameAts(Paths.get(args[0]),Paths.get(args[1]),Paths.get(args[2]));
  }

  /**
   * Generates all String replacements from frg2src mappings. This method does not validate mappings
   * in any way
   *
   * @param mappingsPath
   *     the mappings path.
   *
   * @return a map of all patterns mapped to their replacement strings
   *
   * @throws IOException
   *     if the mappings file could not be read
   */
  private Map<String, String> genReplacements(Path mappingsPath) throws IOException {
    Map<String, String> replacements = new HashMap<>();
    List<String> lines = Files.readAllLines(mappingsPath);
    lines.forEach(line -> {
      String[] words = line.split(" ");
      if("MD:".equals(words[0])) replacements.put(words[2], words[4]);
      if("FD:".equals(words[0])) replacements.put(words[2], words[3]);
    });
    return replacements;
  }

  private void renameAts(Path inputPath, Path mappingsPath, Path outputPath) throws IOException {
    Map<String, String> replacements = genReplacements(mappingsPath);
    try(BufferedReader reader = new BufferedReader(
        new InputStreamReader(Files.newInputStream(inputPath)))) {
      try(BufferedWriter writer = new BufferedWriter(
          new OutputStreamWriter(Files.newOutputStream(outputPath)))) {
        String ln;
        while((ln = reader.readLine()) != null) {
          for(Map.Entry<String, String> replacement : replacements.entrySet())
            ln = ln.replace(replacement.getKey(), replacement.getValue());
          writer.write(ln + "\n");
        }
      }
    }
  }
}
