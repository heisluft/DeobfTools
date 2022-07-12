package de.heisluft.reveng.nests;

import de.heisluft.function.Tuple2;
import de.heisluft.reveng.Util;
import de.heisluft.stream.BiStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnList;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * This Utility can be used to resolve enum lookup switches, which are performed
 * by the compiler generating a synthetic inner class containing a lookupTable, which is then
 * invoked a tableswitch on. Obfuscators often strip information about inner classes, this tool
 * aims to restore it.
 */
public class EnumSwitchClassDetector implements Util {

  public static void main(String[] args) throws IOException {
    if(args.length < 2) {
      System.out.println("Usage EnumSwitchClassDetector <input> <output>");
      return;
    }
    new EnumSwitchClassDetector().restoreMeta(Paths.get(args[0]), Paths.get(args[1]));
  }

  /**
   * Checks the given instruction list for an instruction retrieving fName from cName statically.
   *
   * @param list the instruction list to check
   * @param cName the class name to look for
   * @param fName the field name to look for
   * @return true if the list contains the instruction, false otherwise
   */
  private static boolean containsFieldGet(InsnList list, String cName, String fName) {
    for(int i = 0; i < list.size(); i++) {
      AbstractInsnNode ain = list.get(i);
      if(ain.getOpcode() != Opcodes.GETSTATIC) continue;
      FieldInsnNode fin = ((FieldInsnNode) ain);
      if(!fin.owner.equals(cName)) continue;
      if(fin.name.equals(fName)) return true;
    }
    return false;
  }

  /**
   * Enum Switch classes have one package-private, static final int array, the lookupTable
   * This checks whether the given field node matches this pattern
   *
   * @param fn the field node to check
   * @return true if the field could have been a lookup table, false otherwise
   */
  private static boolean isLookupTableCandidate(FieldNode fn) {
    return "[I".equals(fn.desc) && fn.access == 0x1018;
  }

  /**
   * Restores the metadata of enumswitch lookup classes and their containing classes.
   * @param classes the map of all parsed classes. values will be mutated.
   * @param dirtyClasses a set of classes to be reserialized. filled up, but never removed from.
   */
  void restoreMeta(Map<String, ClassNode> classes, Set<String> dirtyClasses) {
    Map<String, String> candidates = new HashMap<>();
    classes.values().stream().filter(cn -> (cn.access & Opcodes.ACC_SYNTHETIC) != 0 && cn.fields.size() == 1 && isLookupTableCandidate(cn.fields.get(0))).forEach(n -> candidates.put(n.name, n.fields.get(0).name));
    Map<String, Set<String>> uses = new HashMap<>();
    classes.values().forEach(cn ->
        cn.methods.forEach(mn ->
            BiStream.streamMap(candidates).filter(
                (cName, fName) -> !cn.name.equals(cName) && containsFieldGet(mn.instructions, cName, fName)
            ).forEach((cName, fName) -> getOrPut(uses, cName, new HashSet<>()).add(cn.name))
        )
    );
    uses.forEach((syn, used) -> {
      if(used.size() != 1) return;
      ClassNode useNode = classes.get(used.iterator().next());
      ClassNode synNode = classes.get(syn);
      InnerClassNode nestDesc = new InnerClassNode(syn, null, null, synNode.access);
      System.out.println(syn + "is an enum switch lookup class. Making it a nested class of " + useNode.name);
      useNode.innerClasses.add(nestDesc);
      synNode.innerClasses.add(nestDesc);
      synNode.outerClass = useNode.name;
      dirtyClasses.add(useNode.name);
      dirtyClasses.add(syn);
    });
  }

  /**
   * Reads all classes of input,
   * Restores the Metadata of EnumSwitch lookup classes and their containing classes
   * and writes the resulting jar to output
   *
   * @param input tne input path
   * @param output the output path
   * @throws IOException if input could not be read or output could not be written
   */
  private void restoreMeta(Path input, Path output) throws IOException {
    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
    Map<String, ClassNode> classes = parseClasses(output);
    Set<String> dirtyClasses = new HashSet<>();
    restoreMeta(classes, dirtyClasses);
    if(dirtyClasses.isEmpty()) return;
    try(FileSystem fs = createFS(output)) {
      Path root = fs.getPath("/");
      for(String d : dirtyClasses) {
        ClassWriter writer = new ClassWriter(0);
        classes.get(d).accept(writer);
        Files.write(root.resolve(d + ".class"), writer.toByteArray());
      }
    }
  }
}
