package de.heisluft.deobf.tooling.binfix;

import de.heisluft.deobf.tooling.Util;
import de.heisluft.function.Tuple2;

import static org.objectweb.asm.Opcodes.*;

import de.heisluft.deobf.tooling.mappings.MappingsBuilder;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class InnerClassDetector implements Util, MappingsProvider {

  private MappingsBuilder builder;

  @Override
  public void setBuilder(MappingsBuilder builder) {
    this.builder = builder;
  }

  @Override
  public MappingsBuilder getBuilder() {
    return builder;
  }

  private static final int NONE = -1, INSTANCE = 0, STATIC = 1;

  private static Predicate<FieldNode> isNonSynPrivFieldOfDesc(String desc) {
    return fn -> (fn.access & ACC_SYNTHETIC) == 0 && (fn.access & ACC_PRIVATE) != 0 && fn.desc.equals(desc);
  }

  private static Predicate<MethodNode> isNonSynPrivMetOfDesc(String staticDesc) {
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
   * The checks run are very strict, so they may not recognize all methods, but those that are recognized
   * are very certain hits.
   * <br>
   * NOTE: This makes assumptions about the compilers implementation and is tested only against javac 1.5 and upper
   *
   * @param insnList the methods instructions
   * @param cName the name of the containing class
   * @param matchedFields a list of field names with matching descriptors
   * @return whether the method was most likely a field accessor
   */
  private boolean isFieldAccessMethod(InsnList insnList, String cName, Set<String> matchedFields) {
    // we should only access one single field, so we store it as soon as we encounter it.
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

  public void detect(Map<String, ClassNode> classes, Set<String> dirtyClasses) {
    // the set of all classes, mapped by their respective name
    // a set of all synthetic field names for each class name
    final Map<String, Set<String>> synFields = new HashMap<>();
    final Map<String, Set<Tuple2<String, String>>> staticAccessors = new HashMap<>();
    final Map<String, Set<Tuple2<String, String>>> instanceAccessors = new HashMap<>();
    final Map<String, Set<String>> instanceClasses = new HashMap<>();
    final Map<String, Map<Tuple2<String, String>, Set<String>>> anons = new HashMap<>();
    final Map<String, Set<String>> nonAnons = new HashMap<>();
    final Map<String, String> reverseOuterLookup = new HashMap<>();

    classes.values().forEach(cn -> {
      // skip synthetic classes, they can be checked for enum switches with EnumSwitchClassDetector
      if((cn.access & ACC_SYNTHETIC) != 0) return;
      // Enums can never be instance inner classes, so we just skip them
      if(!cn.superName.equals("java/lang/Enum")) cn.fields.stream()
          .filter(fn -> (fn.access & ACC_SYNTHETIC) != 0)
          .forEach(fn -> getOrPut(synFields, cn.name, new HashSet<>()).add(fn.name));

      cn.methods.forEach(mn -> {
        if((mn.access & ACC_SYNTHETIC) == 0) {
          if(!"<init>".equals(mn.name)) return;
          Set<String> synFieldsForClass = new HashSet<>(synFields.getOrDefault(cn.name, Collections.emptySet()));
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
          // Anonymous classes must have a constructor with package visibility
          if((mn.access & 0b111) != 0) getOrPut(nonAnons, outerName, new HashSet<>()).add(cn.name);
          else getOrPut(instanceClasses, outerName, new HashSet<>()).add(cn.name);
          reverseOuterLookup.put(cn.name, outerName);
        }
        String retDesc = mn.desc.substring(mn.desc.lastIndexOf(')') + 1);
        Type[] argTypes = Type.getArgumentTypes(mn.desc);
        int methodAccessType = getMethodAccessType(mn.instructions, cn.name,
            cn.methods.stream().filter(isNonSynPrivMetOfDesc(mn.desc))
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

    instanceClasses.values().stream().flatMap(Set::stream).forEach(name -> {
      String outer = reverseOuterLookup.get(name);
      MethodNode outerMethod = null;
      for(ClassNode cn : classes.values()) {
        if(cn.name.equals(name)) continue;
        for(MethodNode m : cn.methods) {
          Tuple2<AnonRef, String> tup = getRefType(m, name);
          switch(tup._1) {
            case DISQUALIFYING:
              System.out.println("Class " + name + " cannot be anonymous: " + tup._2);
              getOrPut(nonAnons, outer, new HashSet<>()).add(name);
              return;
            case LEGAL:
              if(outerMethod != null) {
                System.out.println("Class " + name + " is referenced from more than one method. It cannot be anonymous.");
                getOrPut(nonAnons, outer, new HashSet<>()).add(name);
                return;
              }
              outerMethod = m;
              break;
          }
        }
      }
      if(outerMethod == null) {
        System.out.println("Class " + name + " is never used. It cannot be anonymous.");
        getOrPut(nonAnons, outer, new HashSet<>()).add(name);
      } else {
        getOrPut(getOrPut(anons, outer, new HashMap<>()), new Tuple2<>(outerMethod.name, outerMethod.desc), new HashSet<>()).add(name);
      }
    });

    System.out.println("Anons: " + anons);
    System.out.println("Safe NonAnons: " + nonAnons);

    anons.forEach((outer, method2Inners) -> {
      dirtyClasses.add(outer);
      ClassNode outerNode = classes.get(outer);
      method2Inners.forEach((method, inners) -> {
        inners.forEach(inner -> {
          dirtyClasses.add(inner);
          ClassNode innerNode = classes.get(inner);
          InnerClassNode icn = new InnerClassNode(inner, null, null, 0);
          outerNode.innerClasses.add(icn);
          innerNode.innerClasses.add(icn);
          innerNode.outerMethod = method._1;
          innerNode.outerMethodDesc = method._2;
          innerNode.outerClass = outer;
        });
      });
    });
    nonAnons.forEach((outer, inners) -> {
      dirtyClasses.add(outer);
      ClassNode outerNode = classes.get(outer);
      inners.forEach(inner -> {
        dirtyClasses.add(inner);
        ClassNode innerNode = classes.get(inner);
        String innerSimpleName = inner.contains("$") ? inner.substring(inner.lastIndexOf('$') + 1)
            : inner.contains("/") ? inner.substring(inner.lastIndexOf('/') + 1) : inner; // fallback for obfuscated classes
        InnerClassNode icn = new InnerClassNode(inner, outer, innerSimpleName, innerNode.access & 0b11);
        outerNode.innerClasses.add(icn);
        innerNode.innerClasses.add(icn);
      });
    });
  }

  private static boolean isLoadInsn(int opCode) {
    return opCode >= ILOAD && opCode <= ALOAD;
  }

  private static Tuple2<AnonRef, String> getRefType(MethodNode mn, String cName) {
    boolean hasNew = false;
    boolean otherRefs = false;
    for(AbstractInsnNode ain : mn.instructions) {
      switch(ain.getType()) {
        case AbstractInsnNode.TYPE_INSN:
          if(!cName.equals(((TypeInsnNode) ain).desc)) continue;
          if(ain.getOpcode() == NEW)
            if(hasNew) return new Tuple2<>(AnonRef.DISQUALIFYING, "Class was instantiated twice");
          else hasNew = true;
          else return new Tuple2<>(AnonRef.DISQUALIFYING, "Cannot create arrays of or cast to anon classes");
          break;
        case AbstractInsnNode.FIELD_INSN:
          if(cName.equals(((FieldInsnNode) ain).owner))
            if(otherRefs) return new Tuple2<>(AnonRef.DISQUALIFYING, "An anonymous class may only be referenced once after creation");
            else otherRefs = true;
          break;
        case AbstractInsnNode.INVOKE_DYNAMIC_INSN: // TODO: Handle this. MC Classic is java 5, so dont hurry :D
          break;
        case AbstractInsnNode.METHOD_INSN:
          MethodInsnNode min = (MethodInsnNode) ain;
          if(cName.equals(min.owner) && !"<init>".equals(min.name))
            if(otherRefs) return new Tuple2<>(AnonRef.DISQUALIFYING, "An anonymous class may only be referenced once after creation");
            else otherRefs = true;
          break;
        case AbstractInsnNode.MULTIANEWARRAY_INSN:
          String desc = ((MultiANewArrayInsnNode)ain).desc;
          if(desc.contains(";") && cName.equals(desc.substring(desc.lastIndexOf('[' + 2), desc.length() - 1))) // Strip leading '['s, 'L' and trailing ';'
            return new Tuple2<>(AnonRef.DISQUALIFYING, "Cannot create arrays of anonymous classes");
          break;
        default:
      }
    }
    return hasNew ? new Tuple2<>(AnonRef.LEGAL, "") :
        otherRefs ? new Tuple2<>(AnonRef.DISQUALIFYING, "Class is acted upon without creating an ad-hoc instance") :
            new Tuple2<>(AnonRef.NONE, "");
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
