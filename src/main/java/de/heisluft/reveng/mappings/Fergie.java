package de.heisluft.reveng.mappings;

import de.heisluft.function.Tuple2;
import de.heisluft.reveng.ExceptionMapper;
import de.heisluft.reveng.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fergie is a mappings parser and generator.
 * As we need to fix compile errors in source we may not want to update our patches for every naming change
 * <br>
 * The initial development process for an obfuscated jar looks like this:
 * <ol>
 *  <li>Preprocess the jar file, restore all possible meta infos</li>
 *  <li>Let Fergie generate unique mappings</li>
 *  <li>Remap jar with Fergie mappings</li>
 *  <li>Decompile</li>
 *  <li>Add Exceptions and src class names to the Fergie mappings</li>
 *  <li>Write Patches with Fergie-provided names</li>
 *  <li>Copy the mappings to another file</li>
 *  <li>Write obf -> src mappings for fields & methods to that file</li>
 * </ol>
 * <br>
 * For a user using the jar as a library, e.g. a modder, we can automatically:
 * <ol>
 *  <li>Generate Fergie -> src mappings from obf -> Fergie and obf -> source mappings</li>
 *  <li>Rename patches with Fergie -> src mappings</li>
 *  <li>Remap the jar with obf -> src mappings</li>
 *  <li>Decompile</li>
 *  <li>Patch with our renamed Patches</li>
 *  <li>Recompile</li>
 *  </ol>
 * <br>
 * As Fergie mappings are unique renamings can be done by simple string replacements without having
 * to lex and parse java source files (they may not even be parsable for certain errors).
 */
public class Fergie implements Util, MappingsProvider {
  /**
   * A singleton instance is used for parsing and writing mappings.
   * Generating mappings is stateful so a new instance is needed everytime for that job
   */
  static final Fergie INSTANCE = new Fergie();

  private static final int FRG_MAPPING_TYPE_INDEX = 0;
  private static final int FRG_ENTITY_CLASS_NAME_INDEX = 1;
  private static final int FRG_MAPPED_CLASS_NAME_INDEX = 2;
  private static final int FRG_ENTITY_NAME_INDEX = 2;
  private static final int FRG_MAPPED_FIELD_NAME_INDEX = 3;
  private static final int FRG_METHOD_DESCRIPTOR_INDEX = 3;
  private static final int FRG_MAPPED_METHOD_NAME_INDEX = 4;

  /**
   * A set containing all methodNames + descriptors of java/lang/Object
   */
  private static final Set<String> OBJECT_MDS = new HashSet<>();
  /**
   * A List of all java keywords with length 3 or below. Classes with these names will have their
   * name changed by the map task in order to allow simple decompilation afterwards
   */
  private static final List<String> RESERVED_WORDS = Arrays.asList(
      "do", "for", "if", "int", "new", "try", "to"
  );

  static {
    for(Method method : Object.class.getDeclaredMethods())
      if(Util.hasNone(method.getModifiers(), Opcodes.ACC_STATIC, Opcodes.ACC_FINAL, Opcodes.ACC_PRIVATE))
        OBJECT_MDS.add(method.getName() + Type.getMethodDescriptor(method));
  }

  /**
   * A Set of all inheritable methods for one class addressed as:
   * className -> methodName + methodDesc
   */
  private final Map<String, Set<String>> inheritableMethods = new HashMap<>();
  /**
   * A cache of all classes within the jar to emit mappings for, addressed by their name
   */
  private final Map<String, ClassNode> classNodes = new HashMap<>();

  /**
   * Returns if a given access modifier has all given flags. For each flag {@code (access & flag) ==
   * flag} must hold true
   *
   * @param access
   *     The value to check
   * @param flags
   *     all flags that must be present
   *
   * @return if all flags are present
   */
  private static boolean hasAll(int access, int... flags) {
    for(int flag : flags)
      if((access & flag) != flag) return false;
    return true;
  }

  /**
   * Recursively searches for methods inherited to addTom adding them to this.inheritableMethods
   *
   * @param cls
   *     the current class to be indexed
   * @param addTo
   *     the name of the class to find inherited methods for
   */
  private void gatherInheritedMethods(Class<?> cls, String addTo) {
    if(cls != null && !cls.getName().equals(Object.class.getName())) {
      for(Method m : cls.getDeclaredMethods())
        if(Util.hasNone(m.getModifiers(), Opcodes.ACC_FINAL, Opcodes.ACC_PRIVATE,
            Opcodes.ACC_STATIC))
          inheritableMethods.get(addTo).add(m.getName() + Type.getMethodDescriptor(m));
      for(Class<?> iface : cls.getInterfaces()) gatherInheritedMethods(iface, addTo);
      gatherInheritedMethods(cls.getSuperclass(), addTo);
    }
  }

  /**
   * Recursively searches for methods inherited to addTom adding them to this.inheritableMethods
   *
   * @param cls
   *     the current class to be indexed
   * @param addTo
   *     the name of the class to find inherited methods for
   */
  private void gatherInheritedMethods(String cls, String addTo) {
    if(!classNodes.containsKey(cls)) {
      try {
        gatherInheritedMethods(Class.forName(cls.replace("/", ".")), addTo);
      } catch(ClassNotFoundException e) {
        e.printStackTrace();
      }
      return;
    }
    ClassNode node = classNodes.get(cls);
    for(MethodNode m : node.methods)
      if(Util.hasNone(m.access, Opcodes.ACC_FINAL, Opcodes.ACC_PRIVATE, Opcodes.ACC_STATIC))
        inheritableMethods.get(addTo).add(m.name + m.desc);
    for(String iface : node.interfaces) gatherInheritedMethods(iface, addTo);
    gatherInheritedMethods(node.superName, addTo);
  }

  /**
   * Searches for methods inherited to cn adding them to this.inheritableMethods
   *
   * @param cn
   *     the name of the class to find inherited methods for
   */
  private void gatherInheritedMethods(String cn) {
    if(inheritableMethods.containsKey(cn)) return;
    inheritableMethods.put(cn, new HashSet<>());
    gatherInheritedMethods(cn, cn);
  }

  public Mappings parseMappings(Path input) throws IOException {
    Mappings mappings = new Mappings();
    Files.readAllLines(input).stream().map(line -> line.split(" ")).forEach(line -> {
      if("MD:".equals(line[FRG_MAPPING_TYPE_INDEX])) {
        if(line.length < 5) throw new IllegalArgumentException("Not enough arguments supplied. (" + join(line) + "), expected at least 4 got" + (line.length - 1));
        String clsName = line[FRG_ENTITY_CLASS_NAME_INDEX];
        String obfName = line[FRG_ENTITY_NAME_INDEX];
        String obfDesc = line[FRG_METHOD_DESCRIPTOR_INDEX];
        mappings.methods.computeIfAbsent(clsName, s -> new HashMap<>()).put(new Tuple2<>(obfName, obfDesc), line[FRG_MAPPED_METHOD_NAME_INDEX]);
        for(int i = 5; i < line.length; i++)
          mappings.exceptions.computeIfAbsent(clsName + obfName + obfDesc, s -> new ArrayList<>()).add(line[i]);
      } else if("FD:".equals(line[FRG_MAPPING_TYPE_INDEX])) {
        if(line.length != 4) throw new IllegalArgumentException("Illegal amount of Arguments supplied. (" + join(line) + "), expected 3 got" + (line.length - 1));
        mappings.fields.computeIfAbsent(line[FRG_ENTITY_CLASS_NAME_INDEX], s -> new HashMap<>())
            .put(line[FRG_ENTITY_NAME_INDEX], line[FRG_MAPPED_FIELD_NAME_INDEX]);
      } else if("CL:".equals(line[FRG_MAPPING_TYPE_INDEX])) {
        if(line.length != 3) throw new IllegalArgumentException("Illegal amount of Arguments supplied. (" + join(line) + "), expected 2 got" + (line.length - 1));
        mappings.classes.put(line[FRG_ENTITY_CLASS_NAME_INDEX], line[FRG_MAPPED_CLASS_NAME_INDEX]);
      } else {
        System.out.print("Not operating on line '" + join(line) + "'!");
      }
    });
    return mappings;
  }

  /**
   * Generates enum method descriptors for a given class (namely the valueOf and values methods)
   *
   * @param clsName
   *     the name of the class to generate for
   *
   * @return a set containing <code>values()[LclsName;</code> and <code>valueOf
   *     (Ljava/lang/String;)LclsName;</code>
   */
  private Stream<String> genEnumMetDescs(String clsName) {
    return Stream.of("values()[L" + clsName + ";", "valueOf(Ljava/lang/String;)L" + clsName + ";");
  }

  /**
   * Writes Mappings in frg file format to path.
   *
   * @param mappings
   *     the mappings to serialize
   * @param to
   *     the path to write to
   *
   * @throws IOException
   *     If the path could not be written to
   */
  void writeMappings(Mappings mappings, Path to) throws IOException {
    List<String> lines = new ArrayList<>();
    mappings.classes.forEach((k, v) -> lines.add("CL: " + k + " " + v));
    mappings.fields.forEach((clsName, map) -> map.forEach((obfFd, deobfFd) -> lines.add("FD: " + clsName + " " + obfFd + " " + deobfFd)));
    mappings.methods.forEach((clsName, map) -> map.forEach((obfMet, deobfName) -> {
      StringBuilder line = new StringBuilder("MD: " + clsName + " " + obfMet._1 + " " + obfMet._2 + " " + deobfName);
      mappings.getExceptions(clsName, obfMet._1, obfMet._2).forEach(s -> line.append(" ").append(s));
      lines.add(line.toString());
    }));
    lines.sort(Comparator.naturalOrder());
    Files.write(to, lines);
  }

  /**
   * Generates default mappings for a given jar. These mappings are guaranteed to generate unique
   * method names.
   *
   * @param input
   *     the jar to generate for
   * @param ignored
   *     a list of paths to be ignored. these paths will be loaded to gather inheritance info but
   *     will not have mappings emitted
   *
   * @return the generated mappings
   *
   * @throws IOException
   *     if the jar file could not be read correctly
   */
  //TODO: Don't emit mappings for anonymous classes, or rename them with the typical $1 suffix
  Mappings generateMappings(Path input, List<String> ignored) throws IOException {
    Mappings mappings = new Mappings();
    if(!Files.isRegularFile(input)) throw new FileNotFoundException(input.toString());
    if(!Files.isReadable(input)) throw new IOException("Cannot read from " + input);
    classNodes.putAll(parseClasses(input));
    Set<String> packages = classNodes.values().stream().filter(p -> p.name.contains("/")).map(p -> p.name.substring(0, p.name.lastIndexOf("/"))).collect(Collectors.toSet());
    classNodes.values().stream().map(n -> n.name).forEach(cn -> {
      String modifiedName = cn;
      // Reserved Words should be escaped automatically
      if(RESERVED_WORDS.contains(modifiedName)) {
        StringBuilder modBuilder = new StringBuilder(modifiedName);
        // Another class could be named _if, in that case this class will be named __if
        while(classNodes.containsKey(modBuilder.toString())) modBuilder.insert(0, "_");
        modifiedName = modBuilder.toString();
      }
      // Reserved Words should be escaped automatically
      else if(modifiedName.contains("/") && RESERVED_WORDS.contains(modifiedName.substring(modifiedName.lastIndexOf('/') + 1))) {
        // Again, we need to avoid naming blah/if to blah/_if if there is already a so named class
        while(classNodes.containsKey(modifiedName)) {
          String[] split = splitAt(modifiedName, modifiedName.lastIndexOf("/"));
          modifiedName = split[0] + "/_" + split[1];
        }
      }
      // Classes and Packages must not share names, so just prepend underscores until a unique class name is guaranteed
      if(packages.contains(modifiedName)) {
        if(!modifiedName.contains("/"))
          while(packages.contains(modifiedName) || classNodes.containsKey(modifiedName))
            modifiedName = "_" + modifiedName;
        else while(packages.contains(modifiedName) || classNodes.containsKey(modifiedName)) {
          String[] split = splitAt(modifiedName, modifiedName.lastIndexOf("/"));
          modifiedName = split[0] + "/_" + split[1];
        }
      }
      if(ignored.stream().noneMatch(modifiedName::startsWith))
        mappings.classes.put(cn, modifiedName);
    });

    mappings.exceptions.putAll(new ExceptionMapper().analyzeExceptions(input));

    AtomicInteger fieldCounter = new AtomicInteger(1);
    AtomicInteger methodCounter = new AtomicInteger(1);
    classNodes.values().stream().filter(c -> ignored.stream().noneMatch(c.name::startsWith))
        .forEach(cn -> {
          gatherInheritedMethods(cn.superName);
          cn.interfaces.forEach(this::gatherInheritedMethods);
          cn.fields.forEach(fn -> {
            // Automatically emit enum $VALUES mapping
            if(cn.superName.equals(Type.getInternalName(Enum.class)) && fn.desc.equals("[L" + cn.name + ";") && hasAll(fn.access, Opcodes.ACC_STATIC, Opcodes.ACC_SYNTHETIC, Opcodes.ACC_FINAL, Opcodes.ACC_PRIVATE)) {
              mappings.fields.computeIfAbsent(cn.name, s -> new HashMap<>()).put(fn.name, "$VALUES");
            }
            // Dont generate Mappings for serialVersionUID
            else if(!(fn.name.equals("serialVersionUID") && fn.desc.equals("J") && hasAll(fn.access, Opcodes.ACC_STATIC, Opcodes.ACC_FINAL) && isSerializable(cn)))
              mappings.fields.computeIfAbsent(cn.name, s -> new HashMap<>()).put(fn.name, "fd_" + fieldCounter.getAndIncrement() + "_" + fn.name);
          });
          Set<String> superMDs = inheritableMethods.getOrDefault(cn.superName, new HashSet<>());
          Set<String> ifaceMDs = cn.interfaces.stream().filter(inheritableMethods::containsKey).map(inheritableMethods::get).flatMap(Collection::stream).collect(Collectors.toSet());
          cn.methods.forEach(mn -> {
            if((mn.access & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC) {
              if(!"<clinit>".equals(mn.name) && !(cn.superName.equals(Type.getInternalName(Enum.class)) && genEnumMetDescs(cn.name).anyMatch(s -> s.equals(mn.name + mn.desc))))
                mappings.methods.computeIfAbsent(cn.name, s -> new HashMap<>()).put(new Tuple2<>(mn.name, mn.desc), "md_" + methodCounter.getAndIncrement() + "_" + mn.name);
            } else if(noneContains(mn.name + mn.desc, superMDs, ifaceMDs, OBJECT_MDS))
              if("<init>".equals(mn.name)) {
                if(!mappings.exceptions.containsKey(cn.name + mn.name + mn.desc)) return;
                mappings.methods.computeIfAbsent(cn.name, s -> new HashMap<>()).put(new Tuple2<>(mn.name, mn.desc), mn.name);
            } else
              mappings.methods.computeIfAbsent(cn.name, s -> new HashMap<>()).put(new Tuple2<>(mn.name, mn.desc), "md_" + methodCounter.getAndIncrement() + "_" + mn.name);
          });
        });
    return mappings;
  }

  /**
   * Returns whether a class inherits from java/io/Serializable in any way, either because it is an
   * interface subclassing Serializable either directly or indirectly or because its a normal class
   * either directly implementing Serializable or subclassing a class directly or indirectly that
   * does
   *
   * @param node
   *     the class to check
   *
   * @return whether thr class inherits from Serializable in any way
   */
  private boolean isSerializable(Class<?> node) {
    return node.equals(Serializable.class) ||
        Arrays.asList(node.getInterfaces()).contains(Serializable.class) ||
        !node.getSuperclass().equals(Object.class) && isSerializable(node.getSuperclass());
  }

  /**
   * Returns whether a class inherits from java/io/Serializable in any way, either because it is an
   * interface subclassing Serializable either directly or indirectly or because its a normal class
   * either directly implementing Serializable or subclassing a class directly or indirectly that
   * does
   *
   * @param node
   *     the class node to check
   *
   * @return whether the class inherits from Serializable in any way
   */
  private boolean isSerializable(ClassNode node) {
    if(node.interfaces.contains("java/io/Serializable")) return true;
    if(node.superName.equals("java/io/Serializable")) return true;
    if(node.superName.equals("java/lang/Object")) return false;
    if(classNodes.containsKey(node.superName))
      return isSerializable(classNodes.get(node.superName));
    try {
      return isSerializable(Class.forName(node.superName.replace('/', '.')));
    } catch(ClassNotFoundException exception) {
      return false;
    }
  }

  /**
   * Returns true if none of a given set of sets contains a certain value t. This is both shorter to
   * write than checking each set individually and faster than combining all sets and calling
   * contains on that combined set
   *
   * @param t
   *     the value to look for
   * @param sets
   *     the sets to check
   * @param <T>
   *     the Type of the value and the sets to check
   *
   * @return true if none of the sets contain t, false otherwise
   */
  @SafeVarargs
  private final <T> boolean noneContains(T t, Set<T>... sets) {
    for(Set<T> set : sets) if(set.contains(t)) return false;
    return true;
  }
}
