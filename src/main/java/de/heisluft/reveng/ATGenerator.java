package de.heisluft.reveng;

import org.objectweb.asm.tree.ClassNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.objectweb.asm.Opcodes.*;

/**
 * A tool to automatically generate AccessTransformer files to fix
 * recompilation errors when a bytecode method has stricter access than the method it is overriding
 */
public class ATGenerator implements Util {

  /**
   * Generates and writes an AccessTransformer config file for the given input jar
   *
   * @param input
   *     the input jar to parse classes from
   * @param atPath
   *     the path to the AccessTransformer file
   *
   * @throws IOException if the input jar could not be fully read or the config file could not be written
   */
  private void generateAT(Path input, Path atPath) throws IOException {
    Map<String, ClassNode> classNodes = parseClasses(input);
    // The accesses of all inheritable methods, format: (cn.name + "#" + mn.name + mn.desc) -> mn.access
    Map<String, Integer> inheritable = new HashMap<>();
    // First pass: populate inheritable methods
    classNodes.values().forEach(cn -> {
      // Nothing to inherit from final classes
      if((cn.access & ACC_FINAL) != 0) return;
      cn.methods.forEach(mn -> {
        if((mn.access & ACC_FINAL) != 0) return;
        if((mn.access & ACC_PRIVATE) != 0) return;
        // We are not interested in static Methods in this case,
        // as hiding a static method from a super class is not a compilation error
        if((mn.access & ACC_STATIC) != 0) return;
        // Constructors are not constrained
        if(mn.name.equals("<init>")) return;
        inheritable.put(cn.name + "#" + mn.name + mn.desc, mn.access);
      });
    });
    // The list of lines to write
    List<String> lines = new ArrayList<>();
    // A list of already processed methods, format: (cn.name + "#" + mn.name + mn.desc)
    Set<String> modified = new HashSet<>();
    // Multiple passes to include errors down the line
    AtomicBoolean dirty = new AtomicBoolean(false);
    do {
      dirty.set(false);
      classNodes.values().forEach(cn -> {
        // This is kinda arbitrary, but for now it covers all cases
        // As I don't know where the weirdness comes from in the first place,
        // I don't know whether interfaces or standard-lib overrides are affected
        // So just exclude them
        if (!classNodes.containsKey(cn.superName)) return;
        cn.methods.forEach(mn -> {
          // Already modified methods should not be parsed twice
          if(modified.contains(cn.name + "#" + mn.name + mn.desc)) return;

          // Iterate supers until we find the right method
          String superName = cn.superName;
          while (!inheritable.containsKey(superName + "#" + mn.name + mn.desc)) {
            if(classNodes.containsKey(superName)) superName = classNodes.get(superName).superName;
            else return;
          }
          // We found the right super class, now get the access
          int superAccess = inheritable.get(superName + "#" + mn.name + mn.desc);

          if(Util.hasNone(superAccess, ACC_PUBLIC, ACC_PROTECTED) && (mn.access & ACC_PRIVATE) != 0) {
            lines.add("package " + cn.name + "." + mn.name + mn.desc);
            dirty.set(true);
            inheritable.put(cn.name + "#" + mn.name + mn.desc, mn.access & ~ACC_PRIVATE);
            modified.add(cn.name + "#" + mn.name + mn.desc);
            return;
          }
          if((superAccess & ACC_PROTECTED) != 0 && Util.hasNone(mn.access, ACC_PUBLIC, ACC_PROTECTED)) {
            lines.add("protected " + cn.name + "." + mn.name + mn.desc);
            dirty.set(true);
            inheritable.put(cn.name + "#" + mn.name + mn.desc, mn.access & ~ACC_PRIVATE | ACC_PROTECTED);
            modified.add(cn.name + "#" + mn.name + mn.desc);
            return;
          }
          if((superAccess & ACC_PUBLIC) != 0 && (mn.access & ACC_PUBLIC) == 0) {
            lines.add("public " + cn.name + "." + mn.name + mn.desc);
            dirty.set(true);
            inheritable.put(cn.name + "#" + mn.name + mn.desc, mn.access & ~ACC_PRIVATE & ~ACC_PROTECTED | ACC_PUBLIC);
            modified.add(cn.name + "#" + mn.name + mn.desc);
          }
        });
      });
    } while (dirty.get());
      try (OutputStream out = Files.newOutputStream(atPath);
           BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
        for (String line : lines) {
          writer.write(line);
          writer.write('\n');
        }
      }
  }

  public static void main(String[] args) throws IOException {
    if(args.length < 2) {
      System.err.println("Usage: ATGenerator <jarFile> <atFile>");
      System.exit(1);
    }
    new ATGenerator().generateAT(Paths.get(args[0]), Paths.get(args[1]));
  }
}
