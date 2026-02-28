package de.heisluft.deobf.tooling;

import de.heisluft.deobf.tooling.debug.Stringifier;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SigAdder implements Util {

  private void doStuff() throws IOException {
    Path path = Paths.get("sigadder.jar");
    Files.copy(Path.of("b1.3-1731-server.jar"), path, StandardCopyOption.REPLACE_EXISTING);
    Map<String, ClassNode> classes = parseClasses(path);
    Map<String, Set<String>> initialCandidates = new HashMap<>();
    Map<String, Set<String>> modifierCandidates = new HashMap<>();
    for(ClassNode value : classes.values()) {
      for(MethodNode method : value.methods) {
        if(method.desc.endsWith(")Ljava/lang/Object;")) initialCandidates.computeIfAbsent(value.name, k -> new HashSet<>()).add(method.name + method.desc);
      }
      if(!initialCandidates.containsKey(value.name)) continue;
      for(MethodNode method : value.methods) {
        if(method.desc.equals("(Ljava/lang/Object;)Z") && method.name.equals("equals")) continue;
        if(method.desc.contains("Ljava/lang/Object;") && !initialCandidates.get(value.name).contains(method.name + method.desc)) modifierCandidates.computeIfAbsent(value.name, k -> new HashSet<>()).add(method.name + method.desc);
      }
    }
    System.out.println("-------- Initial candidates: --------");
    initialCandidates.forEach((k, v) -> System.out.println(k + " enabled by: " + v + ", consistency checks: " + modifierCandidates.get(k)));
    System.out.println("-------------------------------------");
    for(ClassNode classNode : classes.values()) {
      for(String candidate : initialCandidates.keySet()) {
        //if(classNode.name.equals(candidate)) continue;
        for(MethodNode method : classNode.methods) {
          for(AbstractInsnNode insn : method.instructions) {
            if(!(insn instanceof MethodInsnNode min) || !candidate.equals(min.owner)) continue;
            if(!initialCandidates.get(candidate).contains(min.name + min.desc)) continue;
            System.out.println(min.owner + "#" + min.name + min.desc + " used in " + classNode.name + "#" + method.name + method.desc);
            AbstractInsnNode next = insn.getNext();
            while(next != null && next.getOpcode() == -1) next = next.getNext();
            if(next != null) {
              System.out.println("following instruction: " + Stringifier.stringifyInstruction(next));
            };
            System.out.println();
          }
        }
      }
    }
  }

  public static void main(String[] args) throws IOException {
    new SigAdder().doStuff();
  }
}
