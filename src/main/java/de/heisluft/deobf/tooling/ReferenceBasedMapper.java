package de.heisluft.deobf.tooling;

import de.heisluft.cli.simpleopt.OptionDefinition;
import de.heisluft.cli.simpleopt.OptionParseResult;
import de.heisluft.cli.simpleopt.OptionParser;
import de.heisluft.cli.simpleopt.SubCommand;
import de.heisluft.deobf.mappings.Mappings;
import de.heisluft.deobf.mappings.MappingsBuilder;
import de.heisluft.deobf.mappings.MappingsHandlers;
import de.heisluft.deobf.tooling.structure.InheritanceChecker;
import de.heisluft.deobf.tooling.structure.InheritanceStatus;
import de.heisluft.deobf.tooling.structure.InheritanceStatus.Internal;
import de.heisluft.stream.BiStream;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static de.heisluft.deobf.tooling.structure.InheritanceStatus.external;
import static org.objectweb.asm.ClassReader.SKIP_DEBUG;

public class ReferenceBasedMapper implements Util {

  public static final class Likeness {
    public static final int NONE = 0, COMPATIBLE = 1, EQUAL = 2;
  }

  public static final record Descriptor(String type, int arrayDimensions) {
    public static Descriptor of(String desc) {
      int dim = desc.lastIndexOf('[') + 1;
      return new Descriptor(desc.substring(dim), dim);
    }

    @Override
    public String toString() {
      return "[".repeat(arrayDimensions) + type;
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
      return new Descriptor(mappings.remapDescriptor(type), arrayDimensions);
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

  public static final record MethodDescriptor(Descriptor returnDesc, List<Descriptor> paramDescs) {

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
            args.add(new Descriptor(desc.substring(wordStart, i + 1), dim));
            dim = wordStart = 0;
          }
        } else if('J' == c || 'I' == c || 'C' == c || 'S' == c || 'B' == c || 'Z' == c || 'F' == c || 'D' == c) {
          args.add(new Descriptor(String.valueOf(c), dim));
          dim = 0;
        } else if('L' == c) {
          wordStart = i;
        } else throw new RuntimeException("Invalid descriptor: " + desc);
      }
      return new MethodDescriptor(Descriptor.of(desc.substring(desc.lastIndexOf(')') + 1)), args);
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
      return new MethodDescriptor(returnDesc.remap(mappings), paramDescs);
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

  private final MappingsBuilder mappings = new MappingsBuilder();
  private final JDKClassProvider jdkProvider;
  private final Map<String, ClassNode> classes;
  private final Map<String, ClassNode> refClasses;
  private final Map<String, ClassRepr> classReprs;
  private final Map<String, ClassRepr> refClassReprs;
  private final InheritanceChecker inheritanceChecker;
  private final InheritanceChecker refInheritanceChecker;

  private ReferenceBasedMapper(JDKClassProvider jdkProvider, Path jar, Path ref, List<String> ignorePaths) throws IOException {
    this.jdkProvider = jdkProvider;
    refClasses = parseClasses(ref, ignorePaths, SKIP_DEBUG);
    refClassReprs = new HashMap<>();
    classes = parseClasses(jar, ignorePaths, SKIP_DEBUG);
    classReprs = new HashMap<>();
    refClasses.forEach((name, node) -> {
      refClassReprs.put(name, ClassRepr.of(node));
    });
    classes.forEach((name, node) -> {
      classReprs.put(name, ClassRepr.of(node));
    });
    inheritanceChecker = new InheritanceChecker(classes, jdkProvider);
    refInheritanceChecker = new InheritanceChecker(refClasses, jdkProvider);
  }

  private void genMemberMappings() {
    classes.forEach((name,classNode) -> {
      if(!refClasses.containsKey(name)){
        System.out.println("Class " + name + " not found, skipping\n");
        return;
      }
      System.out.println("Processing class " + name);
      ClassNode refClass = refClasses.get(name);
      List<FieldNode> fields = new ArrayList<>(classNode.fields);
      classNode.fields.forEach(field -> {
        List<FieldNode> matchedNodes = refClass.fields.stream().filter(fn -> fn.access == field.access && fn.desc.equals(field.desc)).toList();
        if(matchedNodes.isEmpty()) {
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
        List<MethodNode> matchedNodes = refClass.methods.stream().filter(mn -> mn.access == method.access && mn.desc.equals(method.desc) && refInheritanceChecker.getInheritance(refClass, mn.name, mn.desc, mn.access).equals(status)).toList();
        if(matchedNodes.isEmpty()) {
          System.out.println("method " + name + "#" + method.name + " " + method.desc + " not found, skipping");
          methods.remove(method);
          return;
        }
        if(matchedNodes.size() == 1) {
          String refName = matchedNodes.get(0).name;
          if(!method.name.equals(refName))
            mappings.addMethodMapping(status instanceof Internal i ? i.className() : name, method.name, method.desc, refName);
          methods.remove(method);
        }
      });
      if(!methods.isEmpty()) System.out.println("Unmatched Methods: " + methods.size());
      System.out.println();
    });
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
    OptionParser parser = new OptionParser(new SubCommand("class", null), new SubCommand("members", null));
    List<String> ignorePaths = new ArrayList<>();
    AtomicReference<JDKClassProvider> classProvider = new AtomicReference<>(new JDKClassProvider());
    parser.addOptions(
        OptionDefinition.arg("jdk-path", Path.class)
            .mapValue(JDKClassProvider::new)
            .callback(classProvider::set)
            .build(),
        OptionDefinition.arg("ignore-paths")
            .mapValue(s -> Arrays.asList(s.split(";")))
            .callback(ignorePaths::addAll)
            .build()
    );
    OptionParseResult result = parser.parse(args);
    List<String> additional = result.additional;
    if(additional.size() != 2) return;
    if(result.subcommand == null) {
      System.out.println(parser.formatHelp(null, 80));
      return;
    }
    ReferenceBasedMapper bc = new ReferenceBasedMapper(
        classProvider.get(),
        Paths.get(additional.get(0)),
        Paths.get(additional.get(1)),
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
