package de.heisluft.deobf.tooling.analysis;

import de.heisluft.deobf.tooling.ClassMember;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.Map;

public class MethodCache extends MethodAnalyzer {
  private final Map<String, Map<ClassMember, MethodNode>> cache = new HashMap<>();

  @Override
  public void processMethod(String className, MethodNode method) {
    cache.computeIfAbsent(className, k -> new HashMap<>()).put(new ClassMember(method.name, method.desc), method);
  }

  public MethodNode lookup(String className, String name, String desc) {
    return cache.getOrDefault(className, new HashMap<>()).get(new ClassMember(name, desc));
  }
}
