package de.heisluft.reveng.nests;

import de.heisluft.function.Tuple2;
import de.heisluft.reveng.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

// INNER CLASS BONANZA TAKEAWAYS:
// -> A class references all classes it is enclosed in, both directly and transitive.
// -> A class references all classes directly enclosed in itself
// -> The InnerClassNode Information of the relationship is the same within the inner and outer class.
//
// for inner classes: outerClass = outerMethod = outerMethodDesc = null
// InnerClassNode:
// name: fully qualified inner class name
// innerName: name of inner class only => how to generate?
// outerName: fully qualified outer class name
// for anonymous classes: outerClass = fully qualified className of outer class
// InnerClassNode:
// outerName = innerName = null
// name = fully qualified name of anonymous class (generated) => does not need remapping
// for anonymous classes declared within methods: outerMethod and outerMethodDesc are set
public class Analyzer implements Util {

  private final Map<String, Set<String>> tree;
  Set<String> innerClassCandidates = new HashSet<>();

  private Analyzer(Path jarPath) throws IOException {
    Map<String, ClassNode> classNodes =parseClasses(jarPath, Collections.singletonList("/de/heisluft/RootClass"));
    tree = new HashMap<>(classNodes.size());
    classNodes.values().stream().filter(cn ->!cn.fields.isEmpty() && cn.fields.stream().map(fieldNode -> fieldNode.access).allMatch(access -> (access & Opcodes.ACC_SYNTHETIC) != 0)).map(cn -> cn.name).forEach(innerClassCandidates::add);
    Map<String, Set<Tuple2<String, MethodNode>>> references = new HashMap<>();
    classNodes.values().forEach(cn -> {
      System.out.println(cn.name + ": " + AnonData.applyFrom(cn));
      cn.innerClasses.stream().map(AnonData::innerToString).forEach(System.out::println);
      System.out.println();
      cn.fields.stream()
          .map(fieldNode -> fieldNode.desc)
          .map(Type::getType)
          .map(Type::getClassName)
          .filter(classNodes::containsKey)
          .forEach(type -> getOrPut(tree, type, new HashSet<>()).add(cn.name));
      cn.methods.forEach(mn -> {
            mn.instructions.forEach(in -> {
              if (in instanceof TypeInsnNode) {
                String desc = ((TypeInsnNode) in).desc;
                if (innerClassCandidates.contains(desc)) {
                  getOrPut(references, desc, new HashSet<>()).add(new Tuple2<>(cn.name, mn));
                }
              }
              if (in instanceof MultiANewArrayInsnNode) {
                String desc = ((MultiANewArrayInsnNode) in).desc.replace("[", "").replace(";", "").replace("L", "");
                if (innerClassCandidates.contains(desc)) {
                  getOrPut(references, desc, new HashSet<>()).add(new Tuple2<>(cn.name, mn));
                }
              }
              if (in instanceof FieldInsnNode) {
                String desc = ((FieldInsnNode) in).desc.replace("[", "").replace(";", "").replace("L", "");
                if (innerClassCandidates.contains(desc)) {
                  getOrPut(references, desc, new HashSet<>()).add(new Tuple2<>(cn.name, mn));
                }
              }
              if (in instanceof MethodInsnNode) {
                String desc = ((MethodInsnNode) in).owner;
                if (innerClassCandidates.contains(desc)) {
                  getOrPut(references, desc, new HashSet<>()).add(new Tuple2<>(cn.name, mn));
                }
              }
            });
          }
      );
    });
    references.entrySet().stream().map(Tuple2::new)
        .filter(t -> !t.consume1(s -> {}).isEmpty()).map(tuple -> tuple.map2(set-> set.stream().map(t -> t._1 + '#' + t._2.name + t._2.desc).collect(Collectors.toList()))).map(t -> t._1 + " is referenced from " + t._2).forEach(System.out::println);
    
  }

  public static void main(String[] args) throws IOException {
    if(args.length == 0) {
      System.err.println("No path provided");
      System.exit(-1);
    }
    System.out.println("Class Heuristics for Outer-Inner Relationships - Project CHOIR\n");
    new Analyzer(Paths.get(args[0]));
  }
}
