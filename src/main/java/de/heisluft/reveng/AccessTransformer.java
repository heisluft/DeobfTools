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

import static de.heisluft.function.FunctionalUtil.thr;
import static de.heisluft.function.FunctionalUtil.thrc;

/**
 * AccessTransformers are tools to modify the access of classes and their members,
 * used for example to widen access or to remove final modifiers from methods and fields, so that
 * they can be modified from subclasses and/or externally.
 * AccessTransformers typically have a file extension of .cfg, the file format is as follows:
 * <ul>
 *  <li>Lines starting on '#' will be ignored.</li>
 *  <li>Classes are serialized with internal names (with '/' instead of '.')</li>
 *  <li>Each Line starts with a command followed by the entities notation, separated by exactly one space (' ').</li>
 *  <li>Words following the entities notation are ignored to allow for inline comments</li>
 *  <li>The notation for classes just consists of their internal name</li>
 *  <li>For fields, it consists of the className followed by a dot (.) and the fields name</li>
 *  <li>For methods, it consists of the className followed by a dot (.), and the methods name immediately
 *      followed by its descriptor </li>
 * </ul>
 * @see #isValidCommand(String) for a list of commands and their actions.
 */
public class AccessTransformer implements Util {

  /**
   * A map of all commands for classes, grouped as className -> command
   */
  private final Map<String, String> classCommands = new HashMap<>();
  /**
   * A map of all commands for fields, grouped as className -> fdName -> command
   */
  private final Map<String, Map<String, String>> fieldCommands = new HashMap<>();
  /**
   * A map of all commands for methods, grouped as className -> mdName + mdDesc -> command
   */
  private final Map<String, Map<String, String>> methodCommands = new HashMap<>();
  /**
   * The class cache
   */
  private final Map<String, ClassNode> classes = new HashMap<>();
  /**
   * The set of inheritable methods grouped as className -> mdName + mdDesc
   */
  private final Map<String, Set<String>> inheritable = new HashMap<>();

  /**
   * Gives back an access modifier based on access and an AT command
   *
   * @param access
   *     the initial access modifier
   * @param command
   *     the AT command
   *
   * @return the modified access
   */
  private static int getModifiedAccess(int access, String command) {
    if("public".equals(command)) return (access & 0xfff8) | Opcodes.ACC_PUBLIC;
    if("protected".equals(command)) return (access & 0xfff8) | Opcodes.ACC_PROTECTED;
    if("public-f".equals(command)) return (access & 0xffe8) | Opcodes.ACC_PUBLIC;
    if("protected-f".equals(command)) return (access & 0xffe8) | Opcodes.ACC_PROTECTED;
    return access;
  }

  /**
   * Returns if an AT command is valid. Valid commands (and their effects):
   * <ul>
   *   <li>public: sets the access to public</li>
   *   <li>public-f: sets the access to public, removing the final modifier if present</li>
   *   <li>protected: sets the access to protected</li>
   *   <li>protected-f: sets the access to protected, removing the final modifier if present</li>
   * </ul>
   *
   * @param command
   *     the AT command to validate
   *
   * @return true if the given command is valid, false otherwise
   */
  private static boolean isValidCommand(String command) {
    return "public".equals(command) || "protected".equals(command) || "public-f".equals(command) ||
        "protected-f".equals(command);
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

  /**
   * Transforms the input jar with the commands parsed by {@link #readAT(Path)}
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
      if(classCommands.containsKey(cn.name)) cn.access = getModifiedAccess(cn.access, classCommands.get(cn.name));
      cn.fields.stream().filter(f -> fieldCommands.getOrDefault(cn.name, new HashMap<>()).containsKey(f.name)).forEach(f -> f.access = getModifiedAccess(f.access, fieldCommands.get(cn.name).get(f.name)));
      cn.methods.forEach(mn -> {
        if(Util.hasNone(mn.access, Opcodes.ACC_PRIVATE, Opcodes.ACC_STATIC))
          inheritable.computeIfAbsent(cn.name, s -> new HashSet<>()).add(mn.name + mn.desc);
      });
    });
    classes.values().forEach(cn -> cn.methods.forEach(mn -> mn.access = getModifiedAccess(mn.access, findCommand(cn, mn.name, mn.desc))));
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
   * Finds the command for a given method. if none is found it will try to recursively find it via
   * inheritance.
   *
   * @param cls
   *     the classNode containing the method
   * @param mdName
   *     the methods name
   * @param mdDesc
   *     the methods descriptor
   *
   * @return the command or null if not found
   */
  private String findCommand(ClassNode cls, String mdName, String mdDesc) {
    if(methodCommands.getOrDefault(cls.name, new HashMap<>(0)).containsKey(mdName + mdDesc))
      return methodCommands.get(cls.name).get(mdName + mdDesc);
    return findCommandRec(cls, mdName, mdDesc);
  }

  /**
   * Finds the command for a given method recursively, returning null if not found
   *
   * @param cls
   *     the classNode to search the method in
   * @param mdName
   *     the methods name
   * @param mdDesc
   *     the methods descriptor
   *
   * @return the command or null if not found
   */
  private String findCommandRec(ClassNode cls, String mdName, String mdDesc) {
    if(inheritable.getOrDefault(cls.name, new HashSet<>(0)).contains(mdName + mdDesc) && methodCommands.getOrDefault(cls.name, new HashMap<>()).containsKey(mdName + mdDesc))
      return methodCommands.get(cls.name).get(mdName + mdDesc);
    String result;
    if(classes.containsKey(cls.superName) && (result = findCommandRec(classes.get(cls.superName), mdName, mdDesc)) != null)
      return result;
    for(String iface : cls.interfaces)
      if(classes.containsKey(iface) && (result = findCommandRec(classes.get(iface), mdName, mdDesc)) != null)
        return result;
    return null;
  }

  /**
   * Reads the AccessTransformer File at path, initializing classMap, fieldMap and methodMap.
   *
   * @param path
   *     the path to read
   *
   * @throws IOException
   *     if the path could not be read
   */
  private void readAT(Path path) throws IOException {
    Files.readAllLines(path).stream().filter(l -> !l.startsWith("#")).map(s -> s.split(" "))
        .forEach(words -> {
          if(words.length < 2) throw new IllegalArgumentException("line too short.");
          String command = words[0];
          if(!isValidCommand(command)) throw new IllegalArgumentException("illegal command.");
          String entity = words[1];
          if(!entity.contains(".")) {
            classCommands.put(entity, command);
            return;
          }
          String[] split = entity.split("\\.");
          if(split.length != 2)
            throw new IllegalArgumentException("line contains illegal identifier.");
          String className = split[0];
          String memberName = split[1];
          if(memberName.contains("("))
            getOrPut(methodCommands, className, new HashMap<>()).put(memberName, command);
          else getOrPut(fieldCommands, className, new HashMap<>()).put(memberName, command);
        });
  }
}
