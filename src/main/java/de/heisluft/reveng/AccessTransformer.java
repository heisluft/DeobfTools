package de.heisluft.reveng;

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

import static de.heisluft.function.FunctionalUtil.*;

//TODO: Document
public class AccessTransformer implements Util {

  private final Map<String, String> classMap = new HashMap<>();
  private final Map<String, Map<String, String>> fieldMap = new HashMap<>();
  private final Map<String, Map<String, String>> methodMap = new HashMap<>();
  private final Map<String, ClassNode> classes = new HashMap<>();

  private final Map<String, Set<String>> inheritable = new HashMap<>();

  private static int getModifiedAccess(int access, String modString) {
    if("public".equals(modString)) return (access & 0xfff8) | Opcodes.ACC_PUBLIC;
    if("protected".equals(modString)) return (access & 0xfff8) | Opcodes.ACC_PROTECTED;
    if("public-f".equals(modString)) return (access & 0xffe8) | Opcodes.ACC_PUBLIC;
    if("protected-f".equals(modString)) return (access & 0xffe8) | Opcodes.ACC_PROTECTED;
    return access;
  }

  private static boolean isValidAccess(String modString) {
    return "public".equals(modString) || "protected".equals(modString)
        || "public-f".equals(modString) || "protected-f".equals(modString);
  }

  private void transformJar(Path input, Path output) throws IOException {
    try(FileSystem fs = createFS(input)) {
      Files.walk(fs.getPath("/")).filter(this::hasClassExt).map(thr(this::parseClass)).forEach(cn -> classes.put(cn.name, cn));
    }
    classes.values().forEach(cn -> {
      if(classMap.containsKey(cn.name)) cn.access = getModifiedAccess(cn.access, classMap.get(cn.name));
      cn.fields.stream().filter(f -> fieldMap.getOrDefault(cn.name, new HashMap<>()).containsKey(f.name)).forEach(f -> f.access = getModifiedAccess(f.access, fieldMap.get(cn.name).get(f.name)));
      cn.methods.forEach(mn -> {
        if(Util.hasNone(mn.access, Opcodes.ACC_PRIVATE, Opcodes.ACC_STATIC))
          inheritable.computeIfAbsent(cn.name, s -> new HashSet<>()).add(mn.name + mn.desc);
      });
    });
    classes.values().forEach(cn -> {
      cn.methods.forEach(mn -> mn.access = getModifiedAccess(mn.access, findModifier(cn, mn.name, mn.desc)));
    });
    Files.write(output, new byte[] {0x50,0x4B,0x05,0x06,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00});
    try(FileSystem fs = createFS(output)) {
      classes.values().forEach(thrc(n -> {
        ClassWriter w = new ClassWriter(0);
        n.accept(w);
        if(n.name.contains("/")) Files.createDirectories(fs.getPath(n.name.substring(0, n.name.lastIndexOf('/'))));
        Files.write(fs.getPath(n.name + ".class"), w.toByteArray());
      }));
    }
  }

  private String findModifier(ClassNode cls, String mdName, String mdDesc) {
    if(methodMap.getOrDefault(cls.name, new HashMap<>(0)).containsKey(mdName + mdDesc)) return methodMap.get(cls.name).get(mdName + mdDesc);
    return findModifierRec(cls, mdName, mdDesc);
  }

  private String findModifierRec(ClassNode cls, String mdName, String mdDesc) {
    if(inheritable.getOrDefault(cls.name, new HashSet<>(0)).contains(mdName + mdDesc) && methodMap.getOrDefault(cls.name, new HashMap<>()).containsKey(mdName + mdDesc)) return methodMap.get(cls.name).get(mdName + mdDesc);
    String result;
    if(classes.containsKey(cls.superName) && (result = findModifierRec(classes.get(cls.superName), mdName, mdDesc)) != null) return result;
    for(String iface : cls.interfaces) if(classes.containsKey(iface) && (result = findModifierRec(classes.get(iface), mdName, mdDesc)) != null) return result;
    return null;
  }

  private void readAT(Path p) throws IOException {
    Files.readAllLines(p).stream().filter(l -> !l.startsWith("#")).map(s -> s.split(" ")).forEach(words -> {
      if(words.length < 2) throw new IllegalArgumentException("line too short.");
      String access = words[0];
      if(!isValidAccess(access)) throw new IllegalArgumentException("illegal access modifier.");
      String entity = words[1];
      if(!entity.contains(".")) {
        classMap.put(entity, access);
        return;
      }
      String[] split = entity.split("\\.");
      if(split.length != 2) throw new IllegalArgumentException("line contains illegal identifier.");
      String className = split[0];
      String memberName = split[1];
      if(memberName.contains("("))
        getOrPut(methodMap, className, new HashMap<>()).put(memberName, access);
      else
        getOrPut(fieldMap, className, new HashMap<>()).put(memberName, access);
    });
  }

  public static void main(String[] args) throws IOException {
    if(args.length != 3) {
      System.err.println("Usage: AccessTransformer <input> <config> <output>");
      System.exit(1);
    }
    AccessTransformer reader = new AccessTransformer();
    reader.readAT(Paths.get(args[1]));
    reader.transformJar(Paths.get(args[0]), Paths.get(args[2]));
  }
}
