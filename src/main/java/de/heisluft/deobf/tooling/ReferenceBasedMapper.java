package de.heisluft.deobf.tooling;

import de.heisluft.cli.simplecli.ArgDefinition;
import de.heisluft.cli.simplecli.OptionDefinition;
import de.heisluft.cli.simplecli.OptionParseResult;
import de.heisluft.cli.simplecli.OptionParser;
import de.heisluft.cli.simplecli.Command;
import de.heisluft.deobf.mappings.Mappings;
import de.heisluft.deobf.mappings.MappingsBuilder;
import de.heisluft.deobf.mappings.MappingsHandlers;
import de.heisluft.deobf.tooling.analysis.InheritableAnalyzer;
import de.heisluft.deobf.tooling.analysis.InheritanceChecker;
import de.heisluft.deobf.tooling.analysis.InheritanceStatus;
import de.heisluft.deobf.tooling.analysis.InheritanceStatus.Internal;
import de.heisluft.deobf.tooling.analysis.InheritanceTree;
import de.heisluft.deobf.tooling.analysis.MethodCache;
import de.heisluft.deobf.tooling.analysis.UsageAnalyser;
import de.heisluft.stream.BiStream;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static de.heisluft.deobf.tooling.analysis.InheritanceStatus.external;
import static org.objectweb.asm.ClassReader.SKIP_DEBUG;

public class ReferenceBasedMapper implements Util {

  public static final class Likeness {
    public static final int NONE = 0, COMPATIBLE = 1, EQUAL = 2;
  }

  public static final record Descriptor(String type, int arrayDimensions, String raw) {
    public static Descriptor of(String desc) {
      int dim = desc.lastIndexOf('[') + 1;
      return new Descriptor(desc.substring(dim), dim, desc);
    }

    @Override
    public String toString() {
      return raw;
    }

    public boolean isPrimitive() {
      return type.length() == 1;
    }

    public int likeness(Descriptor other, Set<String> legalAliases) {
      if(arrayDimensions != other.arrayDimensions) return Likeness.NONE;
      if(isPrimitive()) return other.type.equals(type) ? Likeness.EQUAL : Likeness.NONE;
      if(legalAliases.contains(getClassName())) return Likeness.COMPATIBLE;
      return type.equals(other.type) ? Likeness.EQUAL : Likeness.NONE;
    }

    public Descriptor remap(Mappings mappings) {
      return Descriptor.of(mappings.remapDescriptor(raw));
    }

    public String getClassName() {
      return type.charAt(type.length() - 1) == ';' ? type.substring(1, type.length() - 1) : type;
    }
  }

  public static final record FieldRepr(String name, Descriptor desc, int access) {
    public static FieldRepr of(FieldNode fn) {
      return new FieldRepr(fn.name, Descriptor.of(fn.desc), fn.access);
    }

    public double likeness(FieldRepr other, Set<String> legalAliases) {
      if(access != other.access) return 0;
      return desc.likeness(other.desc, legalAliases);
    }

    public FieldRepr remap(Mappings mappings) {
      return new FieldRepr(name, desc.remap(mappings), access);
    }
  }

  public static final record MethodDescriptor(Descriptor returnDesc, List<Descriptor> paramDescs, String raw) {

    public static MethodDescriptor of(String desc) {
      List<Descriptor> args = new ArrayList<>();
      int wordStart = 0;
      int dim = 0;
      for(int i = 1; i < desc.lastIndexOf(')'); i++) {
        char c = desc.charAt(i);
        if(c == '[') {
          dim++;
        } else if(wordStart > 0) {
          if(c == ';') {
            args.add(new Descriptor(desc.substring(wordStart, i + 1), dim, desc.substring(wordStart - dim, i + 1)));
            dim = wordStart = 0;
          }
        } else if('J' == c || 'I' == c || 'C' == c || 'S' == c || 'B' == c || 'Z' == c || 'F' == c || 'D' == c) {
          args.add(new Descriptor(String.valueOf(c), dim, desc.substring(i - dim, i + 1)));
          dim = 0;
        } else if('L' == c) {
          wordStart = i;
        } else throw new RuntimeException("Invalid descriptor: " + desc);
      }
      return new MethodDescriptor(Descriptor.of(desc.substring(desc.lastIndexOf(')') + 1)), args, desc);
    }

    public double likeness(MethodDescriptor other, Set<String> legalAliases) {
      if(paramDescs.size() != other.paramDescs.size()) return 0;
      int retLikeness = returnDesc.likeness(other.returnDesc, legalAliases);
      if(retLikeness == Likeness.NONE) return 0;
      int likeness = retLikeness == Likeness.EQUAL ? 2 : 1;
      for(int i = 0; i < paramDescs.size(); i++) {
        int parLikeness = paramDescs.get(i).likeness(other.paramDescs.get(i), legalAliases);
        if(parLikeness == Likeness.NONE) return 0;
        likeness += parLikeness == Likeness.EQUAL ? 2 : 1;
      }
      return (double) likeness / (paramDescs.size() + 1);
    }

    public MethodDescriptor remap(Mappings mappings) {
      List<Descriptor> paramDescs = new ArrayList<>();
      this.paramDescs.forEach(paramDesc -> paramDescs.add(paramDesc.remap(mappings)));
      return new MethodDescriptor(returnDesc.remap(mappings), paramDescs, mappings.remapDescriptor(raw));
    }

    public String toString() {
      return paramDescs.stream().map(Descriptor::toString).collect(Collectors.joining("", "(", ")" + returnDesc));
    }
  }

  public static final record MethodRepr(String name, MethodDescriptor desc, int access) {
    public static MethodRepr of(MethodNode mn) {
      return new MethodRepr(mn.name, MethodDescriptor.of(mn.desc), mn.access);
    }

    public double alikeness(MethodRepr other, Set<String> legalAliases) {
      if(access != other.access) return 0;
      return desc.likeness(other.desc, legalAliases);
    }

    public MethodRepr remap(Mappings mappings) {
      return new MethodRepr(name, desc.remap(mappings), access);
    }
  }

  public static final record ClassRepr(String name, String sup, List<String> ifaces, List<FieldRepr> fields, List<MethodRepr> methods, int access) {
    public static ClassRepr of(ClassNode cn) {
      List<FieldRepr> fields = new ArrayList<>();
      for(FieldNode fn : cn.fields) fields.add(FieldRepr.of(fn));
      List<MethodRepr> methods = new ArrayList<>();
      for(MethodNode mn : cn.methods) methods.add(MethodRepr.of(mn));
      return new ClassRepr(cn.name, cn.superName, new ArrayList<>(cn.interfaces), fields, methods, cn.access);
    }

    double likeness(ClassRepr other, Mappings context) {
      if(access != other.access) return 0;
      double likeness = 0;
      if(ifaces.size() == other.ifaces.size() && ifaces.isEmpty()) likeness += 1;
      return likeness;
    }

    public ClassRepr remap(Mappings mappings) {
      return new ClassRepr(
          mappings.getClassName(name),
          mappings.getClassName(sup),
          ifaces.stream().map(mappings::getClassName).toList(),
          fields.stream().map(fieldRepr -> fieldRepr.remap(mappings)).toList(),
          methods.stream().map(methodRepr -> methodRepr.remap(mappings)).toList(),
          access
      );
    }
  }

  public static final record MethodMatchResult(boolean hasMatched, Mappings mappings) {
    public static final MethodMatchResult UNMATCHED = new MethodMatchResult(false, null);
  }

  private final MappingsBuilder mappings = new MappingsBuilder();
  private final Map<String, ClassNode> classes;
  private final Map<String, ClassNode> refClasses;
  private final Map<String, ClassRepr> classReprs;
  private final Map<String, ClassRepr> refClassReprs;
  private final InheritanceChecker inheritanceChecker;
  private final InheritanceChecker refInheritanceChecker;
  private final UsageAnalyser usageAnalyser = new UsageAnalyser();
  private final MethodCache methodCache = new MethodCache();
  private final MethodCache refMethodCache = new MethodCache();
  private final InheritanceTree inheritanceTree = new InheritanceTree();
  private final InheritableAnalyzer inheritableAnalyzer = new InheritableAnalyzer();

  private ReferenceBasedMapper(JDKClassProvider jdkProvider, Path jar, Path ref, List<String> ignorePaths) throws IOException {
    refClasses = parseClasses(ref, ignorePaths, SKIP_DEBUG);
    refClassReprs = new HashMap<>();
    classes = parseClasses(jar, ignorePaths, SKIP_DEBUG);
    classReprs = new HashMap<>();
    refClasses.forEach((name, node) -> {
      refClassReprs.put(name, ClassRepr.of(node));
      node.methods.forEach(method ->
          refMethodCache.processMethod(node.name, method, refClasses.keySet())
      );
    });
    classes.forEach((name, node) -> {
      classReprs.put(name, ClassRepr.of(node));
      node.methods.forEach(method -> {
        usageAnalyser.processMethod(method.name, method, refClasses.keySet());
        methodCache.processMethod(node.name, method, refClasses.keySet());
        inheritableAnalyzer.processMethod(method.name, method, refClasses.keySet());
      });
      inheritanceTree.processClass(node);
    });
    inheritanceChecker = new InheritanceChecker(classes, jdkProvider);
    refInheritanceChecker = new InheritanceChecker(refClasses, jdkProvider);
  }

  private final Set<String> checkExemptions = new HashSet<>();

  //TODO: Collect Inheritance Information and add to usages for improved consistency checking.
  private boolean checkConsistency(Mappings changedMembers) {
    var toCheck = new HashMap<String, Set<ClassMember>>();
    changedMembers.forAllFields((className, fname, desc, rname) -> {
      var uses = usageAnalyser.getUsages(className, fname, desc);
      uses.forEach((useClass, classMembers) -> {
        toCheck.computeIfAbsent(useClass, k -> new HashSet<>()).addAll(classMembers);
        var subTypes = inheritanceTree.getSubTypes(useClass);
        classMembers.stream()
            .filter(inheritableAnalyzer.getInheritableMethods(useClass)::contains)
            .forEach(method -> subTypes.forEach(sub -> {
              if(methodCache.lookup(sub, method.name(), method.desc()) != null)
                toCheck.computeIfAbsent(sub, k -> new HashSet<>()).add(method);
            }));
      });
    });
    return BiStream.streamMap(toCheck).allMatch((className, classMembers) -> {
      for(var member : classMembers) {
        if(checkExemptions.contains(className + member))
          continue;
        if(!changedMembers.hasMethodMapping(className, member.name(), member.desc())) continue;
        var search = changedMembers.getMethodName(className, member.name(), member.desc());
        return compare(className, methodCache.lookup(className, member.name(), member.desc()), refMethodCache.lookup(className, search, member.desc())).hasMatched;
      }
      return true;
    });
  }

  private void genMemberMappings() {
    classes.forEach((name,classNode) -> {
      if(!refClasses.containsKey(name)){
        System.out.println("Class " + name + " not found, skipping\n");
        return;
      }
      System.out.println("Processing class " + name);
      var refClass = refClasses.get(name);
      List<FieldNode> fields = new ArrayList<>(classNode.fields);
      classNode.fields.forEach(field -> {
        List<FieldNode> options = classNode.fields.stream().filter(sameDescAccess(field)).toList();
        List<FieldNode> matchedNodes = refClass.fields.stream().filter(sameDescAccess(field)).toList();
        if(matchedNodes.isEmpty() || options.size() > matchedNodes.size()) {
          System.out.println("field " + name + "#" + field.name + " " + field.desc + " not found, skipping");
          fields.remove(field);
          return;
        }
        if(matchedNodes.size() == 1) {
          String refName = matchedNodes.get(0).name;
          if(!field.name.equals(refName))
            mappings.addFieldMapping(name, field.name, field.desc, refName);
          fields.remove(field);
        }
      });
      if(!fields.isEmpty()) System.out.println("Unmatched Fields: " + fields.size());
      List<MethodNode> methods = new ArrayList<>(classNode.methods);
      classNode.methods.forEach(method -> {
        if(mappings.hasMethodMapping(name, method.name, method.desc)) {
          methods.remove(method);
          return;
        }
        InheritanceStatus status = inheritanceChecker.getInheritance(classNode, method.name, method.desc, method.access);
        if(status == external() || status instanceof Internal i && mappings.hasMethodMapping(i.className(), method.name, method.desc)) {
          methods.remove(method);
          return;
        }
        List<MethodNode> contestants = classNode.methods.stream().filter(sameDescAccessInh(method, classNode, inheritanceChecker, status)).toList();
        List<MethodNode> matched = refClass.methods.stream().filter(sameDescAccessInh(method, refClass, refInheritanceChecker, status)).toList();
        if(matched.isEmpty() || contestants.size() > matched.size()) {
          System.out.println("method " + name + "#" + method.name + " " + method.desc + " not found, skipping");
          methods.remove(method);
          return;
        }
        var owner = status instanceof Internal i ? i.className() : name;
        if(matched.size() == 1) {
          String refName = matched.get(0).name;
          if(!method.name.equals(refName))
            mappings.addMethodMapping(owner, method.name, method.desc, refName);
          var res = compare(owner, method, matched.get(0));
          if(!res.hasMatched) {
            checkExemptions.add(owner + new ClassMember(method.name, method.desc));
            if(!refName.equals(method.name))System.out.println("WARN: matched method " + owner + "#" + method.name + method.desc + " -> " + refName + " is inconsistent, recheck manually (its code was likely updated)");
          }
          else {
            res.mappings.forAllFields(mappings::addFieldMapping);
            res.mappings.forAllMethods((cName, memberName, memberDesc, rName) ->
              mappings.addMethodMapping(
                  inheritanceChecker.getInheritance(
                      classes.get(cName),
                      memberName,
                      memberDesc,
                      0
                  ) instanceof Internal i ? i.className() : cName,
                  memberName,
                  memberDesc,
                  rName
              )
            );
          }
          methods.remove(method);
        } else {
          var insnMatched = new HashSet<Mappings>();
          for(MethodNode other : matched) {
            var result = compare(owner, method, other);
            if(!result.hasMatched) continue;
            if(!checkConsistency(result.mappings)) continue;
            insnMatched.add(result.mappings);
          }
          if(insnMatched.size() == 1) {
            var changes = insnMatched.iterator().next();
            changes.forAllFields(mappings::addFieldMapping);
            changes.forAllMethods((cName, memberName, memberDesc, rName) ->
                mappings.addMethodMapping(
                    inheritanceChecker.getInheritance(
                        classes.get(cName),
                        memberName,
                        memberDesc,
                        0
                    ) instanceof Internal i ? i.className() : cName,
                    memberName,
                    memberDesc,
                    rName
                )
            );
            methods.remove(method);
          }
        }
      });
      if(!methods.isEmpty()) System.out.println("Unmatched Methods: " + methods.size());
      System.out.println();
    });
  }

  public MethodMatchResult compare(String className, MethodNode method, MethodNode ref) {
    if(ref.instructions.size() != method.instructions.size()) return MethodMatchResult.UNMATCHED;
    var refInsns = ref.instructions.iterator();
    var cascadingChanges = new MappingsBuilder();
    for(AbstractInsnNode node : method.instructions) {
      AbstractInsnNode ain = refInsns.next();
      if(ain.getOpcode() != node.getOpcode()) return MethodMatchResult.UNMATCHED;
      switch(node) {
        case VarInsnNode vin when ain instanceof VarInsnNode vin2:
          if(vin.var != vin2.var) return MethodMatchResult.UNMATCHED;
          break;
        case FieldInsnNode fin when ain instanceof FieldInsnNode fin2:
          if(!fin.desc.equals(fin2.desc) || !fin.owner.equals(fin2.owner) ||
              !classes.containsKey(fin.owner) && !fin.name.equals(fin2.name)) return  MethodMatchResult.UNMATCHED;
          if(mappings.hasFieldMapping(fin.owner, fin.name, fin.desc)) {
            if(!mappings.getFieldName(fin.owner, fin.name, fin.desc).equals(fin2.name)) return MethodMatchResult.UNMATCHED;
          } else if(cascadingChanges.hasFieldMapping(fin.owner, fin.name, fin.desc)) {
            if(!cascadingChanges.getFieldName(fin.owner, fin.name, fin.desc).equals(fin2.name))
              return MethodMatchResult.UNMATCHED;
          } else if(!fin.name.equals(fin2.name)) cascadingChanges.addFieldMapping(fin.owner, fin.name, fin.desc, fin2.name);
          break;
        case MethodInsnNode min when ain instanceof MethodInsnNode min2:
          if(!min.desc.equals(min2.desc) || !min.owner.equals(min2.owner) ||
              !classes.containsKey(min.owner) && !min.name.equals(min2.name)) return MethodMatchResult.UNMATCHED;
          if(mappings.hasMethodMapping(min.owner, min.name, min.desc)) {
            if(!mappings.getMethodName(min.owner, min.name, min.desc).equals(min2.name))
              return MethodMatchResult.UNMATCHED;
          } else if(cascadingChanges.hasMethodMapping(min.owner, min.name, min.desc)) {
            if(!cascadingChanges.getMethodName(min.owner, min.name, min.desc).equals(min2.name))
              return MethodMatchResult.UNMATCHED;
          } else if(!min.name.equals(min2.name)) cascadingChanges.addMethodMapping(min.owner, min.name, min.desc, min2.name);
          break;
        case LdcInsnNode lin when ain instanceof LdcInsnNode lin2:
          if(lin.cst == null) {
            if(lin2.cst != null) return MethodMatchResult.UNMATCHED;
          } else if(!lin.cst.equals(lin2.cst)) return MethodMatchResult.UNMATCHED;
        default:
      }
    }
    if(!method.name.equals(ref.name))
      cascadingChanges.addMethodMapping(className, method.name, method.desc, ref.name);
    return new MethodMatchResult(true, cascadingChanges.build());
  }

  private Predicate<FieldNode> sameDescAccess(FieldNode field) {
    return fn -> fn.access == field.access && fn.desc.equals(field.desc);
  }

  private Predicate<MethodNode> sameDescAccessInh(MethodNode method, ClassNode classNode, InheritanceChecker inheritanceChecker, InheritanceStatus status) {
    return mn -> mn.access == method.access && mn.desc.equals(method.desc) && inheritanceChecker.getInheritance(classNode, mn.name, mn.desc, mn.access).equals(status);
  }

  private void genClassMappings() {
    Set<String> initialMappedClasses = findUniqueSupers();
    Set<String> fieldMappedClasses = findMappingsByFieldDescs(initialMappedClasses);
    System.out.println();
  }

  private Set<String> findMappingsByFieldDescs(Set<String> lookIn) {
    Set<String> mappedClasses = new HashSet<>();
    BiStream.streamMap(classReprs)
        .filter1(lookIn::contains)
        .map1(mappings::getClassName)
        .map1(refClassReprs::get)
        .forEach((refClass, cls) -> {
          cls.fields.forEach(field -> {
            if(!classReprs.containsKey(field.desc.getClassName())) return;
            Set<FieldRepr> matching = refClass.fields.stream()
                .filter(refField ->
                    refField.access == field.access
                        && refField.desc.likeness(field.desc, refClasses.keySet()) != Likeness.NONE)
                .collect(Collectors.toSet());
            if(matching.size() == 1) {
              ClassRepr classRepr = classReprs.get(field.desc.getClassName());
              ClassRepr refClassRepr = refClassReprs.get(matching.iterator().next().desc.getClassName());
              if(classReprs.containsKey(classRepr.sup)) {
                if(mappings.hasClassMapping(classRepr.sup)) {
                  if(!mappings.getClassName(classRepr.sup).equals(refClassRepr.sup)) return;
                } else if(!refClassReprs.containsKey(classRepr.sup)) return;
              } else if(!classRepr.sup.equals(refClassRepr.sup)) return;
              if(Math.abs(classRepr.fields.size() - refClassRepr.fields.size()) > 2) return;
              System.out.println(classRepr.name + " is likely " + refClassRepr.name);
              mappings.addClassMapping(classRepr.name, refClassRepr.name);
              mappedClasses.add(classRepr.name);
            }
          });
        });
    return mappedClasses;
  }

  public Set<String> findUniqueSupers() {
    Map<String, Set<String>> subClassesNew = new HashMap<>();
    Map<String, Set<String>> subClassesRef = new HashMap<>();
    Map<Set<String>, Set<String>> ifaceCombos = new HashMap<>();
    Map<Set<String>, Set<String>> ifaceCombosRef = new HashMap<>();
    classes.forEach((s, classNode) -> {
      if(mappings.hasClassMapping(s)) return;
      String superName = classNode.superName;
      if(!subClassesNew.containsKey(superName)) subClassesNew.put(superName, new HashSet<>());
      subClassesNew.get(superName).add(s);
      Set<String> ifaces = new HashSet<>(classNode.interfaces == null ? Set.of() : classNode.interfaces);
      if(!ifaceCombos.containsKey(ifaces)) ifaceCombos.put(ifaces, new HashSet<>());
      ifaceCombos.get(ifaces).add(s);
    });
    refClasses.forEach((s, classNode) -> {
      String superName = classNode.superName;
      if(!subClassesRef.containsKey(superName)) subClassesRef.put(superName, new HashSet<>());
      subClassesRef.get(superName).add(s);
      Set<String> ifaces = new HashSet<>(classNode.interfaces == null ? Set.of() : classNode.interfaces);
      if(!ifaceCombosRef.containsKey(ifaces)) ifaceCombosRef.put(ifaces, new HashSet<>());
      ifaceCombosRef.get(ifaces).add(s);
    });
    Set<String> mappedClasses = new HashSet<>();
    subClassesNew.forEach((superName, subClasses) -> {
      // Filter out obf superclasses, they cannot help us in this step
      if(subClasses.size() != 1 || classes.containsKey(superName) && !mappings.hasClassMapping(superName)) return;
      Set<String> oldSubs = subClassesRef.get(superName);
      if(oldSubs == null || oldSubs.size() != 1) return;
      String mapping = oldSubs.iterator().next();
      String subClass = subClasses.iterator().next();
      System.out.println("Class " + subClass + " has unique superclass " + superName + ", must be " + mapping);
      mappings.addClassMapping(subClass, mapping);
      mappedClasses.add(mapping);
    });
    ifaceCombos.forEach((ifaceNames, subClasses) -> {
      // Filter out obf superclasses, they cannot help us in this step
      if(subClasses.size() != 1) return;
      List<String> unnamedIfaces = ifaceNames.stream().filter(iface -> classes.containsKey(iface) && !mappings.hasClassMapping(iface)).toList();
      if(unnamedIfaces.size() > 1 || unnamedIfaces.size() == ifaceNames.size()) return;
      Set<String> oldSubs = ifaceCombosRef.get(ifaceNames);
      if(oldSubs == null || oldSubs.size() != 1) return;
      String mapping = oldSubs.iterator().next();
      String subClass = subClasses.iterator().next();
      System.out.println("Class " + subClass + " has unique interface combo " + ifaceNames + ", must be " + mapping);
      mappings.addClassMapping(subClass, mapping);
      mappedClasses.add(mapping);
    });
    return mappedClasses;
  }

  public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
    OptionParser parser = new OptionParser(new Command("class", null), new Command("members", null));
    List<String> ignorePaths = new ArrayList<>();
    var jarArg = ArgDefinition.arg("jar", Path.class).build();
    var refJarArg = ArgDefinition.arg("referenceJar", Path.class).build();
    var cpOpt = OptionDefinition.valued("jdk-path", Path.class)
        .mapValue(JDKClassProvider::new)
        .build();
    var ignoreOption = OptionDefinition.valued("ignore-paths")
        .mapValue(s -> Arrays.asList(s.split(";")))
        .callback(ignorePaths::addAll)
        .build();
    parser.getCommands().forEach(cmd -> {
      cmd.addOptions(cpOpt,  ignoreOption);
      cmd.addRequiredArgs(jarArg, refJarArg);
    });
    OptionParseResult result = parser.parse(args);
    if(result.subcommand == null) {
      System.out.println(parser.formatHelp(null, 80));
      return;
    }
    ReferenceBasedMapper bc = new ReferenceBasedMapper(
        result.getOrDefault(cpOpt, new JDKClassProvider()),
        result.getValue(jarArg),
        result.getValue(refJarArg),
        ignorePaths
    );
    switch (result.subcommand) {
      case "class":
        bc.genClassMappings();
        break;
      case "members":
        bc.genMemberMappings();
        break;
      default:
    }
    MappingsHandlers.writeMappings(bc.mappings.build(), Paths.get("out.frg")
    );
  }
}
