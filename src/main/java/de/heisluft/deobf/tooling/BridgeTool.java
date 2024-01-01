package de.heisluft.deobf.tooling;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Paths;
import java.util.*;

import static org.objectweb.asm.Opcodes.*;

public class BridgeTool implements Util {
  private final Map<String, ClassNode> classes;

  static abstract class Test<T,U,M, X extends Throwable> {
    U apply(T t, double d, M m) throws X {return null;};
  }

  public static void main(String[] args) throws IOException {
    new Test<String, Integer, byte[], NegativeArraySizeException>() {
      @Override
      public Integer apply(String s, double d, byte[] date) {
        return null;
      }
    };
    new BridgeTool().detect();
  }

  BridgeTool() throws IOException {
    classes = parseClasses(Paths.get("fix.jar"));
  }

  public void detect() {
    classes.values().forEach(cn -> {
      if(cn.superName.equals("java/lang/Object") && cn.interfaces.isEmpty()) return;
      cn.methods.forEach(mn -> {
        if ((mn.access & ACC_SYNTHETIC) == 0) return;
        List<AbstractInsnNode> realInsns = new ArrayList<>();
        for (AbstractInsnNode ain : mn.instructions) if (ain.getOpcode() > -1) realInsns.add(ain);
        int insnOffset = 0;
        AbstractInsnNode ain = realInsns.get(insnOffset);
        if (ain.getOpcode() != ALOAD || ((VarInsnNode) ain).var != 0) return;
        Type[] argTypes = Type.getArgumentTypes(mn.desc);
        Type[] refinedTypes = new Type[argTypes.length];
        int specializedTypes = 0;
        // Look for the pattern: ALOAD0, (XLOAD n, (optional) checkcast refinedType) repeated numArgs times, INVOKEVIRTUAL, XRETURN.
        // Ints and floats must not be widened to doubles or longs.
        int argOffset = 1;
        for (int i = 0; i < argTypes.length; i++) {
          ain = realInsns.get(++insnOffset);
          if(!(ain instanceof VarInsnNode) || ain.getOpcode() >= ISTORE) return;
          VarInsnNode v = (VarInsnNode) ain;
          if(v.var != i + argOffset) return;
          if(v.getOpcode() != ALOAD) {
            refinedTypes[i] = argTypes[i];
            if(v.getOpcode() == DLOAD || v.getOpcode() == LLOAD) {
              if(v.getOpcode() == DLOAD && argTypes[i] != Type.DOUBLE_TYPE || v.getOpcode() == LLOAD && argTypes[i] != Type.LONG_TYPE) return;
              argOffset++;
            }
            continue;
          }
          if(realInsns.get(++insnOffset) instanceof VarInsnNode) continue;
          ain = realInsns.get(insnOffset);
          if(ain.getOpcode() != CHECKCAST) return;
          String desc = ((TypeInsnNode) ain).desc;
          refinedTypes[i] = Type.getObjectType(desc);
          specializedTypes++;
        }
        ain = realInsns.get(++insnOffset);
        if(!(ain instanceof MethodInsnNode)) return;
        MethodInsnNode min = (MethodInsnNode) ain;
        if(!min.owner.equals(cn.name) || !Arrays.equals(refinedTypes, Type.getArgumentTypes(min.desc))) return;
        Type returnType = Type.getReturnType(min.desc);
        boolean returnSpecialized = !Type.getReturnType(mn.desc).equals(returnType);
        String inheritedFrom = lookForInheritance(cn.superName, mn.desc, mn.name);
        for (String iface : cn.interfaces) {
          if(inheritedFrom != null) break;
          inheritedFrom = lookForInheritance(iface, mn.desc, mn.name);
        }
        if(inheritedFrom == null) return;
        System.out.println(cn.name + ": " + mn.name + mn.desc + " is bridge method, declared in " + inheritedFrom + ". specialized method: " + min.name + min.desc);
        System.out.println("Probable numbers of type parameters for method: " + (specializedTypes + (returnSpecialized ? 1 : 0)) + (returnSpecialized ? ", Return specialised" : ""));
        ClassNode theNode;
        if(!classes.containsKey(inheritedFrom)) try(InputStream is = BridgeTool.class.getResourceAsStream("/" + inheritedFrom + ".class")) {
          if (is == null) return;
          theNode = new ClassNode();
          new ClassReader(is).accept(theNode, ClassReader.SKIP_CODE);
          SignatureReader mSigReader = new SignatureReader(theNode.methods.stream().filter(m -> mn.desc.equals(m.desc) && mn.name.equals(m.name)).findFirst().get().signature);
          mSigReader.accept(new SignatureVisitor(ASM8) {

            @Override
            public SignatureVisitor visitParameterType() {
              System.out.println("partype");
              return this;
            }

            @Override
            public SignatureVisitor visitReturnType() {
              System.out.println("rettype");
              return this;
            }

            @Override
            public SignatureVisitor visitExceptionType() {
              System.out.println("extype");
              return this;
            }

            @Override
            public void visitBaseType(char descriptor) {
              System.out.println(descriptor);
            }

            @Override
            public void visitTypeVariable(String name) {
              System.out.println(name);
            }

            @Override
            public SignatureVisitor visitArrayType() {
              System.out.println("arrType");
              return this;
            }

            @Override
            public void visitClassType(String name) {
              System.out.println(name);
            }

            @Override
            public void visitInnerClassType(String name) {
              System.out.println(name);
            }

            @Override
            public void visitTypeArgument() {
              System.out.println("*");
            }

            @Override
            public SignatureVisitor visitTypeArgument(char wildcard) {
              System.out.println(wildcard);
              return this;
            }

            @Override
            public void visitEnd() {
              super.visitEnd();
            }
          });
          SignatureReader classSigReader = new SignatureReader(theNode.signature);
          classSigReader.accept(new SignatureWriter());
        } catch (IOException e) {e.printStackTrace();return;}
      });
    });
  }

  private String lookForInheritance(String type, String desc, String name) {
    if(classes.containsKey(type)) return lookForInheritance(classes.get(type), desc, name);
    else try {
      return lookForInheritance(Class.forName(type.replace('/', '.')),desc,name);
    } catch (ReflectiveOperationException e) {
      e.printStackTrace();
      return null;
    }
  }

  private String lookForInheritance(ClassNode type, String desc, String name) {
    if(type.methods.stream().anyMatch(mn -> name.equals(mn.name) && mn.desc.equals(desc) && Util.hasNone(mn.access, ACC_PRIVATE, ACC_STATIC, ACC_FINAL, ACC_NATIVE))) return type.name;
    String inh = lookForInheritance(type.superName, desc, name);
    if(inh != null) return inh;
    for(String iface : type.interfaces) {
      inh = lookForInheritance(iface, desc, name);
      if(inh != null) return inh;
    }
    return null;
  }

  private String lookForInheritance(Class<?> type, String desc, String name) throws ReflectiveOperationException {
    if(type == null || type.equals(Object.class)) return null;
    if (name.equals("<init>")) return Arrays.stream(type.getDeclaredConstructors()).map(Type::getConstructorDescriptor).anyMatch(desc::equals) ? Type.getInternalName(type) : null;
    if(Arrays.stream(type.getDeclaredMethods()).filter(m ->
        m.getName().equals(name)
            && !Modifier.isPrivate(m.getModifiers())
            && !Modifier.isStatic(m.getModifiers())
            && !Modifier.isNative(m.getModifiers())
            && !Modifier.isFinal(m.getModifiers())
    ).map(Type::getMethodDescriptor).anyMatch(desc::equals)) return Type.getInternalName(type);
    for (Class<?> anInterface : type.getInterfaces()) {
      String s = lookForInheritance(anInterface, desc, name);
      if(s != null) return s;
    }
    return lookForInheritance(type.getSuperclass(), desc, name);
  }
}
