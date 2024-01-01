package de.heisluft.deobf.tooling.binfix;

import de.heisluft.deobf.tooling.Util;
import de.heisluft.function.Tuple2;

import static org.objectweb.asm.Opcodes.*;

import de.heisluft.deobf.mappings.MappingsBuilder;
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

/**
 * A tool to restore innerclass attributes and inner classes' this$0 field and outer$inner name. As of now, only
 * instance inner classes are detected, support for static inner classes is planned.
 * <br>
 * Mappings for captured local vars in anonymous classes are not generated (as the local variable table was probably
 * wiped anyway), as are mappings for accessor methods (although support for this is planned).
 * <br>
 * The Mappings generated for named inner classes will match the pattern {@code outerClassName + "$" + className}, so
 * they will have to be revised whenever either the outer class or the inner class is renamed.
 * <br>
 * The Mappings generated for anonymous classes will match the pattern {@code outerClassName + "$" + i} where i is the
 * first positive integer for which a class with a name matching the generated name does not exist.
 */
//TODO: Emit mappings for accessor methods
//TODO: Use accessor methods to detect static inner classes
//TODO: Use Type instantiation to detect static anon classes
public class InnerClassDetector implements Util, MappingsProvider {

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
   * A type of reference to an instance inner class, used to detect whether it was an anonymous class.
   */
  private enum AnonRef {
    /** The class was not referenced at all */
    NONE,
    /** The class was referenced in a way that does not disqualify it from being an anonymous class */
    LEGAL,
    /** The class was referenced in a way that disqualifies it from being an anonymous class */
    DISQUALIFYING
  }

  /**
   * Class Members can be either static or instance members
   */
  private enum AccessType {
    /** Fields are accessed by getstatic and putstatic, methods by invokestatic */
    INSTANCE,
    /** Fields are accessed by getstatic and putstatic, methods by invokespecial, invokeinterface and invokevirtual */
    STATIC
  }

  /**
   * Creates a Predicate checking if a field node is both private and not synthetic, and if its descriptor matches
   * {@code desc}.
   * @param desc the descriptor to be checked against
   * @return the resulting predicate
   */
  private static Predicate<FieldNode> isNonSynPrivFieldOfDesc(String desc) {
    return fn -> (fn.access & ACC_SYNTHETIC) == 0 && (fn.access & ACC_PRIVATE) != 0 && fn.desc.equals(desc);
  }

  /**
   * Creates a Predicate checking if a method node is both private and not synthetic, and if its descriptor matches
   * an accessor descriptor. For instance methods this means that it matches if the first arg of the accessor descriptor
   * is equals to the class name.
   *
   * @param staticDesc the accessor static descriptor to be checked against
   * @return the resulting predicate
   */
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

  /**
   * Checks whether a specified instruction is a return instruction of any kind.
   * @param opcode the instruction OpCode to check
   * @return {@code true} if opCode denotes a return instruction, {@code false} otherwise
   */
  private boolean isReturnOpCode(int opcode) {
    return opcode >= IRETURN && opcode <= RETURN;
  }

  /**
   * Checks whether a specified instruction is a load instruction of any kind.
   * @param opCode the instruction OpCode to check
   * @return {@code true} if opCode denotes a load instruction, {@code false} otherwise
   */
  private static boolean isLoadInsn(int opCode) {
    return opCode >= ILOAD && opCode <= ALOAD;
  }

  /**
   * Checks whether a methods argument type is incompatible to its return type.
   *
   * @param argType the methods argument type
   * @param returnType the methods return type
   * @return whether a methods argument type is incompatible to its return type.
   */
  private static boolean incompatibleReturnType(String argType, String returnType) {
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

  /**
   * Checks how the way a class is referenced within a method qualifies or disqualifies it from being an anonymous class.
   *
   * @param mn the method to look into
   * @param cName the name of the instance inner class
   * @return a tuple consisting of either LEGAL, QUALIFYING (both with empty strings) or DISQUALIFYING (with the string
   * holding the reason for disqualification)
   */
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
          if(desc.contains(";") && cName.equals(desc.substring(desc.lastIndexOf('[') + 2, desc.length() - 1))) // Strip leading '['s, 'L' and trailing ';'
            return new Tuple2<>(AnonRef.DISQUALIFYING, "Cannot create arrays of anonymous classes");
          break;
        default:
      }
    }
    return hasNew ? new Tuple2<>(AnonRef.LEGAL, "") :
        otherRefs ? new Tuple2<>(AnonRef.DISQUALIFYING, "Class is acted upon without creating an ad-hoc instance") :
            new Tuple2<>(AnonRef.NONE, "");
  }

  /**
   * Checks a methods for the possibility of being an accessor method.
   * <br>
   * NOTE: This matches javac generated accessors. Other compilers may emit other accessors.
   *
   * @param insnList the instructions to be checked
   * @param cName the accessed class name
   * @param matchedMets a set of Tuples consisting of method names and descriptors
   * @return {@link AccessType#INSTANCE} if the method could have been an instance accessor,
   * {@link AccessType#STATIC} if the method could have been a static accessor, or {@code null} if it is neither
   */
  private AccessType getMethodAccessType(InsnList insnList, String cName, Set<Tuple2<String, String>> matchedMets) {
    // We found no methods to relay
    if(matchedMets.isEmpty()) return null;
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
      if(staticLock != null) return null;
      if(ain instanceof VarInsnNode) {
        int argNum = ((VarInsnNode) ain).var;
        // We should push each arg only ONCE, ...
        if(loadedArgs.contains(argNum)) return null;
        // ...starting at 0, ...
        if(loadedArgs.isEmpty() && argNum != 0) return null;
        // ...in order
        if(!loadedArgs.isEmpty() && loadedArgs.get(loadedArgs.size() - 1) != argNum - 1) return null;
        loadedArgs.add(argNum);
        continue;
      }
      // A method accessor should only ever relay method calls
      // invokevirtual and invokestatic are the only permitted calls
      if(!(ain instanceof MethodInsnNode) || opCode == INVOKEVIRTUAL || opCode == INVOKEINTERFACE) {
        return null;
      }
      MethodInsnNode min = (MethodInsnNode)ain;
      // Accessors only relay within their own class
      if(!cName.equals(min.owner)) return null;
      // Only private methods have accessors generated
      if(!matchedMets.contains(new Tuple2<>(min.name, min.desc))) return null;
      staticLock = opCode == INVOKESTATIC;
    }
    return staticLock == null ? null : staticLock ? AccessType.STATIC : AccessType.INSTANCE;
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

  /**
   * Runs the tool
   *
   * @param classes the map of all parsed classes. values will be mutated.
   * @param dirtyClasses a set of classes to be re-serialized. added to, but never removed from.
   */
  public void detect(Map<String, ClassNode> classes, Set<String> dirtyClasses) {
    // the set of all classes, mapped by their respective name
    // a set of all synthetic field names for each class name
    final Map<String, Set<String>> synFields = new HashMap<>();
    final Map<String, Set<Tuple2<String, String>>> staticAccessors = new HashMap<>();
    final Map<String, Set<Tuple2<String, String>>> instanceAccessors = new HashMap<>();
    final Map<String, Set<String>> instanceClasses = new HashMap<>();
    final Map<String, Map<Tuple2<String, String>, Set<String>>> anonInstanceClasses = new HashMap<>();
    final Map<String, Set<String>> namedInstanceClasses = new HashMap<>();
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
          if(argTypes.length == 0 || !classes.containsKey(argTypes[0].getInternalName())) return;

          boolean supInvokFound = false;

          for(AbstractInsnNode ain : mn.instructions) {
            if(!supInvokFound && ain.getOpcode() == ALOAD && ((VarInsnNode) ain).var == 0) {
              AbstractInsnNode next = ain.getNext();
              if(!isLoadInsn(next.getOpcode())) continue;
              int local = ((VarInsnNode) next).var;
              // Longs and doubles take up 2 Locals
              int doubleCorrection = 0;
              for (int i = 0; i < argTypes.length; i++) {
                if(i + 1 + doubleCorrection >= local) break;
                Type arg = argTypes[i];
                if (arg.equals(Type.DOUBLE_TYPE) || arg.equals(Type.LONG_TYPE))
                  doubleCorrection++;
              }
              local -= doubleCorrection;
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
          if((mn.access & 0b111) != 0) getOrPut(namedInstanceClasses, outerName, new HashSet<>()).add(cn.name);
          else getOrPut(instanceClasses, outerName, new HashSet<>()).add(cn.name);
          reverseOuterLookup.put(cn.name, outerName);
        }
        String retDesc = mn.desc.substring(mn.desc.lastIndexOf(')') + 1);
        Type[] argTypes = Type.getArgumentTypes(mn.desc);
        AccessType methodAccessType = getMethodAccessType(mn.instructions, cn.name,
            cn.methods.stream().filter(isNonSynPrivMetOfDesc(mn.desc))
                .map(match -> new Tuple2<>(match.name, match.desc)).collect(Collectors.toSet())
        );
        if(methodAccessType != null) {
          if(methodAccessType == AccessType.STATIC)
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
          if(argTypes.length == 2 && incompatibleReturnType(argTypes[1].getDescriptor(), retDesc))
            return;
          if(isFieldAccessMethod(mn.instructions, cn.name, maybeRetFields))
            getOrPut(instanceAccessors, cn.name, new HashSet<>()).add(new Tuple2<>(mn.name, mn.desc));
          return;
        }
        // Two args require the first to be of the classes type
        if(argTypes.length == 2) return;
        // the last type: static mutating accessors
        if(incompatibleReturnType(argTypes[0].getDescriptor(), retDesc)) return;
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
              getOrPut(namedInstanceClasses, outer, new HashSet<>()).add(name);
              return;
            case LEGAL:
              if(outerMethod != null) {
                System.out.println("Class " + name + " is referenced from more than one method. It cannot be anonymous.");
                getOrPut(namedInstanceClasses, outer, new HashSet<>()).add(name);
                return;
              }
              outerMethod = m;
              break;
          }
        }
      }
      if(outerMethod == null) {
        System.out.println("Class " + name + " is never used. It cannot be anonymous.");
        getOrPut(namedInstanceClasses, outer, new HashSet<>()).add(name);
      } else {
        getOrPut(getOrPut(anonInstanceClasses, outer, new HashMap<>()), new Tuple2<>(outerMethod.name, outerMethod.desc), new HashSet<>()).add(name);
      }
    });

    System.out.println("Anons: " + anonInstanceClasses);
    System.out.println("Safe NonAnons: " + namedInstanceClasses);

    anonInstanceClasses.forEach((outer, method2Inners) -> {
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
          int i = 1;
          String base = outer + "$";
          while (classes.containsKey(base + i) || builder.hasClassNameTarget(base + i)) i++;
          builder.addClassMapping(inner, outer + "$" + i);
          if(classes.get(outer).methods.stream().noneMatch(mn -> mn.name.equals(method._1) && mn.desc.equals(method._2) && (mn.access & ACC_STATIC) != 0)) {
            String[] synNames = innerNode.fields.stream().filter(fn -> (fn.access & ACC_SYNTHETIC) != 0).map(fn -> fn.name).toArray(String[]::new);
            builder.addFieldMapping(inner, synNames[synNames.length - 1], "this$0"); //we only need the last syn field, as we can't restore values
          } // else it is a static anonymous class, and we do not restore vals;
        });
      });
    });
    namedInstanceClasses.forEach((outer, inners) -> {
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
        builder.addClassMapping(inner, outer + "$" + innerSimpleName);
        innerNode.fields.stream().filter(fn -> (fn.access & ACC_SYNTHETIC) != 0).findFirst().ifPresent(fn -> builder.addFieldMapping(inner, fn.name, "this$0"));
      });
    });
  }
}
