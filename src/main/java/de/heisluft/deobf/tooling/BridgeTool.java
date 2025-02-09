package de.heisluft.deobf.tooling;

import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiFunction;

import static org.objectweb.asm.Opcodes.*;

public class BridgeTool implements Util {
  private final Map<String, ClassNode> classes;
  private final JDKClassProvider classProvider = new JDKClassProvider();

  public static void main(String[] args) throws IOException {
    new BridgeTool().detect();
  }

  BridgeTool() throws IOException {
    classes = parseClasses(Paths.get("remap-tests/jars/mc/client/alpha/a1.1.2_01.jar"));
  }

  public void detect() {
    new BiFunction<Class<? extends Exception>,Class<? super String>, byte[]>() {

      @Override
      public byte[] apply(Class<? extends Exception> aClass, Class<? super String> aClass2) {
        return new byte[0];
      }
    };
    classes.values().forEach(cn -> {
      if(cn.superName.equals("java/lang/Object") && cn.interfaces.isEmpty()) return;
      cn.methods.forEach(mn -> {
        if((mn.access & ACC_SYNTHETIC) == 0) return;
        List<AbstractInsnNode> realInsns = new ArrayList<>();
        for(AbstractInsnNode ain : mn.instructions) if(ain.getOpcode() > -1) realInsns.add(ain);
        int insnOffset = 0;
        AbstractInsnNode ain = realInsns.get(insnOffset);
        if(ain.getOpcode() != ALOAD || ((VarInsnNode) ain).var != 0) return;
        Type[] argTypes = Type.getArgumentTypes(mn.desc);
        Type[] refinedTypes = new Type[argTypes.length];
        int specializedTypes = 0;
        // Look for the pattern: ALOAD0, (XLOAD n, (optional) checkcast refinedType) repeated
        // numArgs times, INVOKEVIRTUAL, XRETURN.
        // Ints and floats must not be widened to doubles or longs.
        int argOffset = 1;
        for(int i = 0; i < argTypes.length; i++) {
          ain = realInsns.get(++insnOffset);
          if(!(ain instanceof VarInsnNode v) || ain.getOpcode() >= ISTORE) return;
          if(v.var != i + argOffset) return;
          if(v.getOpcode() != ALOAD) {
            refinedTypes[i] = argTypes[i];
            if(v.getOpcode() == DLOAD || v.getOpcode() == LLOAD) {
              if(v.getOpcode() == DLOAD && argTypes[i] != Type.DOUBLE_TYPE ||
                  v.getOpcode() == LLOAD && argTypes[i] != Type.LONG_TYPE) return;
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
        if(!(ain instanceof MethodInsnNode min)) return;
        if(!min.owner.equals(cn.name) || !Arrays.equals(refinedTypes, Type.getArgumentTypes(min.desc))) return;
        Type returnType = Type.getReturnType(min.desc);
        boolean returnSpecialized = !Type.getReturnType(mn.desc).equals(returnType);
        ClassNode inheritedFrom = lookForInheritance(cn.superName, mn.desc, mn.name);
        for(String iface : cn.interfaces) {
          if(inheritedFrom != null) break;
          inheritedFrom = lookForInheritance(iface, mn.desc, mn.name);
        }
        if(inheritedFrom == null) return;
        System.out.println(cn.name + ": " + mn.name + mn.desc + " is bridge method, declared in " + inheritedFrom.name + ". specialized method: " + min.name + min.desc);
        System.out.println("Probable numbers of type parameters for method: " + (specializedTypes + (returnSpecialized ? 1 : 0)) + (returnSpecialized ? ", Return specialised" : ""));
        MethodNode decl = inheritedFrom.methods.stream().filter(m -> mn.desc.equals(m.desc) && mn.name.equals(m.name)).findFirst().get();
        if(decl.signature == null) return;
        SignatureReader mSigReader = new SignatureReader(decl.signature);
        mSigReader.accept(new SignatureVisitor(ASM9) {});
        List<String> typeVars = new ArrayList<>();
        SignatureReader classSigReader = new SignatureReader(inheritedFrom.signature);
        classSigReader.accept(new SignatureVisitor(ASM9) {
          @Override
          public void visitFormalTypeParameter(String name) {
            typeVars.add(name);
          }
        });
        System.out.println(typeVars);
        StringBuffer sb = new StringBuffer("L" + cn.superName + ";");
        for(String iface : cn.interfaces) {
          sb.append("L").append(iface);
          if(inheritedFrom.name.equals(iface)) {
            List<String> encountered = new ArrayList<>();
            for(int i = 0; i < argTypes.length; i++) {
              if(!argTypes[i].equals(refinedTypes[i]));
            }
            sb.append("<").append(">");
          }
          sb.append(";");
        }
        System.out.println(sb);
      });
    });
  }

  private ClassNode lookForInheritance(String type, String desc, String name) {
    if(classes.containsKey(type)) return lookForInheritance(classes.get(type), desc, name);
    else return lookForInheritance(classProvider.getClassNode(type), desc, name);
  }

  private ClassNode lookForInheritance(ClassNode type, String desc, String name) {
    if(type == null) return null;
    if(type.methods.stream().anyMatch(mn -> name.equals(mn.name) && mn.desc.equals(desc) &&
        Util.hasNone(mn.access, ACC_PRIVATE, ACC_STATIC, ACC_FINAL, ACC_NATIVE))) return type;
    ClassNode inh = lookForInheritance(type.superName, desc, name);
    if(inh != null) return inh;
    for(String iface : type.interfaces) {
      inh = lookForInheritance(iface, desc, name);
      if(inh != null) return inh;
    }
    return null;
  }
}
