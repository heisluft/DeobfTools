package de.heisluft.reveng.mappings;

import de.heisluft.reveng.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.FileSystem;
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

import static de.heisluft.function.FunctionalUtil.thr;

/**
 * Fergie is a mapping generator. As we need to fix compile errors in source we may not want
 * to update our patches for every naming change.
 *
 * The dev chain looks like this
 * 1. Preprocess the jar file, restore all possible meta infos
 * 2. Generate dummy mappings
 * 3. Let Fergie process the mappings
 * 4. Remap jar with Fergie mappings
 * 5. Decompile
 * 6. Write Patches with Fergie-provided names and add Exceptions to the Fergie mappings
 * 7. Done
 *
 * The user is provided with obf->src and Fergie->src mappings as well as all needed patches.
 * In every deobf chain we do the following:
 * 1. Rename patches with Fergie->src mappings
 * 2. Remap the jar with obf->src mappings
 * 3. Decompile
 * 4. Patch with our renamed Patches.
 *
 * As Fergie mappings are unique renamings can be done by simple string replacements
 * without having to lex and parse the processed files.
 *
 */
public class Fergie implements Util, MappingsProvider {
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

  //className -> methodName + methodDesc
  private final Map<String, Set<String>> inheritableMethods = new HashMap<>();
  private final Map<String, ClassNode> classNodes = new HashMap<>();

  /**
   * A List of all java keywords with length 3 or below.
   * Classes with these names will have their name changed by the map task in order to allow simple
   * decompilation afterwards
   */
  private static final List<String> RESERVED_WORDS = Arrays.asList(
      "do", "for", "if", "int", "new", "try", "to"
  );

  static {
    for(Method method : Object.class.getDeclaredMethods())
      if(hasNone(method.getModifiers(), Opcodes.ACC_STATIC, Opcodes.ACC_FINAL, Opcodes.ACC_PRIVATE))
        OBJECT_MDS.add(method.getName() + Type.getMethodDescriptor(method));
  }

  /**
   * Returns if a given access modifier has all given flags.
   * For each flag {@code (access & flag) == flag} must hold true
   *
   * @param access The value to check
   * @param flags all flags that must be present
   * @return if all flags are present
   */
  private static boolean hasAll(int access, int... flags) {
    for(int flag : flags)
      if((access & flag) != flag) return false;
    return true;
  }

  /**
   * Returns if a given access modifier has none of the given flags.
   * For each flag {@code (access & flag) != flag} must hold true
   *
   * @param access The value to check
   * @param flags all flags that must not be present
   * @return if none of the given flags are present
   */
  private static boolean hasNone(int access, int... flags) {
    for(int flag : flags)
      if((access & flag) == flag) return false;
    return true;
  }

  private void buildClassHierarchy(Class<?> sup, String addTo) {
    if(sup != null && !sup.getName().equals(Object.class.getName())) {
      for(Method m : sup.getDeclaredMethods())
        if(hasNone(m.getModifiers(), Opcodes.ACC_FINAL, Opcodes.ACC_PRIVATE, Opcodes.ACC_STATIC))
          inheritableMethods.get(addTo).add(m.getName() + Type.getMethodDescriptor(m));
      for(Class<?> iface : sup.getInterfaces()) buildClassHierarchy(iface, addTo);
      buildClassHierarchy(sup.getSuperclass(), addTo);
    }
  }

  private void buildClassHierarchy(String nodeName, String addTo) {
    if(!classNodes.containsKey(nodeName)) {
      try {
        buildClassHierarchy(Class.forName(nodeName.replace("/", ".")), addTo);
      } catch(ClassNotFoundException e) {
        e.printStackTrace();
      }
      return;
    }
    ClassNode node = classNodes.get(nodeName);
    for(MethodNode m : node.methods)
      if(hasNone(m.access, Opcodes.ACC_FINAL, Opcodes.ACC_PRIVATE, Opcodes.ACC_STATIC))
        inheritableMethods.get(addTo).add(m.name + m.desc);
    for(String iface : node.interfaces) buildClassHierarchy(iface, addTo);
    buildClassHierarchy(node.superName, addTo);
  }

  private void gatherInheritedMethods(String cn) {
    if(inheritableMethods.containsKey(cn)) return;
    inheritableMethods.put(cn, new HashSet<>());
    buildClassHierarchy(cn, cn);
  }

  public Mappings parseMappings(Path input) throws IOException {
    Mappings mappings = new Mappings();
    Files.readAllLines(input).stream().map(line -> line.split(" ")).forEach(line -> {
      if("MD:".equals(line[FRG_MAPPING_TYPE_INDEX])) {
        if(line.length < 5) throw new IllegalArgumentException("Not enough arguments supplied. (" + join(line) + "), expected at least 4 got" + (line.length - 1));
        String clsName = line[FRG_ENTITY_CLASS_NAME_INDEX];
        String obfName = line[FRG_ENTITY_NAME_INDEX];
        String obfDesc = line[FRG_METHOD_DESCRIPTOR_INDEX];
        mappings.methods.computeIfAbsent(clsName, s -> new HashMap<>()).put(obfName + obfDesc, line[FRG_MAPPED_METHOD_NAME_INDEX]);
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
   * (Ljava/lang/String;)LclsName;</code>
   */
  private Stream<String> genEnumMetDescs(String clsName) {
    return Stream.of("values()[L" + clsName + ";", "valueOf(Ljava/lang/String;)L" + clsName + ";");
  }

  void writeMappings(Mappings mappings, Path to) throws IOException {
    List<String> lines = new ArrayList<>();
    mappings.classes.forEach((k,v) -> lines.add("CL: " + k + " " + v));
    mappings.fields.forEach((clsName, map) -> map.forEach((obfFd, deobfFd) -> lines.add("FD: " + clsName + " " + obfFd + " " + deobfFd)));
    mappings.methods.forEach((clsName, map) -> map.forEach((obfMet, deobfName) -> lines.add("MD: " + clsName + " " + obfMet.substring(0, obfMet.lastIndexOf('(')) + " " + obfMet.substring(obfMet.lastIndexOf('(')) + " " + deobfName)));
    lines.sort(Comparator.naturalOrder());
    Files.write(to, lines);
  }

  Mappings generateMappings(Path input) throws IOException {
    Mappings mappings = new Mappings();
    if(!Files.isRegularFile(input)) throw new FileNotFoundException(input.toString());
    if(!Files.isReadable(input)) throw new IOException("Cannot read from " + input);
    try(FileSystem fs = createFS(input)) {
      Files.walk(fs.getPath("/")).filter(this::hasClassExt).map(thr(this::parseClass)).forEach(c -> classNodes.put(c.name, c));
    }
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
            if(!modifiedName.contains("/")) while(packages.contains(modifiedName) || classNodes.containsKey(modifiedName))
              modifiedName = "_" + modifiedName;
            else while(packages.contains(modifiedName) || classNodes.containsKey(modifiedName)) {
              String[] split = splitAt(modifiedName, modifiedName.lastIndexOf("/"));
              modifiedName = split[0] + "/_" + split[1];
            }
          }
          mappings.classes.put(cn, modifiedName);
        });
    AtomicInteger fieldCounter = new AtomicInteger(1);
    AtomicInteger methodCounter = new AtomicInteger(1);
    classNodes.values().forEach(cn -> {
        gatherInheritedMethods(cn.superName);
        cn.interfaces.forEach(this::gatherInheritedMethods);
        cn.fields.forEach(fn -> {
          if (cn.superName.equals(Type.getInternalName(Enum.class))&&fn.desc.equals("[L" + cn.name + ";") && hasAll(fn.access, Opcodes.ACC_STATIC, Opcodes.ACC_SYNTHETIC, Opcodes.ACC_FINAL, Opcodes.ACC_PRIVATE)) {
            mappings.fields.computeIfAbsent(cn.name, s -> new HashMap<>()).put(fn.name, "$VALUES");
          }
          else mappings.fields.computeIfAbsent(cn.name, s -> new HashMap<>()).put(fn.name, "fd_" + fieldCounter.getAndIncrement() + "_" + fn.name);
        });
        Set<String> superMDs = inheritableMethods.getOrDefault(cn.superName, new HashSet<>());
        Set<String> ifaceMDs = cn.interfaces.stream().filter(inheritableMethods::containsKey).map(inheritableMethods::get).flatMap(Collection::stream).collect(Collectors.toSet());
        cn.methods.forEach(mn -> {
          if((mn.access & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC) {
            if(!"<clinit>".equals(mn.name) && !(cn.superName.equals(Type.getInternalName(Enum.class)) && genEnumMetDescs(cn.name).anyMatch(s -> s.equals(mn.name + mn.desc))))
              mappings.methods.computeIfAbsent(cn.name, s -> new HashMap<>()).put(mn.name + mn.desc, "md_" + methodCounter.getAndIncrement() + "_" + mn.name);
          } else if(noneContains(mn.name + mn.desc, superMDs, ifaceMDs, OBJECT_MDS))
            mappings.methods.computeIfAbsent(cn.name, s -> new HashMap<>()).put(mn.name + mn.desc, mn.name.equals("<init>") ? mn.name : ("md_" + methodCounter.getAndIncrement() + "_" + mn.name));
      });
    });
    return mappings;
  }

  /**
   * Returns true if none of a given set of sets contains a certain value t.
   * This is both shorter to write than checking each set individually
   * and faster than combining all sets and calling contains on that combined set
   *
   * @param t the value to look for
   * @param sets the sets to check
   * @param <T> the Type of the value and the sets to check
   *
   * @return true if none of the sets contain t, false otherwise
   */
  @SafeVarargs
  private final <T> boolean noneContains(T t, Set<T>... sets) {
    for(Set<T> set : sets) if(set.contains(t)) return false;
    return true;
  }

  private void emitIntermediateMappings(Path obfMappings, Path to) throws IOException {
    List<String> lines = Files.readAllLines(obfMappings);
    AtomicInteger mdCounter = new AtomicInteger(1);
    AtomicInteger fdCounter = new AtomicInteger(1);
    Files.write(to, lines.stream().filter(l -> !l.startsWith("CL: ")).map(line -> {
      String[] split = line.split(" ");
      StringBuilder builder = new StringBuilder(split[0]);
      boolean isMethod = split[0].equals("MD:");
      int i;
      for(i = 1; i < (isMethod ? 4 : 3); i++) {
        builder.append(" ").append(split[i]);
      }
      builder.append(isMethod ? " md_" : " fd_").append(isMethod ? mdCounter.getAndIncrement() : fdCounter.getAndIncrement()).append('_')
          .append(split[i]);
      for(i++; i < split.length; i++) {
        builder.append(" ").append(split[i]);
      }
      return builder.toString();
    }).collect(Collectors.toList()));
  }

  private void renamePatches(Path inter, Path patchDir, Path outDir) throws IOException {

  }
}
