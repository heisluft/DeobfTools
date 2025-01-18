package de.heisluft.deobf.tooling;

import de.heisluft.deobf.mappings.Mappings;
import de.heisluft.deobf.mappings.MappingsBuilder;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A tool to generate method, class and field names.
 * <br>
 * As we need to fix compile errors in source we may not want to update our patches for every naming change
 * <br>
 * The initial development process for an obfuscated jar looks like this:
 * <ol>
 *  <li>Preprocess the jar file, restore all possible meta infos</li>
 *  <li>Let the tool generate unique mappings</li>
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
public class MappingsGenerator implements Util {

  /**
   * A Set of all inheritable methods for one class addressed as:
   * className -> methodName + methodDesc
   */
  private final Map<String, Set<String>> inheritableMethods = new HashMap<>();
  /**
   * A cache of all classes within the jar to emit mappings for, addressed by their name
   */
  private final Map<String, ClassNode> classNodes = new HashMap<>();
  /** The mappings builder to use */
  private final MappingsBuilder builder;
  /** Access to JDK classes for inheritance */
  private final JDKClassProvider provider;

  /**
   * Constructs a new Generator instance. Instances are single use!
   * @param supplementaryMappings supplementary mappings to use. if there is no
   */
  public MappingsGenerator(Mappings supplementaryMappings, JDKClassProvider provider) {
   builder = supplementaryMappings == null ? new MappingsBuilder() : new MappingsBuilder(supplementaryMappings);
   this.provider = provider;
  }

  /**
   * Recursively searches for methods inherited to addTo adding them to this.inheritableMethods
   *
   * @param cls
   *     the current class to be indexed
   * @param addTo
   *     the name of the class to find inherited methods for
   */
  private void gatherInheritedMethods(String cls, String addTo) {
    if(cls == null) return;
    ClassNode node = classNodes.containsKey(cls) ? classNodes.get(cls) : provider.getClassNode(cls);
    if(node == null) return;
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
    return isSerializable(provider.getClassNode(node.superName));
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

  /**
   * A set containing all methodNames + descriptors of java/lang/Object
   */
  private static final Set<String> OBJECT_MDS = new HashSet<>();

  static {
    for(Method method : Object.class.getDeclaredMethods())
      if(Util.hasNone(method.getModifiers(), Opcodes.ACC_STATIC, Opcodes.ACC_FINAL, Opcodes.ACC_PRIVATE))
        OBJECT_MDS.add(method.getName() + Type.getMethodDescriptor(method));
  }

  /**
   * A List of all java keywords with length 3 or below. Classes with these names will have their
   * name changed by the map task in order to allow simple decompilation afterwards
   */
  private static final List<String> RESERVED_WORDS = Arrays.asList(
      "do", "for", "if", "int", "new", "try", "to"
  );

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
  public Mappings generateMappings(Path input, List<String> ignored) throws IOException {
    if(!Files.isRegularFile(input)) throw new FileNotFoundException(input.toString());
    if(!Files.isReadable(input)) throw new IOException("Cannot read from " + input);
    classNodes.putAll(parseClasses(input));
    Set<String> packages = classNodes.values().stream().filter(p -> p.name.contains("/")).map(p -> p.name.substring(0, p.name.lastIndexOf("/"))).collect(Collectors.toSet());
    classNodes.values().stream().map(n -> n.name).filter(cn -> ignored.stream().noneMatch(("/" + cn)::startsWith)).filter(cn -> !builder.hasClassMapping(cn)).forEach(cn -> {
      String modifiedName = cn;
      // Reserved Words should be escaped automatically
      if(RESERVED_WORDS.contains(modifiedName)) {
        StringBuilder modBuilder = new StringBuilder(modifiedName);
        // Another class could be named _if, in that case this class will be named __if
        while(classNodes.containsKey(modBuilder.toString()) || builder.hasClassNameTarget(modBuilder.toString())) modBuilder.insert(0, "_");
        modifiedName = modBuilder.toString();
      }
      // Reserved Words should be escaped automatically
      else if(modifiedName.contains("/") && RESERVED_WORDS.contains(modifiedName.substring(modifiedName.lastIndexOf('/') + 1))) {
        // Again, we need to avoid naming blah/if to blah/_if if there is already a so named class
        while(classNodes.containsKey(modifiedName) || builder.hasClassNameTarget(modifiedName)) {
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
        builder.addClassMapping(cn, modifiedName);
    });

    builder.addExceptions(new ExceptionMapper(provider).analyzeExceptions(input));

    AtomicInteger fieldCounter = new AtomicInteger(1);
    AtomicInteger methodCounter = new AtomicInteger(1);
    classNodes.values().stream().sorted(Comparator.comparing(classNode -> classNode.name)).filter(c -> ignored.stream().noneMatch(("/" + c.name)::startsWith)).forEach(cn -> {
      gatherInheritedMethods(cn.superName);
      cn.interfaces.forEach(this::gatherInheritedMethods);
      cn.fields.forEach(fn -> {
        if(builder.hasFieldMapping(cn.name, fn.name)) return;
        // Automatically emit enum $VALUES mapping
        if(cn.superName.equals(Type.getInternalName(Enum.class)) && fn.desc.equals("[L" + cn.name + ";") && hasAll(fn.access, Opcodes.ACC_STATIC, Opcodes.ACC_SYNTHETIC, Opcodes.ACC_FINAL, Opcodes.ACC_PRIVATE)) {
          builder.addFieldMapping(cn.name, fn.name, fn.desc, "$VALUES");
        }
        // Dont generate Mappings for serialVersionUID
        else if(!(fn.name.equals("serialVersionUID") && fn.desc.equals("J") && hasAll(fn.access, Opcodes.ACC_STATIC, Opcodes.ACC_FINAL) && isSerializable(cn)))
          builder.addFieldMapping(cn.name, fn.name, fn.desc, "fd_" + fieldCounter.getAndIncrement() + "_" + fn.name);
      });
      Set<String> superMDs = inheritableMethods.getOrDefault(cn.superName, new HashSet<>());
      Set<String> ifaceMDs = cn.interfaces.stream().filter(inheritableMethods::containsKey).map(inheritableMethods::get).flatMap(Collection::stream).collect(Collectors.toSet());
      cn.methods.forEach(mn -> {
        if(builder.hasMethodMapping(cn.name, mn.name, mn.desc)) return;
        if((mn.access & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC) {
          //exclude public static void main(String[] args);
          if("main".equals(mn.name) && "([Ljava/lang/String;)V".equals(mn.desc) && (mn.access & Opcodes.ACC_PUBLIC) == Opcodes.ACC_PUBLIC && !builder.hasExceptionsFor(cn.name, "main", "(Ljava/lang/String;)V")) return;
          if(!"<clinit>".equals(mn.name) && !(cn.superName.equals(Type.getInternalName(Enum.class)) && genEnumMetDescs(cn.name).anyMatch(s -> s.equals(mn.name + mn.desc))))
            builder.addMethodMapping(cn.name, mn.name, mn.desc, "md_" + methodCounter.getAndIncrement() + "_" + mn.name);
        } else if(noneContains(mn.name + mn.desc, superMDs, ifaceMDs, OBJECT_MDS))
          if("<init>".equals(mn.name)) {
            if(!builder.hasExceptionsFor(cn.name, mn.name, mn.desc)) return;
            builder.addMethodMapping(cn.name, mn.name, mn.desc, mn.name);
          } else builder.addMethodMapping(cn.name, mn.name, mn.desc, "md_" + methodCounter.getAndIncrement() + "_" + mn.name);
      });
    });
    return builder.build();
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
}
