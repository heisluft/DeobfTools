package de.heisluft.deobf.tooling.binfix;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.heisluft.deobf.tooling.Util;
import de.heisluft.deobf.tooling.mappings.Mappings;

import de.heisluft.deobf.tooling.mappings.MappingsBuilder;
import de.heisluft.deobf.tooling.mappings.MappingsHandler;
import de.heisluft.deobf.tooling.mappings.MappingsHandlers;
import org.objectweb.asm.Label;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.ClassWriter;

public class BinFixer {

  public static void main(String[] args) throws IOException {
    System.out.println("BinFixer: A Merger of Project CHOIR (Class Heuristics for Outer-Inner Relationships) and ConstructorFixer\n");

    if (args.length < 2 || args.length > 3) {
      System.out.println("Usage BinFixer <input> <output> [mappingsOutput]");
      return;
    }
    Path mappingsOutPath = null;
    if(args.length == 3) {
      mappingsOutPath = Paths.get(args[2]);
      Files.createDirectories(mappingsOutPath.getParent());
    }
    Path input = Paths.get(args[0]);
    Path output = Paths.get(args[1]);

    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
    Util u = new Util() {};
    Map<String, ClassNode> classes = u.parseClasses(input);
    Set<String> dirtyClasses = new HashSet<>();

    InnerClassDetector icd = new InnerClassDetector();
    icd.setBuilder(new MappingsBuilder());
    icd.detect(classes, dirtyClasses);
    EnumSwitchClassDetector escd = new EnumSwitchClassDetector();
    escd.setBuilder(icd.getBuilder());
    escd.restoreMeta(classes, dirtyClasses);
    if(mappingsOutPath != null) MappingsHandlers.findFileHandler(mappingsOutPath.getFileName().toString()).writeMappings(escd.getBuilder().build(), mappingsOutPath);
    new ConstructorFixer().fixConstructors(classes, dirtyClasses);

    if (dirtyClasses.isEmpty()) return;

    try (FileSystem fs = u.createFS(output)) {
      for (String dirtyClass : dirtyClasses) {
        ClassWriter writer = new ClassWriter(0);
        classes.get(dirtyClass).accept(writer);
        Files.write(fs.getPath("/" + dirtyClass + ".class"), writer.toByteArray());
      }
    }
  }
}
