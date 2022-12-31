package de.heisluft.deobf.tooling.binfix;

import de.heisluft.deobf.tooling.Util;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static de.heisluft.function.FunctionalUtil.thrc;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

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
  private byte[] transformClassNode(byte[] bytes, Map<String, ClassNode> classCache) {
    ClassNode cn = parseBytes(bytes);
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
      // Instance Inner Classes should not have their constructor data shuffled
      // The code is left in place for classes whose data was not restored
      if(Util.hasNone(cn.access, ACC_STATIC) && cn.innerClasses.stream().anyMatch(icn -> icn.name.equals(cn.name))) return bytes;
      System.out.println("Fixing Class: " + cn.name + " (extending " + cn.superName + ") super call offset: " + i);
      // super call is not first
      AbstractInsnNode aload0 = m.instructions.get(i);
      m.instructions.remove(aload0);
      AbstractInsnNode ivsp = m.instructions.get(i);
      m.instructions.remove(ivsp);
      m.instructions.insert(ivsp);
      m.instructions.insert(aload0);
      ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
      cn.accept(cw);
      return cw.toByteArray();
    }

    ClassNode superNode = classCache.get(cn.superName);
    if(superNode == null) return bytes;
    System.out.println("class " + cn.name + " has no constructor... checking if one is needed");
    Set<MethodNode> superCtors = new HashSet<>();
    superNode.methods.stream().filter(mn -> "<init>".equals(mn.name)).forEach(superCtors::add);
    if(superCtors.isEmpty()) {
      System.out.println("No constructor needed, super has none");
      return bytes;
    }
    if(superCtors.stream().map(mn -> mn.desc).anyMatch("()V"::equals)) {
      System.out.println("Super has default constructor, we dont need to create one");
      return bytes;
    }
    if(superCtors.size() > 1) {
      System.out.println("super has multiple non-default constructor, manual patching will be needed");
      return bytes;
    }
    MethodNode singleCtor = superCtors.iterator().next();
    System.out.println("Adding constructor matching super, desc: " + singleCtor.desc);
    cn.methods.add(0, createConstructor(singleCtor.desc, cn.superName, singleCtor.access));
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    cn.accept(writer);
    return writer.toByteArray();
  }

  private MethodNode createConstructor(String desc, String superName, int superAcc) {
    MethodNode node = new MethodNode(superAcc, "<init>", desc, null, null);
    node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    for(int i = 0; i < Type.getArgumentTypes(desc).length; i++) {
      node.instructions.add(new VarInsnNode(Opcodes.ALOAD, i + 1));
    }
    node.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", desc));
    node.instructions.add(new InsnNode(Opcodes.RETURN));
    return node;
  }

  private boolean hasClassFileExt(String path) {
    return path.endsWith(".class");
  }

  private ClassNode parseBytes(byte[] bytes) {
    ClassReader reader = new ClassReader(bytes);
    ClassNode cn = new ClassNode();
    reader.accept(cn, ClassReader.EXPAND_FRAMES);
    return cn;
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
    Map<String, byte[]> entries = new HashMap<>();
    Map<String, ClassNode> nodes = new HashMap<>();
    try(FileSystem fs = createFS(inJar)) {
      Files.walk(fs.getPath("/")).filter(Files::isRegularFile)
          .forEach(thrc(p -> entries.put(p.toString(), Files.readAllBytes(p))));
    }
    entries.keySet().stream().filter(this::hasClassFileExt).map(entries::get).map(this::parseBytes).forEach(cn -> nodes.put(cn.name, cn));
    entries.keySet().stream().filter(this::hasClassFileExt).forEach(r -> entries.put(r, transformClassNode(entries.get(r), nodes)));
    Files.write(outJar,
        new byte[]{80, 75, 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    try(FileSystem fs = createFS(outJar)) {
      entries.keySet().stream().filter(p -> !p.startsWith("META-INF/")).forEach(thrc(path -> {
        Path p = fs.getPath(path);
        Files.createDirectories(p.getParent());
        Files.write(p, entries.get(path));
      }));
    }
  }
}
