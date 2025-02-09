package de.heisluft.deobf.tooling;

import de.heisluft.function.Tuple2;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.objectweb.asm.Opcodes.*;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class Sigmund implements Util{

  private final JDKClassProvider classProvider;
  private final Map<String, ClassNode> classes;
  private final Map<String, Map<String, Tuple2<FieldNode, Map<String, Integer>>>> paramGuesses = new HashMap<>();

  private boolean isAssignableFrom(String maybe, String impl) {
    if(maybe.equals(impl)) return true;
    ClassNode maybeImplNode = classes.containsKey(impl) ? classes.get(impl) : classProvider.getClassNode(impl);
    if(maybeImplNode == null) return false; // Can't determine
    if(maybeImplNode.superName == null || maybeImplNode.superName.equals("java/lang/Object") && maybeImplNode.interfaces.isEmpty()) return false;
    if(maybe.equals(maybeImplNode.superName)) return true;
    if(maybeImplNode.interfaces.contains(maybe)) return true;
    return isAssignableFrom(maybe, maybeImplNode.superName) || maybeImplNode.interfaces.stream().anyMatch(s -> isAssignableFrom(maybe, s));
  }

  private Sigmund() throws IOException {
    classes = parseClasses(Paths.get("remap-tests/jars/mc/client/alpha/a1.1.2_01.jar"));
    classProvider = new JDKClassProvider(Paths.get("C:\\Program Files\\Java\\jdk-8"));
  }

  private void detect() {
    classes.values().forEach(classNode -> {
      classNode.fields.forEach(fieldNode -> {
        if(fieldNode.signature != null) return;
        String desc = fieldNode.desc;
        if(desc.startsWith("[")) desc = desc.substring(desc.lastIndexOf('[') + 1);
        if(!desc.startsWith("L")) return;
        String cName = desc.substring(1, desc.length() - 1);
        if(classes.containsKey(cName)) return;
        ClassNode c = classProvider.getClassNode(cName);
        if(c == null || c.signature == null || !c.signature.startsWith("<")) return;
        paramGuesses.computeIfAbsent(classNode.name, k -> new HashMap<>()).put(fieldNode.name, new Tuple2<>(fieldNode, null));
      });
    });

    classes.values().forEach(classNode -> {

      classNode.methods.forEach(methodNode -> {
        methodNode.instructions.forEach(mn -> {
          if(mn.getOpcode() != GETFIELD && mn.getOpcode() != GETSTATIC) return;
          FieldInsnNode fin = ((FieldInsnNode) mn);
          if(!paramGuesses.containsKey(fin.owner) || !paramGuesses.get(fin.owner).containsKey(fin.name)) return;
          System.out.println(classNode.name + "#" + methodNode.name + methodNode.desc + " accesses maybe generic field " + fin.owner + "#" + fin.name + ", lets trace");
          AbstractInsnNode next = mn.getNext();
          while(!(next instanceof JumpInsnNode)) {
            if(next == null) return; // Hit end of code, unfortunate
            if(next instanceof MethodInsnNode && isAssignableFrom(((MethodInsnNode) next).owner, fin.desc.substring(1, fin.desc.length() - 1))) {
              MethodInsnNode meth = (MethodInsnNode) next;
              ClassNode callee = classes.containsKey(meth.owner) ? classes.get(meth.owner) : classProvider.getClassNode(meth.owner);
              if(callee == null) return;
              Optional<MethodNode> called = callee.methods.stream().filter(m -> m.name.equals(meth.name) && m.desc.equals(meth.desc)).findFirst();
              if(!called.isPresent()) return;
              MethodNode calledNode = called.get();
              if(calledNode.signature == null) return;
              System.out.println();
              break;
            }
            next = next.getNext();
          }
          /*if(!(mn instanceof MethodInsnNode)) return;
          MethodInsnNode min = ((MethodInsnNode) mn);
          if(!min.owner.equals("java/util/Iterator") || !min.name.equals("next")) return;
          AbstractInsnNode next = min.getNext();
          if(next.getOpcode() != CHECKCAST) return;
          System.out.println("Found Iterator of type " + ((TypeInsnNode)next).desc + " in " + classNode.name + "#" + methodNode.name + methodNode.desc);
          AbstractInsnNode prev = min.getPrevious();
          if(prev.getOpcode() == ALOAD) {
            System.out.println("");
            System.out.println("Loaded from " + ((VarInsnNode)prev).var);
          }
          else if(prev.getOpcode() == INVOKEINTERFACE) {
            MethodInsnNode pMin = ((MethodInsnNode) prev);
            if(!pMin.desc.startsWith("()")) return; // only accept no args for now
            System.out.println("In Situ from " + pMin.owner + "#" + pMin.name + pMin.desc);
            prev = prev.getPrevious();
            if(prev.getOpcode() != GETFIELD) {
              System.out.println("Test1");
              return;
            }
            FieldInsnNode fin = (FieldInsnNode) prev;
            if(classes.containsKey(fin.owner)) System.out.println("Found type specialization for " + fin.owner + "#" + fin.name + " of type '" + fin.desc + "': " + ((TypeInsnNode)next).desc);
          }*/

        });
      });
    });
  }

  public static void main(String[] args) throws Exception {
    new Sigmund().detect();
  }
}
