package de.heisluft.reveng;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static de.heisluft.function.FunctionalUtil.thrbc;
import static de.heisluft.function.FunctionalUtil.thrc;

/**
 * The constructor fixer is a tool to move empty super() calls to the first position, easing recompilation
 * <p>
 * Generally it should be preferred to just restore the classes status as an inner class, but this
 * is not yet possible
 */
public class ConstructorFixer implements Util {
  public static void main(String[] args) throws IOException {
    if(args.length != 2) {
      System.out.println("usage: ConstructorFixer <input> <output>");
      System.exit(1);
    }
    new ConstructorFixer().test(Paths.get(args[0]), Paths.get(args[1]));
  }

  /**
   * Transforms a single class node
   *
   * @param bytes
   *     the unmodified classes bytes
   *
   * @return the resulting classes bytes, may be modified
   */
  private byte[] transformClassNode(byte[] bytes) {
    ClassReader reader = new ClassReader(bytes);
    ClassNode cn = new ClassNode();
    reader.accept(cn, ClassReader.EXPAND_FRAMES);

    for(MethodNode m : cn.methods) {
      if(!m.name.equals("<init>")) continue;
      int i;
      for(i = 0; i < m.instructions.size(); i++) {
        AbstractInsnNode ain = m.instructions.get(i);
        if(ain instanceof VarInsnNode && ((VarInsnNode) ain).var == 0) {
          if(ain.getNext().getOpcode() == Opcodes.INVOKESPECIAL) {
            MethodInsnNode min = ((MethodInsnNode) ain.getNext());
            if(min.owner.equals(cn.superName) && min.desc.equals("()V")) break;
          }
        }
      }
      if(i == 0 || i == m.instructions.size()) return bytes;
      System.out.println("Class: " + cn.name + " (extending " + cn.superName + ") Offset: " + i);
      // super call is not first
      AbstractInsnNode aload0 = m.instructions.get(i);
      m.instructions.remove(aload0);
      AbstractInsnNode ivsp = m.instructions.get(i);
      m.instructions.remove(ivsp);
      m.instructions.insert(ivsp);
      m.instructions.insert(aload0);
      System.out.println("Fixing the constructor of " + cn.name);
      ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
      cn.accept(cw);
      return cw.toByteArray();
    }
    return bytes;
  }

  /**
   * Transforms the classes from an input jar, writing the resulting classes to the output jar path
   *
   * @param inJar
   *     the path of the input jar
   * @param outJar
   *     the path of the output jar
   *
   * @throws IOException
   *     if the input jar could not be read or the output jar could not be written to
   */
  private void test(Path inJar, Path outJar) throws IOException {
    Map<Path, byte[]> entries = new HashMap<>();
    try(FileSystem fs = createFS(inJar)) {
      Files.walk(fs.getPath("/")).filter(Files::isRegularFile)
          .forEach(thrc(p -> entries.put(p, Files.readAllBytes(p))));
    }
    entries.keySet().stream().filter(this::hasClassExt)
        .forEach(p -> entries.put(p, transformClassNode(entries.get(p))));
    Files.write(outJar,
        new byte[]{80, 75, 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    try(FileSystem fs = createFS(outJar)) {
      entries.forEach(thrbc((path, bytes) -> {
        Path p = fs.getPath(path.toString());
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
      }));
    }
  }
}
