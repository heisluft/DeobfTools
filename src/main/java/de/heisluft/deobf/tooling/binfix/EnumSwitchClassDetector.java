package de.heisluft.deobf.tooling.binfix;

import de.heisluft.deobf.tooling.Util;
import de.heisluft.deobf.tooling.mappings.MappingsBuilder;
import de.heisluft.stream.BiStream;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnList;

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
public class EnumSwitchClassDetector implements Util, MappingsProvider {

  /** The mappings builder used in this run. */
  private MappingsBuilder builder;

  @Override
  public void setBuilder(MappingsBuilder builder) {
    this.builder = builder;
  }

  @Override
  public MappingsBuilder getBuilder() {
    return builder;
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
   * @param dirtyClasses a set of classes to be re-serialized. filled up, but never removed from.
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
      int i = 1;
      String renBase = useNode.name + "$";
      while (builder.hasClassNameTarget(renBase + i)) i++;
      builder.addClassMapping(synNode.name, renBase + i);
      builder.addFieldMapping(synNode.name, synNode.fields.get(0).name, "$SwitchMap$" + useNode.name.replace('/', '$'));
      InnerClassNode nestDesc = new InnerClassNode(syn, null, null, synNode.access);
      System.out.println(syn + " is an enum switch lookup class. Making it a nested class of " + useNode.name);
      useNode.innerClasses.add(nestDesc);
      synNode.innerClasses.add(nestDesc);
      synNode.outerClass = useNode.name;
      dirtyClasses.add(useNode.name);
      dirtyClasses.add(syn);
    });
  }
}
