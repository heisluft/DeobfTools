package de.heisluft.reveng.at;

import de.heisluft.reveng.Util;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static de.heisluft.function.FunctionalUtil.thr;
import static de.heisluft.function.FunctionalUtil.thrc;

/**
 * A tool for applying AccessTransformers
 */
public class ATApplicator implements Util {

  /**
   * The class cache
   */
  private final Map<String, ClassNode> classes = new HashMap<>();
  /**
   * The set of inheritable methods grouped as className -> mdName + mdDesc
   */
  private final Map<String, Set<String>> inheritable = new HashMap<>();
  /**
   * The AccessTransformer to apply
   */
  private final AccessTransformer at;


  /**
   * Transforms the input jar by applying the Applicators AccessTransformer
   *
   * @param input
   *     the path to the input jar
   * @param output
   *     the path to the output jar
   *
   * @throws IOException
   *     if the output jar could not be written or the input file could not be read
   */
  private void transformJar(Path input, Path output) throws IOException {
    try(FileSystem fs = createFS(input)) {
      Files.walk(fs.getPath("/")).filter(this::hasClassExt).map(thr(this::parseClass)).forEach(cn -> classes.put(cn.name, cn));
    }
    classes.values().forEach(cn -> {
      cn.access = at.getClassAccess(cn.name, cn.access);
      cn.fields.forEach(f -> f.access = at.getFieldAccess(cn.name, f.name, f.access));
      cn.methods.forEach(mn -> {
        if(Util.hasNone(mn.access, Opcodes.ACC_PRIVATE, Opcodes.ACC_STATIC))
          inheritable.computeIfAbsent(cn.name, s -> new HashSet<>()).add(mn.name + mn.desc);
      });
    });
    classes.values().forEach(cn -> cn.methods.forEach(mn -> mn.access = findAccess(cn, mn.name, mn.desc, mn.access)));
    Files.write(output, new byte[]{0x50, 0x4B, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
    try(FileSystem fs = createFS(output)) {
      classes.values().forEach(thrc(n -> {
        ClassWriter w = new ClassWriter(0);
        n.accept(w);
        if(n.name.contains("/"))
          Files.createDirectories(fs.getPath(n.name.substring(0, n.name.lastIndexOf('/'))));
        Files.write(fs.getPath(n.name + ".class"), w.toByteArray());
      }));
    }
  }

  /**
   * Finds the access for a given method. if none is found it will try to recursively find it via
   * inheritance.
   *
   * @param cls
   *     the classNode containing the method
   * @param mdName
   *     the methods name
   * @param mdDesc
   *     the methods descriptor
   *
   * @return the resulting access
   */
  private int findAccess(ClassNode cls, String mdName, String mdDesc, int access) {
    if(at.providesMethodAccess(cls.name, mdName, mdDesc))
      return at.getMethodAccess(cls.name, mdName, mdDesc, access);
    return findAccessRec(cls, mdName, mdDesc, access);
  }

  /**
   * Finds the access for a given method recursively
   *
   * @param cls
   *     the classNode to search the method in
   * @param mdName
   *     the methods name
   * @param mdDesc
   *     the methods descriptor
   *
   * @return the new access flag, identical to the old ne if not transformed
   */
  private int findAccessRec(ClassNode cls, String mdName, String mdDesc, int access) {
    if(inheritable.getOrDefault(cls.name, new HashSet<>(0)).contains(mdName + mdDesc) && at.providesMethodAccess(cls.name, mdName, mdDesc))
      return at.getMethodAccess(cls.name, mdName, mdDesc, access);
    int result;
    if(classes.containsKey(cls.superName) && (result = findAccessRec(classes.get(cls.superName), mdName, mdDesc, access)) != access)
      return result;
    for(String iface : cls.interfaces)
      if(classes.containsKey(iface) && (result = findAccessRec(classes.get(iface), mdName, mdDesc, access)) != access)
        return result;
    return access;
  }

  private ATApplicator(Path atPath) throws IOException {
    this.at = new CFGAccessTransformer(atPath);
  }

  public static void main(String[] args) throws IOException {
    if(args.length != 3) {
      System.err.println("Usage: AccessTransformer <input> <config> <output>");
      System.exit(1);
    }
    new ATApplicator(Paths.get(args[1])).transformJar(Paths.get(args[0]), Paths.get(args[2]));
  }
}
