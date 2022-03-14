package de.heisluft.reveng.mappings;

import de.heisluft.function.Tuple2;
import de.heisluft.reveng.Util;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.lang.reflect.Executable;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

public class ExceptionMapper implements Util {

  private static final Map<String, ClassNode> classNodes = new HashMap<>();
  private static final List<String> exClasses = new ArrayList<>();
  private static final List<String> runtimeExesAndErrors = new ArrayList<>();

  public Map<String, List<String>> analyzeExceptions(Path inJar) throws IOException {
    classNodes.putAll(parseClasses(inJar));
    classNodes.values().stream().filter(this::isExceptionClass).map(cn -> cn.name).forEach(exClasses::add);
    classNodes.values().stream().filter(this::isRuntimeOrErrorClass).map(cn -> cn.name).forEach(runtimeExesAndErrors::add);
    classNodes.values().forEach(cn -> cn.methods.forEach(new ExInferringMV(cn.name)::accept));
    if(ExInferringMV.currentDirty.isEmpty()) return new HashMap<>();
    ExInferringMV.firstPass = false;
    while (!ExInferringMV.currentDirty.isEmpty()) {
      ExInferringMV.lastDirty = ExInferringMV.currentDirty;
      ExInferringMV.currentDirty = new HashSet<>();
      classNodes.values().forEach(cn -> cn.methods.forEach(new ExInferringMV(cn.name)::accept));
    }
    return ExInferringMV.addedExceptions;
  }


  private boolean isRuntimeOrErrorClass(ClassNode cn) {
    String sup = cn.superName;
    if(sup.equals("java/lang/Error")) return true;
    if(sup.equals("java/lang/RuntimeException")) return true;
    if(sup.equals("java/lang/Object")) return false;
    Class<?> c = ExInferringMV.resolveClass(ExInferringMV.desc(sup));
    if(c != null) {
      return Error.class.isAssignableFrom(c) || RuntimeException.class.isAssignableFrom(c);
    }
    return classNodes.containsKey(sup) && isRuntimeOrErrorClass(classNodes.get(sup));
  }

  private boolean isExceptionClass(ClassNode cn) {
    String sup = cn.superName;
    if(sup.equals("java/lang/Throwable")) return true;
    if(sup.equals("java/lang/Exception")) return true;
    if(sup.equals("java/lang/Object")) return false;
    if(sup.equals("java/lang/RuntimeException")) return false;
    Class<?> c = ExInferringMV.resolveClass(ExInferringMV.desc(sup));
    if(c != null) {
      return Exception.class.isAssignableFrom(c) && !RuntimeException.class.isAssignableFrom(c);
    }
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

    //mn -> Set<clsName + mdName + mdDesc>
    private static final Map<MethodNode, Set<String>> calledMethods = new HashMap<>();
    //clsName + mdName + mdDesc -> List<clsName>
    private static final Map<String, List<String>> addedExceptions = new HashMap<>();
    //clsName + mdName + mdDesc
    private static Set<String> lastDirty = new HashSet<>();
    //clsName + mdName + mdDesc
    private static Set<String> currentDirty = new HashSet<>();
    private static boolean firstPass = true;

    private static final Map<String, Class<?>> classCache = new HashMap<>();
    //clsName + mdName + mdDesc -> Method
    private static final Map<String, Executable> methodCache = new HashMap<>();

    /**
     *
     * @param className
     */
    public ExInferringMV(String className) {
      super(ASM7);
      this.className = className;
    }

    /**
     *
     * @param node
     */
    public void accept(MethodNode node) {
      if(addedExceptions.containsKey(className + node.name + node.desc)) return;
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
      getOrPut(tryBlocks, start, new Tuple2<>(end, new ArrayList<>()))._2.add(type);
      getOrPut(tryEnds, end, new ArrayList<>()).add(type);
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
      if(!(owner.equals(className) && name.equals(node.name) && descriptor.equals(node.desc))) getOrPut(calledMethods, node, new HashSet<>()).add(key);
      Type[] argTypes = Type.getArgumentTypes(descriptor);
      for(int i = 0; i < argTypes.length; i++) stack.pop();
      if(opcode != INVOKESTATIC) stack.pop();
      if(!descriptor.endsWith(")V")) stack.push(descriptor.substring(descriptor.lastIndexOf(')') + 1));
      if(addedExceptions.containsKey(key)) {
        addedExceptions.get(key).stream().filter(ex -> isSignificant(desc(ex), caughtExceptions)).forEach(thrownExTypes::add);
      } else {
        Class<?> ownerCls = resolveClass(desc(owner));
        if(ownerCls != null) {
         Executable ex = resolveMethod(ownerCls, name, descriptor, argTypes);
          if(ex != null) {
            Class<?>[] exTypes = ex.getExceptionTypes();
            for(Class<?> exType : exTypes) {
              if(isSignificant(Type.getDescriptor(exType), caughtExceptions)) thrownExTypes.add(Type.getInternalName(exType));
           }
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
        if(isSignificant(desc(exType), thrownExTypes.stream().filter(not(exType::equals)).collect(Collectors.toList())))
          effExTypes.add(exType);
      }
      thrownExTypes.clear();
      if(!effExTypes.isEmpty()) {
        String key = className + node.name + node.desc;
        addedExceptions.put(key, effExTypes);
        currentDirty.add(key);
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
      if(opcode >= ILOAD && opcode <= ALOAD) stack.push(locals.get(var));
      if(opcode >= ISTORE && opcode <= ASTORE) locals.put(var, stack.pop());
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
        Class<?> jExType = resolveClass(s);
        if(jExType == null) return false;
        Class<?> caughtExType = resolveClass(ex);
        return caughtExType != null && caughtExType.isAssignableFrom(jExType);
      })) return false;
      if(ExceptionMapper.exClasses.contains(exType)) return true;
      if(ExceptionMapper.runtimeExesAndErrors.contains(exType)) return false;
      Class<?> jExType = resolveClass(exType);
      if(jExType == null) return true;
      if(RuntimeException.class.isAssignableFrom(jExType)) return false;
      if(Error.class.isAssignableFrom(jExType)) return false;
      for(String cEx : caughtExceptions) {
        Class<?> jEx = resolveClass(cEx);
        if(jEx != null && jEx.isAssignableFrom(jExType)) return false;
      }
      return true;
    }

    /**
     *
     * @param desc
     * @return
     */
    static Class<?> resolveClass(String desc) {
      //Class.forName is weird, so we need to transform reference types
      String clsName = (desc.startsWith("L") && desc.endsWith(";") ? desc.substring(1, desc.length() - 1) : desc).replace('/', '.');
      if(classCache.containsKey(clsName)) return classCache.get(clsName);
      try {
        classCache.put(clsName, Class.forName(clsName));
        return classCache.get(clsName);
      } catch(ReflectiveOperationException e) {
        classCache.put(clsName, null);
        return null;
      }
    }

    /**
     *
     * @param c
     * @param name
     * @param desc
     * @param argTypes
     * @return
     */
    private static Executable resolveMethod(Class<?> c, String name, String desc, Type[] argTypes) {
      if(methodCache.containsKey(c + name + desc)) return methodCache.get(c + name + desc);
      Class<?>[] jArgTypes = Arrays.stream(argTypes).map(Type::getDescriptor).map(ExInferringMV::resolveClass).toArray(Class<?>[]::new);
      try {
        Executable ex;
        if("<init>".equals(name)) ex = c.getConstructor(jArgTypes);
        else ex = c.getMethod(name, jArgTypes);
        methodCache.put(c + name + desc, ex);
        return ex;
      } catch(ReflectiveOperationException e) {
        try {
          Executable ex;
          if("<init>".equals(name)) ex = c.getDeclaredConstructor(jArgTypes);
          else ex = c.getDeclaredMethod(name, jArgTypes);
          methodCache.put(c + name + desc, ex);
          return ex;
        } catch(ReflectiveOperationException e1) {
          methodCache.put(c + name + desc, null);
          return null;
        }
      }
    }

    static {
      classCache.put("D", double.class);
      classCache.put("F", float.class);
      classCache.put("I", int.class);
      classCache.put("S", short.class);
      classCache.put("B", byte.class);
      classCache.put("Z", boolean.class);
      classCache.put("C", char.class);
      classCache.put("J", long.class);
    }
  }
}
