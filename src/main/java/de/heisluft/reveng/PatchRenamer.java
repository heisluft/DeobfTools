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

import static de.heisluft.function.FunctionalUtil.thrc;

/**
 * The PatchRenamer remaps source level patches in order to have them applied to src-mapped code
 * without the need for the parsing of Java code
 */
public class PatchRenamer implements Util {
  public static void main(String[] args) throws IOException {
    if(args.length != 3) {
      System.out.println("Usage: PatchRenamer <frgPatchesDir> <frg2src file> <srcPatches dir>");
    }
    new PatchRenamer().renamePatches(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]));
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

  /**
   * Renames the patches at patchDirectory with the mappings at mappingsPath, writing them to
   * outPatchDir
   *
   * @param patchDirectory
   *     the directory in which the frg-mapped patches reside
   * @param mappingsPath
   *     the path to the frg2src mappings file
   * @param outPatchDir
   *     the directory to write the src-mapped patches to
   *
   * @throws IOException
   *     if either patches or mappings could not be read or src-patches could not be written
   */
  private void renamePatches(Path patchDirectory, Path mappingsPath, Path outPatchDir)
  throws IOException {
    Map<String, String> replacements = genReplacements(mappingsPath);
    Files.walk(patchDirectory, 1).filter(Files::isRegularFile).forEach(thrc(p -> {
      Path dest = outPatchDir.resolve(patchDirectory.relativize(p));
      try(BufferedReader reader = new BufferedReader(
          new InputStreamReader(Files.newInputStream(p)))) {
        try(BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(Files.newOutputStream(dest)))) {
          String ln;
          while((ln = reader.readLine()) != null) {
            for(Map.Entry<String, String> replacement : replacements.entrySet()) {
              ln = ln.replace(replacement.getKey(), replacement.getValue());
            }
            System.out.println(ln);
            writer.write(ln + "\n");
          }
        }
      }
    }));
  }
}
