package de.heisluft.deobf.tooling;

import de.heisluft.function.Tuple2;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

public class ExceptionMapper implements Util {

  private static final Map<String, ClassNode> classNodes = new HashMap<>();
  private static final List<String> exClasses = new ArrayList<>();
  private static final List<String> runtimeExesAndErrors = new ArrayList<>();

  private final JDKClassProvider provider;

  public ExceptionMapper(JDKClassProvider provider) {
    this.provider = provider;
  }

  public Map<String, List<String>> analyzeExceptions(Path inJar) throws IOException {
    classNodes.putAll(parseClasses(inJar));
    classNodes.values().stream().filter(this::isExceptionClass).map(cn -> cn.name).forEach(exClasses::add);
    classNodes.values().stream().filter(this::isRuntimeOrErrorClass).map(cn -> cn.name).forEach(runtimeExesAndErrors::add);
    classNodes.values().forEach(cn -> cn.methods.forEach(new ExInferringMV(cn.name, provider)::accept));
    if(ExInferringMV.currentDirty.isEmpty()) return new HashMap<>();
    ExInferringMV.firstPass = false;
    while (!ExInferringMV.currentDirty.isEmpty()) {
      ExInferringMV.lastDirty = ExInferringMV.currentDirty;
      ExInferringMV.currentDirty = new HashSet<>();
      classNodes.values().forEach(cn -> cn.methods.forEach(new ExInferringMV(cn.name, provider)::accept));
    }
    return ExInferringMV.addedExceptions;
  }


  private boolean isRuntimeOrErrorClass(ClassNode cn) {
    String sup = cn.superName;
    if(sup.equals("java/lang/Error")) return true;
    if(sup.equals("java/lang/RuntimeException")) return true;
    if(sup.equals("java/lang/Object")) return false;
    ClassNode supC = provider.getClassNode(sup);
    if(supC != null) return isRuntimeOrErrorClass(supC);
    return classNodes.containsKey(sup) && isRuntimeOrErrorClass(classNodes.get(sup));
  }

  private boolean isExceptionClass(ClassNode cn) {
    String sup = cn.superName;
    if(sup.equals("java/lang/Throwable")) return true;
    if(sup.equals("java/lang/Exception")) return true;
    if(sup.equals("java/lang/Object")) return false;
    if(sup.equals("java/lang/RuntimeException")) return false;
    ClassNode supC = provider.getClassNode(sup);
    if(supC != null) return isExceptionClass(supC);
    return classNodes.containsKey(sup) && isExceptionClass(classNodes.get(sup));
  }

  public static class ExInferringMV extends MethodVisitor implements Util {

    private final String className;
    private MethodNode node;

    private final Stack<String> stack = new Stack<>();
    private final Map<Integer, String> locals = new HashMap<>(); // I wish this could just be an array, however, visitMaxs is called last...
    private final Map<Label, String> catchBlocks = new HashMap<>();

    private final Set<String> thrownExTypes = new HashSet<>();

    private final Map<Label, Tuple2<Label, List<String>>> tryBlocks = new HashMap<>();
    private final Map<Label, List<String>> tryEnds = new HashMap<>();
    private final List<String> caughtExceptions = new ArrayList<>();
    private final Stack<Label> awaited = new Stack<>();

    private final JDKClassProvider provider;

    //mn -> Set<clsName + mdName + mdDesc>
    private static final Map<MethodNode, Set<String>> calledMethods = new HashMap<>();
    //clsName + . + mdName + mdDesc -> List<clsName>
    private static final Map<String, List<String>> addedExceptions = new HashMap<>();
    //clsName + mdName + mdDesc
    private static Set<String> lastDirty = new HashSet<>();
    //clsName + mdName + mdDesc
    private static Set<String> currentDirty = new HashSet<>();
    private static boolean firstPass = true;

    /**
     * @param className
     * @param provider
     */
    public ExInferringMV(String className, JDKClassProvider provider) {
      super(ASM7);
      this.className = className;
      this.provider = provider;
    }

    /**
     *
     * @param node
     */
    public void accept(MethodNode node) {
      if(addedExceptions.containsKey(className + "." + node.name + node.desc)) return;
      if(!firstPass && (!calledMethods.containsKey(node) || calledMethods.get(node).stream().noneMatch(lastDirty::contains))) return;
      this.node = node;
      Type[] argTypes = Type.getArgumentTypes(node.desc);
      boolean isInstance = (node.access & ACC_STATIC) == 0;
      if(isInstance) locals.put(0, "L" + className + ";");
      for(int i = 0; i < argTypes.length; i++) {
        locals.put(i + (isInstance ? 1 : 0), argTypes[i].getDescriptor());
      }
      node.accept(this);
    }

    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
      tryBlocks.computeIfAbsent(start, k -> new Tuple2<>(end, new ArrayList<>()))._2.add(type);
      tryEnds.computeIfAbsent(end, k -> new ArrayList<>()).add(type);
      catchBlocks.put(handler, type);
      super.visitTryCatchBlock(start, end, handler, type);
    }

    public void visitInsn(int opcode) {
      if(opcode >= ICONST_M1 && opcode <= ICONST_5) stack.push("I");
      if(opcode == LCONST_0 || opcode == LCONST_1) stack.push("J");
      if(opcode == ACONST_NULL) stack.push("null");
      if(opcode >= FCONST_0 && opcode <= FCONST_2) stack.push("F");
      if(opcode == DCONST_0 || opcode == DCONST_1) stack.push("D");
      if(opcode == IALOAD || opcode == SALOAD || opcode == BALOAD || opcode == CALOAD) {
        stack.pop();
        stack.pop();
        stack.push("I");
      }
      if(opcode == LALOAD) {
        stack.pop();
        stack.pop();
        stack.push("J");
      }
      if(opcode == DALOAD) {
        stack.pop();
        stack.pop();
        stack.push("D");
      }
      if(opcode == FALOAD) {
        stack.pop();
        stack.pop();
        stack.push("F");
      }
      if(opcode == AALOAD) {
        stack.pop();
        stack.push(stack.pop().substring(1));
      }
      if(opcode >= IASTORE && opcode <= SASTORE) {
        stack.pop();
        stack.pop();
        stack.pop();
      }
      if(opcode == POP || opcode == POP2) {
        String popped = stack.pop();
        if(opcode == POP2 && !popped.equals("J") && !popped.equals("D")) stack.pop();
      }
      if(opcode == DUP || opcode == DUP2) {
        String popped = stack.pop();
        if(opcode == DUP2 && !popped.equals("J") && !popped.equals("D")) {
          String val2 = stack.peek();
          stack.push(popped);
          stack.push(val2);
        } else stack.push(popped);
        stack.push(popped);
      }
      if(opcode == DUP_X1 || opcode == DUP2_X1) {
        String popped = stack.pop();
        if(opcode == DUP2_X1 && !popped.equals("J") && !popped.equals("D")) {
          String popped2 = stack.pop();
          String bet = stack.pop();
          stack.push(popped2);
          stack.push(popped);
          stack.push(bet);
          stack.push(popped2);
          stack.push(popped);
        } else {
          String bet = stack.pop();
          stack.push(popped);
          stack.push(bet);
          stack.push(popped);
        }
      }
      if(opcode == DUP_X2 || opcode == DUP2_X2) {
        String popped = stack.pop();
        if(opcode == DUP2_X2 && !popped.equals("J") && !popped.equals("D")) {
          String popped2 = stack.pop();
          String popped3 = stack.pop();
          String popped4 = stack.pop();
          stack.push(popped2);
          stack.push(popped);
          stack.push(popped4);
          stack.push(popped3);
          stack.push(popped2);
          stack.push(popped);
        } else {
          String inter1 = stack.pop(), inter2 = stack.pop();
          stack.push(popped);
          stack.push(inter2);
          stack.push(inter1);
          stack.push(popped);
        }
      }
      if(opcode == SWAP) {
        String swp1 = stack.pop(), swp2 = stack.pop();
        stack.push(swp1);
        stack.push(swp2);
      }
      if(opcode == ARRAYLENGTH) {
        stack.pop();
        stack.push("I");
      }
      if(opcode == I2L || opcode == D2L || opcode == F2L) {
        stack.pop();
        stack.push("J");
      }
      if(opcode == I2F || opcode == L2F || opcode == D2F) {
        stack.pop();
        stack.push("F");
      }
      if(opcode == I2D || opcode == F2D || opcode == L2D) {
        stack.pop();
        stack.push("D");
      }
      if(opcode == L2I || opcode == D2I || opcode == F2I) {
        stack.pop();
        stack.push("I");
      }
      if(opcode == IMUL || opcode == IADD || opcode == IREM || opcode == IDIV || opcode == ISUB ||
          opcode == IAND || opcode == IOR || opcode == IXOR || opcode == ISHL || opcode == ISHR ||
          opcode == IUSHR) {
        stack.pop();
        stack.pop();
        stack.push("I");
      }
      if(opcode == LMUL || opcode == LADD || opcode == LREM || opcode == LDIV || opcode == LSUB ||
          opcode == LAND || opcode == LOR || opcode == LXOR || opcode == LSHL || opcode == LSHR ||
          opcode == LUSHR) {
        stack.pop();
        stack.pop();
        stack.push("J");
      }
      if(opcode == FMUL || opcode == FADD || opcode == FREM || opcode == FDIV || opcode == FSUB) {
        stack.pop();
        stack.pop();
        stack.push("F");
      }
      if(opcode == DMUL || opcode == DADD || opcode == DREM || opcode == DDIV || opcode == DSUB) {
        stack.pop();
        stack.pop();
        stack.push("D");
      }
      if(opcode >= LCMP && opcode <= DCMPG) {
        stack.pop();
        stack.pop();
        stack.push("I");
      }
      if(opcode == ATHROW) {
        String exType = stack.peek();
        if(exType != null && isSignificant(exType, caughtExceptions)) thrownExTypes.add(Type.getType(exType).getInternalName());
      }
      super.visitInsn(opcode);
    }

    public void visitIntInsn(int opcode, int operand) {
      if(opcode == BIPUSH || opcode == SIPUSH) stack.push("I");
      if(opcode == NEWARRAY) {
        stack.pop();
        switch(operand) {
          case T_BOOLEAN:
            stack.push("[Z");
            break;
          case T_CHAR:
            stack.push("[C");
            break;
          case T_BYTE:
            stack.push("[B");
            break;
          case T_SHORT:
            stack.push("[S");
            break;
          case T_INT:
            stack.push("[I");
            break;
          case T_LONG:
            stack.push("[J");
            break;
          case T_DOUBLE:
            stack.push("[D");
            break;
          case T_FLOAT:
            stack.push("[F");
            break;
        }
      }
      super.visitIntInsn(opcode, operand);
    }

    public void visitTypeInsn(int opcode, String type) {
      if(opcode == CHECKCAST) {
        stack.pop();
        stack.push(desc(type));
      }
      if(opcode == ANEWARRAY) {
        stack.pop();
        stack.push("[" + desc(type));
      }
      if(opcode == INSTANCEOF) {
        stack.pop();
        stack.push("I");
      }
      if(opcode == NEW) stack.push(desc(type));
      super.visitTypeInsn(opcode, type);
    }

    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
      if(opcode == GETSTATIC) stack.push(descriptor);
      if(opcode == PUTSTATIC) stack.pop();
      if(opcode == GETFIELD) {
        stack.pop();
        stack.push(descriptor);
      }
      if(opcode == PUTFIELD) {
        stack.pop();
        stack.pop();
      }
      super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
      String key = owner + name + descriptor;
      if(!(owner.equals(className) && name.equals(node.name) && descriptor.equals(node.desc))) calledMethods.computeIfAbsent(node, k -> new HashSet<>()).add(key);
      Type[] argTypes = Type.getArgumentTypes(descriptor);
      for(int i = 0; i < argTypes.length; i++) stack.pop();
      if(opcode != INVOKESTATIC) stack.pop();
      if(!descriptor.endsWith(")V")) stack.push(descriptor.substring(descriptor.lastIndexOf(')') + 1));
      if(addedExceptions.containsKey(owner + "." + name + descriptor)) {
        addedExceptions.get(owner + "." + name + descriptor).stream().filter(ex -> isSignificant(desc(ex), caughtExceptions)).forEach(thrownExTypes::add);
      } else {
        ClassNode cn = provider.getClassNode(owner);
        if(cn != null) {
          Optional<MethodNode> op = cn.methods.stream()
              .filter(mn -> mn.name.equals(name) && mn.desc.equals(descriptor)).findFirst();
          if(op.isPresent()) {
            List<String> exTypes = op.get().exceptions;
            for(String exType : exTypes)
              if(isSignificant("L" + exType + ";", caughtExceptions)) thrownExTypes.add(exType);
          }
        }
      }
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    public void visitEnd() {
      stack.clear();
      locals.clear();
      List<String> effExTypes = new ArrayList<>();
      for(String exType : thrownExTypes) {
        if(isSignificant(desc(exType), thrownExTypes.stream().filter(k -> !exType.equals(k)).collect(Collectors.toList())))
          effExTypes.add(exType);
      }
      thrownExTypes.clear();
      if(!effExTypes.isEmpty()) {
        addedExceptions.put(className + "." + node.name + node.desc, effExTypes);
        currentDirty.add(className + node.name + node.desc);
      }
      super.visitEnd();
    }

    public void visitJumpInsn(int opcode, Label label) {
      if(GOTO != opcode && JSR != opcode) {
        stack.pop();
        if(IFNULL != opcode && IFNONNULL != opcode && (opcode < IFEQ || opcode > IFLE)) stack.pop();
      }
      super.visitJumpInsn(opcode, label);
    }

    public void visitLdcInsn(Object value) {
      if(value instanceof Integer) {
        stack.push("I");
      } else if(value instanceof Float) {
        stack.push("F");
      } else if(value instanceof Long) {
        stack.push("J");
      } else if(value instanceof Double) {
        stack.push("D");
      } else if(value instanceof String) {
        stack.push("Ljava/lang/String;");
      } else if(value instanceof Type) {
        stack.push(value.toString());
      } else {
        throw new RuntimeException("This should not happen");
      }

      super.visitLdcInsn(value);
    }

    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
      for(int i = 0; i < numDimensions; i++) stack.pop();
      stack.push(descriptor);
      super.visitMultiANewArrayInsn(descriptor, numDimensions);
    }

    public void visitLabel(Label label) {
      if(!awaited.empty() && label.equals(awaited.peek())) {
        caughtExceptions.removeAll(tryEnds.get(awaited.pop()));
      }
      if(tryBlocks.containsKey(label)) {
        Tuple2<Label, List<String>> endInfo = tryBlocks.get(label);
        awaited.push(endInfo._1);
        caughtExceptions.addAll(endInfo._2);
      }
      if(catchBlocks.containsKey(label)) stack.push(desc(catchBlocks.get(label)));
      super.visitLabel(label);
    }

    public void visitVarInsn(int opcode, int var) {
      if(opcode == ILOAD) stack.push("I");
      else if(opcode == LLOAD) stack.push("J");
      else if(opcode == FLOAD) stack.push("F");
      else if(opcode == DLOAD) stack.push("D");
      else if(opcode == ALOAD) stack.push(locals.get(var));
      else {
        String pop = stack.pop();
        if (opcode == ISTORE) stack.push("I");
        else if (opcode == LSTORE) stack.push("J");
        else if (opcode == FSTORE) stack.push("F");
        else if (opcode == DSTORE) stack.push("D");
        else if (opcode == ASTORE) locals.put(var, pop);
      }
      super.visitVarInsn(opcode, var);
    }

    /**
     *
     * @param type
     * @return
     */
    static String desc(String type) {
      if(type == null) return "null";
      if(type.startsWith("[") || type.endsWith(";")) return type;
      switch(type) {
        case "Z":
        case "C":
        case "J":
        case "I":
        case "S":
        case "B":
        case "D":
        case "F":
          return type;
      }
      return "L" + type + ";";
    }

    /**
     *
     * @param exDesc
     * @param caughtExceptions
     * @return
     */
    private boolean isSignificant(String exDesc, List<String> caughtExceptions) {
      if(exDesc.equals("null")) return false;
      String exType = exDesc.substring(1, exDesc.length() - 1);
      if(caughtExceptions.stream().filter(Objects::nonNull).anyMatch(ex-> {
        if(exType.equals(ex)) return false;
        String s = exType;
        while (classNodes.containsKey(s)) {
          s = classNodes.get(s).superName;
          if(s.equals(ex)) return true;
        }
        ClassNode exNode = provider.getClassNode(ex);
        if(exNode == null) return false;
        ClassNode caughtExNode = provider.getClassNode(s);
        while(caughtExNode != null) {
          if(caughtExNode.name.equals(exNode.name)) return true;
          caughtExNode = provider.getClassNode(caughtExNode.superName);
        }
        return false;
      })) return false;
      if(ExceptionMapper.exClasses.contains(exType)) return true;
      if(ExceptionMapper.runtimeExesAndErrors.contains(exType)) return false;
      ClassNode nExType = provider.getClassNode(exType);
      ClassNode errNode = provider.getClassNode("java/lang/Error");
      ClassNode rExNode = provider.getClassNode("java/lang/RuntimeException");
      ClassNode curr = nExType;
      while(curr != null) {
        if(errNode.name.equals(curr.name) || rExNode.name.equals(curr.name) || caughtExceptions.contains(curr.name)) return false;
        curr = provider.getClassNode(curr.superName);
      }
      return true;
    }
  }
}
