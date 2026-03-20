package de.heisluft.deobf.tooling.analysis;

import de.heisluft.deobf.tooling.ClassMember;
import de.heisluft.deobf.tooling.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class InheritableAnalyzer extends MethodAnalyzer {
  private final Map<String, Set<ClassMember>> inheritableMethods = new HashMap<>();

  @Override
  public void processMethod(String className, MethodNode method) {
    if(!Util.hasNone(method.access, Opcodes.ACC_PRIVATE)) return;
    inheritableMethods.computeIfAbsent(className, s -> new HashSet<>()).add(new ClassMember(method.name, method.desc));
  }

  public Set<ClassMember> getInheritableMethods(String className) {
    return inheritableMethods.getOrDefault(className, new HashSet<>());
  }
}
