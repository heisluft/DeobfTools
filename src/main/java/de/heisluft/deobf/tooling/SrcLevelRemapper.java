package de.heisluft.deobf.tooling;

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

import static de.heisluft.function.FunctionalUtil.thrc;

/**
 * A utility to remap source level files such as patches and ATs
 */
public class SrcLevelRemapper implements Util {

  /**
   * The map of all replacements
   */
  private final Map<String, String> replacements = new HashMap<>();

  public static void main(String[] args) throws IOException {
    if(args.length < 3) {
      System.out.println("Usage: SrcLevelRenamer <input> <mappings> <output>");
      System.exit(1);
    }
    SrcLevelRemapper slm = new SrcLevelRemapper(Paths.get(args[1]));
    Path in = Paths.get(args[0]);
    Path out = Paths.get(args[2]);
    if(Files.isDirectory(in)) slm.remapAllFiles(in, out);
    else if (Files.isRegularFile(in)) slm.remapSingleFile(in, out);
    else {
      System.err.println("input must be a regular file or directory!");
      System.exit(1);
    }
  }

  /**
   * Generates all String replacements from frg2src mappings. This method does not validate mappings
   * in any way
   *
   * @param mappingsPath
   *     the mappings path
   *
   * @throws IOException
   *     if the mappings file could not be read
   */
  private SrcLevelRemapper(Path mappingsPath) throws IOException {
    List<String> lines = Files.readAllLines(mappingsPath);
    lines.forEach(line -> {
      String[] words = line.split(" ");
      if("MD:".equals(words[0])) replacements.put(words[2], words[4]);
      if("FD:".equals(words[0])) replacements.put(words[2], words[3]);
    });
  }

  /**
   * Remaps a single file with our parsed mappings line by line, replacing all method and field names.
   *
   * @param inputPath
   *     the path to the fergie-mapped source file
   * @param outputPath
   *     the path to the remapped source file
   *
   * @throws IOException if the source file could not be read or the remapped source file could not be written
   */
  private void remapSingleFile(Path inputPath, Path outputPath) throws IOException {
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

  /**
   * Remaps all files in inDirectory with our parsed mappings, writing them to outDirectory
   *
   * @param inDirectory
   *     the directory in which the frg-mapped source-files reside
   * @param outDirectory
   *     the directory to write the remapped source files to
   *
   * @throws IOException
   *     if any source files could not be read or any remapped source file could not be written
   */
  private void remapAllFiles(Path inDirectory, Path outDirectory) throws IOException {
    Files.walk(inDirectory).filter(Files::isRegularFile).forEach(thrc(p -> {
      Path target = outDirectory.resolve(inDirectory.relativize(p));
      Files.createDirectories(target.getParent());
      remapSingleFile(p, target);
    }));
  }
}
