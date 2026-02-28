package de.heisluft.deobf.tooling.structure;

import de.heisluft.deobf.tooling.JDKClassProvider;
import de.heisluft.deobf.tooling.Util;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;

import static de.heisluft.deobf.tooling.structure.InheritanceStatus.inside;
import static de.heisluft.deobf.tooling.structure.InheritanceStatus.none;
import static de.heisluft.deobf.tooling.structure.InheritanceStatus.external;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

public final class InheritanceChecker {
  private final Map<String, ClassNode> classLookup;
  private final JDKClassProvider jdkLookup;

  public InheritanceChecker(Map<String, ClassNode> classLookup, JDKClassProvider jdkLookup) {
    this.classLookup = classLookup;
    this.jdkLookup = jdkLookup;
  }

  public InheritanceStatus getInheritance(ClassNode cls, String mdName, String mdDesc, int access) {
    if(!Util.hasNone(access, ACC_PRIVATE, ACC_STATIC)) return none();
    if(mdName.equals("<init>") || mdName.equals("<clinit>")) return none();
    return getInheritance(cls, cls.name, mdName, mdDesc);
  }

  private InheritanceStatus getInheritance(ClassNode cls, String rootName, String mdName, String mdDesc) {
    if(!rootName.equals(cls.name) && cls.methods.stream().anyMatch(md -> mdName.equals(md.name) && mdDesc.equals(md.desc) && Util.hasNone(md.access, ACC_PRIVATE, ACC_STATIC))) return classLookup.containsKey(cls.name) ? inside(cls.name) : external();
    InheritanceStatus result;
    ClassNode superCls = classLookup.getOrDefault(cls.superName, jdkLookup.getClassNode(cls.superName));
    if(superCls != null && (result = getInheritance(superCls, rootName, mdName, mdDesc)) != none()) return result;
    for(String iface : cls.interfaces) {
      ClassNode ifaceCls = classLookup.getOrDefault(iface, jdkLookup.getClassNode(iface));
      if(ifaceCls != null && (result = getInheritance(ifaceCls, rootName, mdName, mdDesc)) != none()) return result;
    }
    return none();
  }

}
