package de.heisluft.reveng;

import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static de.heisluft.function.FunctionalUtil.thr;

public class Exceptions implements Util {

  private final Map<String, ClassNode> classNodes = new HashMap<>();

  public static void main(String[] args) throws IOException {
    new Exceptions().analyzeExceptions(Paths.get("c0.0.23a_01-deobf.jar"));
  }

  private void analyzeExceptions(Path inJar) throws IOException {
    try(FileSystem fs = createFS(inJar)) {
      Files.walk(fs.getPath("/")).filter(this::hasClassExt).map(thr(this::parseClass)).forEach(node -> classNodes.put(node.name, node));
    }
    classNodes.values().forEach(cn -> cn.methods.forEach(new ExInferringMV(cn.name)::accept));
  }
}
