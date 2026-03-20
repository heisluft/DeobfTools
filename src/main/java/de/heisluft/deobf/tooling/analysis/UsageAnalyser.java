package de.heisluft.deobf.tooling.analysis;

import de.heisluft.deobf.tooling.ClassMember;
import de.heisluft.stream.BiStream;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class UsageAnalyser extends MethodAnalyzer {

  private final Map<String, Map<ClassMember, Map<String, Set<ClassMember>>>> usages = new HashMap<>();

  public Map<String, Set<ClassMember>> getUsages(String className, String memberName, String memberDesc) {
    return usages.getOrDefault(className, Map.of()).getOrDefault(new ClassMember(memberName, memberDesc), Map.of());
  }
  public Map<String, Set<ClassMember>> getUsages(String className, ClassMember member) {
    return usages.getOrDefault(className, Map.of()).getOrDefault(member, Map.of());
  }

  @Override
  public void processMethod(String className, MethodNode method, Set<String> allClassNames) {
    ClassMember repr = new ClassMember(method.name, method.desc);
    for(var insn : method.instructions) {
      switch(insn) {
        case MethodInsnNode min:
          if(allClassNames.contains(min.owner))
            usages.computeIfAbsent(min.owner, k -> new HashMap<>())
                .computeIfAbsent(new ClassMember(min.name, min.desc), k -> new HashMap<>())
                .computeIfAbsent(className, k -> new HashSet<>()).add(repr);
          break;
        case FieldInsnNode fin:
          if(allClassNames.contains(fin.owner))
            usages.computeIfAbsent(fin.owner, k -> new HashMap<>())
                .computeIfAbsent(new ClassMember(fin.name, fin.desc), k -> new HashMap<>())
                .computeIfAbsent(className, k -> new HashSet<>()).add(repr);
          break;
        default:
      }
    }
  }
}
