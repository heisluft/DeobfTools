package de.heisluft.deobf.tooling.analysis;

import org.objectweb.asm.tree.ClassNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class InheritanceTree extends ClassAnalyzer {

  private final Map<String, Set<String>> directInheritors = new HashMap<>();
  private final Map<String, Set<String>> cache = new HashMap<>();

  @Override
  public void processClass(ClassNode classNode) {
    directInheritors.computeIfAbsent(classNode.superName, k -> new HashSet<>()).add(classNode.name);
    for(var iface: classNode.interfaces)
      directInheritors.computeIfAbsent(iface, k -> new HashSet<>()).add(classNode.name);
  }

  private void populateSubTypesRec(String className, Set<String> subTypes) {
    directInheritors.getOrDefault(className, new HashSet<>()).forEach(subType -> {
      populateSubTypesRec(subType, subTypes);
      subTypes.add(subType);
    });
  }

  public Set<String> getSubTypes(String className) {
    if(cache.containsKey(className)) return cache.get(className);
    var subTypes = new HashSet<String>();
    populateSubTypesRec(className, subTypes);
    cache.put(className, subTypes);
    return subTypes;
  }
}
