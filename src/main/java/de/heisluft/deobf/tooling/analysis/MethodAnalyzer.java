package de.heisluft.deobf.tooling.analysis;

import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

public abstract class MethodAnalyzer {

  public void processMethod(String className, MethodNode method, Set<String> allClassNames) {
    processMethod(className, method);
  }

  public void processMethod(String className, MethodNode method) {}

}
