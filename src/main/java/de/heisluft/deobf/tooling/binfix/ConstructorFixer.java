package de.heisluft.deobf.tooling.binfix;

import de.heisluft.deobf.tooling.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.ACC_STATIC;

/**
 * The constructor fixer is a tool to move empty super() calls to the first position and adding constructors for classes
 * that miss them.
 * <br>
 * Generally it should be preferred to just restore the classes status as an inner class, but this may not always work.
 * so this tool is run last
 */
public class ConstructorFixer implements Util {

  /**
   * Fixes the constructors of non restored inner classes and adds constructors as needed.
   *
   * @param classes the map of all parsed classes. values will be mutated.
   * @param dirtyClasses a set of classes to be re-serialized. added to, but never removed from.
   */
  void fixConstructors(Map<String, ClassNode> classes, Set<String> dirtyClasses) {
    classes.forEach((s, classNode) -> {
      if(transformClassNode(classNode, classes)) dirtyClasses.add(s);
    });
  }

  /**
   * Transforms a single class node
   *
   * @param cn
   *     the unmodified class node
   *
   * @return whether the class node was transformed
   */
  private boolean transformClassNode(ClassNode cn, Map<String, ClassNode> classCache) {
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
      if(i == 0 || i == m.instructions.size()) return false;
      // Instance Inner Classes should not have their constructor data shuffled
      // The code is left in place for classes whose data was not restored
      if(Util.hasNone(cn.access, ACC_STATIC) && cn.innerClasses.stream().anyMatch(icn -> icn.name.equals(cn.name))) return false;
      System.out.println("Fixing Class: " + cn.name + " (extending " + cn.superName + ") super call offset: " + i);
      // super call is not first
      AbstractInsnNode aload0 = m.instructions.get(i);
      m.instructions.remove(aload0);
      AbstractInsnNode ivsp = m.instructions.get(i);
      m.instructions.remove(ivsp);
      m.instructions.insert(ivsp);
      m.instructions.insert(aload0);
      return true;
    }

    ClassNode superNode = classCache.get(cn.superName);
    if(superNode == null) return false;
    System.out.println("class " + cn.name + " has no constructor... checking if one is needed");
    Set<MethodNode> superCtors = new HashSet<>();
    superNode.methods.stream().filter(mn -> "<init>".equals(mn.name)).forEach(superCtors::add);
    if(superCtors.isEmpty()) {
      System.out.println("No constructor needed, super has none");
      return false;
    }
    if(superCtors.stream().map(mn -> mn.desc).anyMatch("()V"::equals)) {
      System.out.println("Super has default constructor, we dont need to create one");
      return false;
    }
    if(superCtors.size() > 1) {
      System.out.println("super has multiple non-default constructor, manual patching will be needed");
      return false;
    }
    MethodNode singleCtor = superCtors.iterator().next();
    System.out.println("Adding constructor matching super, desc: " + singleCtor.desc);
    cn.methods.add(0, createConstructor(singleCtor.desc, cn.superName, singleCtor.access));
    return true;
  }

  /**
   * Creates a constructor MethodNode with the specified signature, relaying all the descriptors args to
   * a call to the specified superclass constructor
   *
   * @param desc
   *      the descriptor of the constructor
   * @param superName
   *      the name of the class to be called within the method
   * @param superAcc
   *      the access of the superclass constructor.
   * @return the resulting MethodNode
   */
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
}
