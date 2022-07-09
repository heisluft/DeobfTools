package de.heisluft.reveng.nests;

import de.heisluft.function.Tuple2;
import de.heisluft.reveng.Util;
import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class InnerClassDetector implements Util {

  private static final int NONE = -1, INSTANCE = 0, STATIC = 1;

  public static void main(String[] args) throws IOException {
    new InnerClassDetector().detect(Paths.get("../compilertest/build/libs/compilertest.jar"));
  }

  private static Predicate<FieldNode> isNonSynPrivFieldOfDesc(String desc) {
    return fn -> (fn.access & ACC_SYNTHETIC) == 0 && (fn.access & ACC_PRIVATE) != 0 && fn.desc.equals(desc);
  }

  private static Predicate<MethodNode> isNonSynPrivMetOfDesc(String cName, String staticDesc) {
    String instDesc = null;
    Type[] argTypes = Type.getArgumentTypes(staticDesc);
    if(argTypes.length > 0)
      instDesc = Type.getMethodDescriptor(Type.getReturnType(staticDesc), Arrays.stream(argTypes).skip(1).toArray(Type[]::new));
    String instanceDesc = instDesc;
    return mn ->
        (mn.access & ACC_SYNTHETIC) == 0 &&
            (mn.access & ACC_PRIVATE) != 0 &&
            ((mn.access & ACC_STATIC) == 0 ?
                mn.desc.equals(instanceDesc) :
                mn.desc.equals(staticDesc));
  }

  private boolean isReturnOpCode(int opcode) {
    return opcode >= IRETURN && opcode <= RETURN;
  }

  private int getMethodAccessType(InsnList insnList, String cName, Set<Tuple2<String, String>> matchedMets) {
    // We found no methods to relay
    if(matchedMets.isEmpty()) return NONE;
    List<Integer> loadedArgs = new ArrayList<>();
    Boolean staticLock = null;
    for(AbstractInsnNode ain : insnList) {
      // as always we skip debug instructions
      if(ain instanceof LineNumberNode
          || ain instanceof FrameNode
          || ain instanceof LabelNode) continue;

      int opCode = ain.getOpcode();
      // A method accessor does not branch, break after return
      if(isReturnOpCode(opCode)) break;
      // A method accessor calls only one method, its relay.
      if(staticLock != null) return NONE;
      if(ain instanceof VarInsnNode) {
        int argNum = ((VarInsnNode) ain).var;
        // We should push each arg only ONCE, ...
        if(loadedArgs.contains(argNum)) return NONE;
        // ...starting at 0, ...
        if(loadedArgs.isEmpty() && argNum != 0) return NONE;
        // ...in order
        if(!loadedArgs.isEmpty() && loadedArgs.get(loadedArgs.size() - 1) != argNum - 1) return NONE;
        loadedArgs.add(argNum);
        continue;
      }
      // A method accessor should only ever relay method calls
      // invokevirtual and invokestatic are the only permitted calls
      if(!(ain instanceof MethodInsnNode) || opCode == INVOKEVIRTUAL || opCode == INVOKEINTERFACE) {
        return NONE;
      }
      MethodInsnNode min = (MethodInsnNode)ain;
      // Accessors only relay within their own class
      if(!cName.equals(min.owner)) return NONE;
      // Only private methods have accessors generated
      if(!matchedMets.contains(new Tuple2<>(min.name, min.desc))) return NONE;
      staticLock = opCode == INVOKESTATIC;
    }
    return staticLock == null ? NONE : staticLock ? STATIC : INSTANCE;
  }

  /**
   * Checks a methods instructions for clues if they could be a field accessor.
   * The checks run are very strict so they may not recognize all methods, but those that are recognized
   * are very certain hits
   *
   * NOTE: This makes assumptions about the compilers implementation and is tested only against javac 1.5 and upper
   *
   * @param insnList the methods instructions
   * @param cName the name of the containing class
   * @param matchedFields a list of field names with matching descriptors
   * @return
   */
  private boolean isFieldAccessMethod(InsnList insnList, String cName, Set<String> matchedFields) {
    // we should only access one single field so we store it as soon as we encounter it.
    String fieldLock = null;
    Boolean staticLock = null; // Ya we need that good old tri state bool
    for(AbstractInsnNode ain : insnList) {
      // These are either debug or are already accounted for, skip them
      if(ain instanceof LineNumberNode
          || ain instanceof FrameNode
          || ain instanceof LabelNode
          || ain instanceof VarInsnNode) continue;
      // An accessor should not have complex logic, generally disallow switches, jumps, invokedynamic
      // multianew and ldc
      if(ain instanceof TableSwitchInsnNode
          || ain instanceof MultiANewArrayInsnNode
          || ain instanceof LdcInsnNode
          || ain instanceof InvokeDynamicInsnNode
          || ain instanceof JumpInsnNode
          || ain instanceof LookupSwitchInsnNode) return false;
      // We forbid ref creation, only exception is the += operator generating a StringBuilder
      if(ain instanceof TypeInsnNode) {
        if(ain.getOpcode() == ANEWARRAY) return false;
        if(ain.getOpcode() == NEW && !"java/lang/StringBuilder".equals(((TypeInsnNode) ain).desc)) return false;
      }
      // Accessors of static fields should not operate on instance fields and vice-versa
      if(ain instanceof FieldInsnNode) {
        if(staticLock != null) {
          if(staticLock && ain.getOpcode() == GETFIELD || staticLock && ain.getOpcode() == PUTFIELD)
            return false;
          if(!staticLock && ain.getOpcode() == GETSTATIC || !staticLock && ain.getOpcode() == PUTSTATIC)
            return false;
        }
        FieldInsnNode fin = (FieldInsnNode) ain;
        // Access to fields other than the ones within the class itself are disallowed
        if(!cName.equals(fin.owner)) return false;
        if(fieldLock != null && !fieldLock.equals(fin.name)) return false;
        // we should not access any fields with a descriptor other than the methods return type
        if(!matchedFields.contains(fin.name)) return false;
        // set locks
        fieldLock = fin.name;
        staticLock = ain.getOpcode() == GETSTATIC || ain.getOpcode() == PUTSTATIC;
      }
      // only the += string accessor is actually invoking methods
      // and it is only calling string builder once.
      if(ain instanceof MethodInsnNode) {
        MethodInsnNode min = (MethodInsnNode) ain;
        if(ain.getOpcode() == INVOKEINTERFACE || ain.getOpcode() == INVOKESTATIC) return false;
        if(!min.owner.equals("java/lang/StringBuilder")) return false;
        switch(min.name) {
          case "<init>":
          case "append":
          case "toString": break;
          default: return false;
        }
      }
    }
    return true;
  }

  public void detect(Path input) throws IOException {
    // the set of all classes, mapped by their respective name
    final Map<String, ClassNode> classes = parseClasses(input);
    // a set of all synthetic field names for each class name
    final Map<String, Set<String>> synFields = new HashMap<>();
    final Map<String, Set<Tuple2<String, String>>> staticAccessors = new HashMap<>();
    final Map<String, Set<Tuple2<String, String>>> instanceAccessors = new HashMap<>();
    final Map<String, Integer> instanceInvocations = new HashMap<>();
    final Map<String, Set<String>> instanceClasses = new HashMap<>();
    final Map<String, Set<String>> anonInnerClasses = new HashMap<>();

    classes.values().forEach(cn -> {
      // skip synthetic classes, they'd ideally be checked for enum switches, TODO: Merge
      if((cn.access & ACC_SYNTHETIC) != 0) return;
      // Enums can never be instance inner classes, so we just skip them
      if(!cn.superName.equals("java/lang/Enum")) cn.fields.stream()
          .filter(fn -> (fn.access & ACC_SYNTHETIC) != 0)
          .forEach(fn -> getOrPut(synFields, cn.name, new HashSet<>()).add(fn.name));

      cn.methods.forEach(mn -> {
        if((mn.access & ACC_SYNTHETIC) == 0) {
          if(!"<init>".equals(mn.name)) return;
          @SuppressWarnings("unchecked")
          Set<String> synFieldsForClass = new HashSet<>(synFields.getOrDefault(cn.name, Collections.EMPTY_SET));
          if(synFieldsForClass.isEmpty()) return;

          Type[] argTypes = Type.getArgumentTypes(mn.desc);
          if(argTypes.length == 0) return;

          boolean supInvokFound = false;

          for(AbstractInsnNode ain : mn.instructions) {
            if(!supInvokFound && ain.getOpcode() == ALOAD && ((VarInsnNode) ain).var == 0) {
              AbstractInsnNode next = ain.getNext();
              if(!isLoadInsn(next.getOpcode())) continue;
              int local = ((VarInsnNode) next).var;
              next = next.getNext();
              if(next.getOpcode() != PUTFIELD) continue;
              FieldInsnNode fin = (FieldInsnNode) next;
              if(!fin.owner.equals(cn.name) || !argTypes[local - 1].getDescriptor().equals(fin.desc)) continue;
              if(!synFieldsForClass.contains(fin.name)) continue;
              synFieldsForClass.remove(fin.name);
            }
            if(ain.getOpcode() != INVOKESPECIAL) continue;
            supInvokFound = true;
          }
          if(!synFieldsForClass.isEmpty()) {
            System.out.println("class " + cn.name + " initializes non-synthetic fields before super()?");
            return;
          }
          String outerName = argTypes[0].getInternalName();
          if(synFields.get(cn.name).size() > 1) getOrPut(anonInnerClasses, outerName, new HashSet<>()).add(cn.name);
          else {
            getOrPut(instanceClasses, outerName, new HashSet<>()).add(cn.name);
            instanceInvocations.put(cn.name, 0);
          }
        }
        String retDesc = mn.desc.substring(mn.desc.lastIndexOf(')') + 1);
        Type[] argTypes = Type.getArgumentTypes(mn.desc);
        int methodAccessType = getMethodAccessType(mn.instructions, cn.name,
            cn.methods.stream()
                .filter(isNonSynPrivMetOfDesc(cn.name, mn.desc))
                .map(match -> new Tuple2<>(match.name, match.desc)).collect(Collectors.toSet())
        );
        if(methodAccessType != NONE) {
          if(methodAccessType == STATIC)
            getOrPut(staticAccessors, cn.name, new HashSet<>()).add(new Tuple2<>(mn.name, mn.desc));
          else
            getOrPut(instanceAccessors, cn.name, new HashSet<>()).add(new Tuple2<>(mn.name, mn.desc));
          return;
        }

        // Accessors generated by javac have at max 2 arguments
        if(argTypes.length > 2) return;
        Set<String> maybeRetFields = cn.fields.stream().filter(isNonSynPrivFieldOfDesc(retDesc))
            .map(fn -> fn.name).collect(Collectors.toSet());
        if(maybeRetFields.isEmpty()) return;
        // This is only true for static inner getters
        if(argTypes.length == 0) {
          if(isFieldAccessMethod(mn.instructions, cn.name, maybeRetFields))
            getOrPut(staticAccessors, cn.name, new HashSet<>()).add(new Tuple2<>(mn.name, mn.desc));
          return;
        }
        // This coveres all instance accessors
        if(argTypes[0].getInternalName().equals(cn.name)) {
          if(argTypes.length == 2 && argTypeIsntReturnType(argTypes[1].getDescriptor(), retDesc))
            return;
          if(isFieldAccessMethod(mn.instructions, cn.name, maybeRetFields))
            getOrPut(instanceAccessors, cn.name, new HashSet<>()).add(new Tuple2<>(mn.name, mn.desc));
          return;
        }
        // Two args require the first to be of the classes type
        if(argTypes.length == 2) return;
        // the last type: static mutating accessors
        if(argTypeIsntReturnType(argTypes[0].getDescriptor(), retDesc)) return;
        if(isFieldAccessMethod(mn.instructions, cn.name, maybeRetFields))
          getOrPut(staticAccessors, cn.name, new HashSet<>()).add(new Tuple2<>(mn.name, mn.desc));
      });
    });
    System.out.println("anonInnerClasses: " + anonInnerClasses);
    System.out.println("instanceClasses " + instanceClasses);

    Set<String> instanceClassSet = instanceClasses.values().stream().flatMap(Set::stream).collect(Collectors.toSet());

    classes.values().forEach(cn -> {

      cn.methods.forEach(mn -> {
        for(AbstractInsnNode ain : mn.instructions) {
          if(ain instanceof MethodInsnNode) {
            MethodInsnNode min = (MethodInsnNode) ain;
            String owner = min.owner;
            if(instanceClassSet.contains(owner)) instanceInvocations.put(owner, instanceInvocations.get(owner) + 1);
          }
        }
      });
    });
    System.out.println(instanceInvocations);
  }

  private static boolean isSamePackage(String c1, String c2) {
    if(!c1.contains("/")) return !c2.contains("/");
    return c2.contains("/") &&
        c1.substring(0, c1.lastIndexOf('/')).equals(c2.substring(0, c2.lastIndexOf('/')));
  }

  private static boolean isLoadInsn(int opCode) {
    return opCode >= ILOAD && opCode <= ALOAD;
  }

  private static boolean argTypeIsntReturnType(String argType, String returnType) {
    if(argType.equals(returnType)) return false;
    switch(returnType) {
      case "Z":
      case "B":
      case "C":
      case "S": return !"I".equals(argType);
      case "Ljava/lang/String;": return !"Ljava/lang/Object;".equals(argType);
    }
    return true;
  }
}
