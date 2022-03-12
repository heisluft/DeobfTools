package de.heisluft.reveng;

import de.heisluft.function.Tuple2;
import de.heisluft.reveng.debug.Stringifier;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.lang.reflect.Executable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

public class Exceptions implements Util {

  private static final Map<String, ClassNode> classNodes = new HashMap<>();
  private static final List<String> exClasses = new ArrayList<>();
  private static final List<String> runtimeExesAndErrors = new ArrayList<>();

  public static void main(String[] args) throws IOException {
    new Exceptions().analyzeExceptions(Paths.get("c0.0.23a_01-deobf.jar"));
  }

  private void analyzeExceptions(Path inJar) throws IOException {
    classNodes.putAll(parseClasses(inJar));
    Map<String, String> supers = new HashMap<>();
    classNodes.values().stream().filter(this::isExceptionClass).map(cn -> cn.name).forEach(exClasses::add);
    classNodes.values().stream().filter(this::isRuntimeOrErrorClass).map(cn -> cn.name).forEach(runtimeExesAndErrors::add);
    System.out.println(exClasses);
    classNodes.values().forEach(cn -> cn.methods.forEach(new ExInferringMV(cn.name)::accept));
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

    private static final boolean DEBUG = false;

    private final Stack<String> stack = new Stack<>();
    private final Map<Integer, String> locals = new HashMap<>(); // I wish this could just be an array, however, visitMaxs is called last...
    private final Map<Label, String> catchBlocks = new HashMap<>();
    private Label currentLabel = null;

    private final Set<String> thrownExTypes = new HashSet<>();

    private final Map<Label, Tuple2<Label, List<String>>> tryBlocks = new HashMap<>();
    private final Map<Label, List<String>> tryEnds = new HashMap<>();
    private final List<String> caughtExceptions = new ArrayList<>();
    private final Stack<Label> awaited = new Stack<>();

    private static final Map<MethodNode, Map<String, Set<String>>> calledMethods = new HashMap<>();
    private static final Map<String, Class<?>> classCache = new HashMap<>();
    //clsName + '#' + mdName + mdDesc -> Method
    private static final Map<String, Executable> methodCache = new HashMap<>();


    public ExInferringMV(String className) {
      super(ASM7);
      this.className = className;
    }

    public void accept(MethodNode node) {
      this.node = node;
      System.out.println("Analyzing " + className + "#" + node.name + node.desc);
      Type[] argTypes = Type.getArgumentTypes(node.desc);
      boolean isInstance = (node.access & ACC_STATIC) == 0;
      if(isInstance) locals.put(0, "L" + className + ";");
      for(int i = 0; i < argTypes.length; i++) {
        locals.put(i + (isInstance ? 1 : 0), argTypes[i].getDescriptor());
      }
      node.accept(this);
    }

    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
      if(DEBUG) System.out.println(
          "TryCatch(start: " + start + ", end: " + end + ", handler: " + handler + ", exType: " +
              type);
      getOrPut(tryBlocks, start, new Tuple2<>(end, new ArrayList<>()))._2.add(type);
      getOrPut(tryEnds, end, new ArrayList<>()).add(type);
      catchBlocks.put(handler, type);
      super.visitTryCatchBlock(start, end, handler, type);
    }

    public void visitInsn(int opcode) {
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(Stringifier.stringifyInsnOp(opcode));
      }
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
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(stackToString() + ", " + localsToString());
      }
      super.visitInsn(opcode);
    }

    public void visitIntInsn(int opcode, int operand) {
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(Stringifier.stringifyInsnOp(opcode) + " " + operand);
      }
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
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(stackToString() + ", " + localsToString());
      }
      super.visitIntInsn(opcode, operand);
    }

    public void visitTypeInsn(int opcode, String type) {
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(Stringifier.stringifyInsnOp(opcode) + " " + type);
      }
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
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(stackToString() + ", " + localsToString());
      }
      super.visitTypeInsn(opcode, type);
    }

    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(
            Stringifier.stringifyInsnOp(opcode) + " " + owner + "#" + name + " " + descriptor);
      }
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
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(stackToString() + ", " + localsToString());
      }
      super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
      if(!(owner.equals(className) && name.equals(node.name) && descriptor.equals(node.desc))) getOrPut(getOrPut(calledMethods, node, new HashMap<>()), owner, new HashSet<>()).add(name + descriptor);
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(Stringifier.stringifyInsnOp(opcode) + " " + owner + "#" + name + descriptor);
      }
      Type[] argTypes = Type.getArgumentTypes(descriptor);
      for(int i = 0; i < argTypes.length; i++) stack.pop();
      if(opcode != INVOKESTATIC) stack.pop();
      if(!descriptor.endsWith(")V")) stack.push(descriptor.substring(descriptor.lastIndexOf(')') + 1));
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
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(stackToString() + ", " + localsToString());
      }
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    public void visitEnd() {
      if(DEBUG && currentLabel != null) System.out.println("}");
      currentLabel = null;
      stack.clear();
      locals.clear();
      List<String> effExTypes = new ArrayList<>();
      for(String exType : thrownExTypes) {
        if(isSignificant(exType, thrownExTypes.stream().filter(not(exType::equals)).collect(Collectors.toList())))
          effExTypes.add(exType);
      }
      thrownExTypes.clear();
      if(!effExTypes.isEmpty()) System.out.println("throws " + effExTypes);
      System.out.println();
      super.visitEnd();
    }

    public void visitJumpInsn(int opcode, Label label) {
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(Stringifier.stringifyInsnOp(opcode) + " " + label);
      }
      if(GOTO != opcode && JSR != opcode) {
        stack.pop();
        if(IFNULL != opcode && IFNONNULL != opcode && (opcode < IFEQ || opcode > IFLE)) stack.pop();
      }
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(stackToString() + ", " + localsToString());
      }
      super.visitJumpInsn(opcode, label);
    }

    public void visitLdcInsn(Object value) {
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println("LDC " + value + " (" + Type.getDescriptor(value.getClass()) + ")");
      }
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

      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(stackToString() + ", " + localsToString());
      }
      super.visitLdcInsn(value);
    }

    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println("MULTIANEWARRAY " + descriptor + ", dim " + numDimensions);
      }
      for(int i = 0; i < numDimensions; i++) stack.pop();
      stack.push(descriptor);
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(stackToString() + ", " + localsToString());
      }
      super.visitMultiANewArrayInsn(descriptor, numDimensions);
    }

    public void visitLabel(Label label) {
      if(DEBUG && currentLabel != null) System.out.println("}");
      if(!awaited.empty() && label.equals(awaited.peek())) {
        caughtExceptions.removeAll(tryEnds.get(awaited.pop()));
      }
      if(tryBlocks.containsKey(label)) {
        Tuple2<Label, List<String>> endInfo = tryBlocks.get(label);
        awaited.push(endInfo._1);
        caughtExceptions.addAll(endInfo._2);
      }
      if(DEBUG) System.out.println(label + ": {");
      currentLabel = label;
      if(catchBlocks.containsKey(label)) stack.push(desc(catchBlocks.get(label)));
      super.visitLabel(label);
    }

    public void visitVarInsn(int opcode, int var) {
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(Stringifier.stringifyInsnOp(opcode) + " " + var);
      }
      if(opcode >= ILOAD && opcode <= ALOAD) stack.push(locals.get(var));
      if(opcode >= ISTORE && opcode <= ASTORE) locals.put(var, stack.pop());
      if(DEBUG) {
        if(currentLabel != null) System.out.print("  ");
        System.out.println(stackToString() + ", " + localsToString());
      }
      super.visitVarInsn(opcode, var);
    }

    static String desc(String type) {
      if(type == null) return "null";
      if(type.startsWith("[")) return type;
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

    private boolean isSignificant(String exType, List<String> caughtExceptions) {
      if(exType.equals("null")) return false;
      if(caughtExceptions.stream().map(ExInferringMV::desc).anyMatch(exType::equals)) return false;
      if(Exceptions.exClasses.contains(exType)) return true;
      if(Exceptions.runtimeExesAndErrors.contains(exType)) return false;
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

    private String stackToString() {
      String s = stack.toString();
      return "stack: { " + s.substring(1, s.length() - 1) + " }";
    }

    private String localsToString() {
      return "locals: " +
          Tuple2.streamMap(locals).sorted(Comparator.comparing(Tuple2::_1)).map(Tuple2::_2).collect(Collectors.joining(", ", "{ ", " }"));
    }

    static Class<?> resolveClass(String desc) {
      //Class.forName is weird, so we need to transform reference types
      String clsName = (desc.startsWith("L") && desc.endsWith(";") ? desc.substring(1, desc.length() - 1) : desc).replace('/', '.');
      if(classCache.containsKey(clsName)) return classCache.get(clsName);
      try {
        classCache.put(clsName, Class.forName(clsName));
        return classCache.get(clsName);
      } catch(ReflectiveOperationException e) {
        return null;
      }
    }

    private static Executable resolveMethod(Class<?> c, String name, String desc, Type[] argTypes) {
      if(methodCache.containsKey(c + "#" + name + desc)) return methodCache.get(c + "#" + name + desc);
      Class<?>[] jArgTypes = Arrays.stream(argTypes).map(Type::getDescriptor).map(ExInferringMV::resolveClass).toArray(Class<?>[]::new);
      try {
        Executable ex;
        if("<init>".equals(name)) ex = c.getConstructor(jArgTypes);
        else ex = c.getMethod(name, jArgTypes);
        methodCache.put(c + "#" + name + desc, ex);
        return ex;
      } catch(ReflectiveOperationException e) {
        try {
          Executable ex;
          if("<init>".equals(name)) ex = c.getDeclaredConstructor(jArgTypes);
          else ex = c.getDeclaredMethod(name, jArgTypes);
          methodCache.put(c + "#" + name + desc, ex);
          return ex;
        } catch(ReflectiveOperationException e1) {
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
