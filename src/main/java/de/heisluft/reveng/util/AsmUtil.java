package de.heisluft.reveng.util;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public interface AsmUtil {
  /**
   * Parses the given file to a ClassNode
   * @param path the path to parse
   * @return the parsed node
   * @throws IOException if the path could not be read
   */
    default ClassNode parseClass(Path path) throws IOException {
    ClassReader cr = new ClassReader(Files.readAllBytes(path));
    ClassNode result = new ClassNode(Opcodes.ASM7);
    cr.accept(result, 0);
    return result;
  }
}
