package de.heisluft.deobf.tooling.at;

import de.heisluft.deobf.tooling.Util;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * An AccessTransformer with the file extension of .cfg. The file format is as follows:
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
public class CFGAccessTransformer implements Util, AccessTransformer {

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
    return switch(command) {
      case "public" -> (access & 0xfff8) | Opcodes.ACC_PUBLIC;
      case "protected" -> (access & 0xfff8) | Opcodes.ACC_PROTECTED;
      case "package" -> (access & 0xfff8);
      case "public-f" -> (access & 0xffe8) | Opcodes.ACC_PUBLIC;
      case "protected-f" -> (access & 0xffe8) | Opcodes.ACC_PROTECTED;
      case "package-f" -> (access & 0xffe8);
      default -> access;
    };
  }

  /**
   * Returns if an AT command is valid. Valid commands (and their effects):
   * <ul>
   *   <li>public: sets the access to public</li>
   *   <li>public-f: sets the access to public, removing the final modifier if present</li>
   *   <li>protected: sets the access to protected</li>
   *   <li>protected-f: sets the access to protected, removing the final modifier if present</li>
   *   <li>package: sets the access to package-private</li>
   *   <li>package-f: sets the access to package-private, removing the final modifier if present</li>
   * </ul>
   *
   * @param command
   *     the AT command to validate
   *
   * @return true if the given command is valid, false otherwise
   */
  private static boolean isValidCommand(String command) {
    return "public".equals(command) || "protected".equals(command) || "package".equals(command) ||
        "public-f".equals(command) || "protected-f".equals(command) || "package-f".equals(command);
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
  public CFGAccessTransformer(Path path) throws IOException {
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
            methodCommands.computeIfAbsent(className, k -> new HashMap<>()).put(memberName, command);
          else fieldCommands.computeIfAbsent(className, k -> new HashMap<>()).put(memberName, command);
        });
  }

  @Override
  public boolean providesMethodAccess(String className, String methodName, String methodDesc) {
    return methodCommands.containsKey(className) && methodCommands.get(className).containsKey(methodName + methodDesc);
  }

  @Override
  public int getMethodAccess(String className, String methodName, String methodDesc, int access) {
    if(!providesMethodAccess(className, methodName, methodDesc)) return access;
    return getModifiedAccess(access, methodCommands.get(className).get(methodName + methodDesc));
  }

  @Override
  public int getFieldAccess(String className, String fieldName, int access) {
    if(!fieldCommands.containsKey(className)) return access;
    Map<String, String> fdCommands = fieldCommands.get(className);
    return fdCommands.containsKey(fieldName) ? getModifiedAccess(access, fdCommands.get(fieldName)) : access;
  }

  @Override
  public int getClassAccess(String className, int access) {
    return classCommands.containsKey(className) ? getModifiedAccess(access, classCommands.get(className)) : access;
  }
}
