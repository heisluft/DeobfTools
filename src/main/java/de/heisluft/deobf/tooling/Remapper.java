package de.heisluft.deobf.tooling;

import de.heisluft.cli.simpleopt.OptionParseResult;
import de.heisluft.cli.simpleopt.SubCommand;
import de.heisluft.cli.simpleopt.OptionParser;
import de.heisluft.deobf.mappings.Mappings;
import de.heisluft.deobf.mappings.MappingsHandlers;
import de.heisluft.deobf.mappings.MappingsHandler;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static de.heisluft.cli.simpleopt.OptionDefinition.*;

//TODO: Think about a clever way to restore generic signatures on fields and based on that, methods
//TODO: Come up with an idea on how to restore generic signatures of obfuscated classes with the help of the specialized subclass bridge methods
//The Ultimate Goal would be a Remapper which is smart enough to generate the specialized methods from bridge methods
public class Remapper implements Util {
  //className -> methodName + methodDesc
  private static final Map<String, Set<String>> INHERITABLE_METHODS = new HashMap<>();
  //className -> fieldName + ":" + fieldDesc
  private static final Map<String, Set<String>> SUBCLASS_ACCESSIBLE_FIELDS = new HashMap<>();

  private final Map<String, ClassNode> classNodes = new HashMap<>();

  private static void displayHelpAndExit(OptionParser p) {
    System.out.println(p.formatHelp("Heislufts Remapping Service version 1.0\n A deobfuscator and mappings generator\nusage: Remapper [options] <task> <input> <mappings>", 100));
    System.exit(0);
  }

  public static void main(String[] args) throws IOException {
    List<String> ignoredPaths = new ArrayList<>();
    AtomicReference<Path> outPath = new AtomicReference<>();
    AtomicReference<Path> jdkPath = new AtomicReference<>();
    AtomicReference<Mappings> supplementaryMappings = new AtomicReference<>();
    AtomicBoolean stripBridgeAccess = new AtomicBoolean(true);
    OptionParser parser = new OptionParser(
        new SubCommand("map", "Generates obfuscation mappings from the <input> jar and writes them to <mappings>."),
        new SubCommand("remap", "Remaps the <input> jar with the specified <mappings> file and writes it to [output]. If the outputPath option is not specified, it will default to <input>-deobf.jar"),
        new SubCommand("genReverseMappings", "Generates reverse obfuscation mappings from the <input> mappings and writes them to <mappings>."),
        new SubCommand("genMediatorMappings", "Writes mappings mapping the output of <input> to the output of <mappings> to [output]."),
        new SubCommand("genConversionMappings", "Writes mappings mapping the input of <input> to the output of <mappings> to [output]."),
        new SubCommand("cleanMappings", "Writes a clean version of the mappings at <input> to <mapping>")
    );
    parser.addOptions(
        arg("ignorePaths")
            .validFor("map", "remap")
            .description("A List of paths to ignore from the input jar. Multiple Paths are separated using ; (semicolon). These Paths are treated as wildcards. For example, -i /com;/org/unwanted/ would lead the program to exclude all paths starting with either '/com' or '/org/unwanted/' eg. '/com/i.class', '/computer.xml', '/org/unwanted/b.gif'. This option will be ignored for tasks only operating on mappings", "pathsToIgnore")
            .callback(s -> ignoredPaths.addAll(Arrays.asList(s.split(";"))))
            .build(),
        arg("outputPath", Path.class)
            .validFor("remap", "genConversionMappings", "genMediatorMappings")
            .description("Overrides the path where the remapped jar will be written to. This option will be ignored for 'map', 'genReverseMappings' and 'cleanMappings'.", "outputPath")
            .callback(p -> {
              if(!Files.isWritable(p)) throw new IllegalArgumentException("output path is not writable");
              outPath.set(p);
            })
            .build(),
        arg("supplementary", Path.class)
            .validFor("map")
            .description("Valid only for 'map'. Provides supplementary mappings. For these, no new mappings will be generated, instead they will directly be merged into the output mappings file", "mappingsPath")
            .callback(p -> {
              if(!Files.isReadable(p)) throw new IllegalArgumentException("mappings path does not exist or is not readable");
              try {
                supplementaryMappings.set(MappingsHandlers.parseMappings(p));
              } catch(IOException exception) {
                throw new IllegalArgumentException("Error reading mappings at " + p, exception);
              }
            })
            .build(),
        arg("jdk", Path.class)
            .validFor("map")
            .description("Valid only for 'map'. Path to JDK, used for inferring exceptions", "jdkPath")
            .callback(p -> {
              if(!Files.isDirectory(p)) throw new IllegalArgumentException("jdk path does not point to a directory");
              jdkPath.set(p);
            })
            .build(),
        flag("noBridgeStrip").shorthand('b')
            .validFor("remap")
            .description("Valid only for 'remap'. Skips stripping of bridge and synthetic access modifiers for bridge methods.")
            .whenSet(() -> stripBridgeAccess.set(false))
            .build(),
        flag("help")
            .description("Displays this message.")
            .whenSet(() -> displayHelpAndExit(parser))
            .build()
    );
    OptionParseResult result = parser.parse(args);
    if(result.subcommand == null)  {
      displayHelpAndExit(parser);
      return;
    }
    String action = result.subcommand;
    List<String> remaining = result.additional;
    if(remaining.size() < 2) {
      System.out.println("Insufficient arguments, expected 2. Run with --help for more information.");
      System.exit(1);
    }
    if(remaining.size() > 2) System.out.println("Ignored arguments: " + remaining.subList(2, remaining.size()));

    Path inputPath = Paths.get(remaining.get(0));
    Path mappingsPath = Paths.get(remaining.get(1));

    if(outPath.get() == null) outPath.set(Paths.get("remap".equals(action) ? remaining.get(0).substring(0, remaining.get(0).lastIndexOf('.')) + "-deobf.jar" : "out.frg"));

    try {
      MappingsHandler frgProvider = MappingsHandlers.findHandler("frg");
      MappingsHandler firstProv = MappingsHandlers.findFileHandler(mappingsPath.toString());
      switch(action) {
        case "cleanMappings":
          frgProvider.writeMappings(firstProv.parseMappings(inputPath).clean(), mappingsPath);
          break;
        case "genMediatorMappings":
          Mappings a2b = firstProv.parseMappings(inputPath);
          Mappings a2c = MappingsHandlers.findFileHandler(mappingsPath.toString()).parseMappings(mappingsPath);
          frgProvider.writeMappings(a2b.generateMediatorMappings(a2c), outPath.get());
          break;
        case "genConversionMappings":
          a2b = firstProv.parseMappings(inputPath);
          Mappings b2c = MappingsHandlers.findFileHandler(mappingsPath.toString()).parseMappings(mappingsPath);
          frgProvider.writeMappings(a2b.generateConversionMethods(b2c), outPath.get());
          break;
        case "remap":
          if(outPath.get().equals(inputPath)) {
            System.out.println("The output path must not match the input path.");
            return;
          }
          new Remapper().remapJar(inputPath, mappingsPath, outPath.get(), ignoredPaths, stripBridgeAccess.get());
          break;
        case "genReverseMappings":
          frgProvider.writeMappings(firstProv.parseMappings(inputPath).generateReverseMappings(), mappingsPath);
          break;
        default:
          frgProvider.writeMappings(new MappingsGenerator(supplementaryMappings.get(), jdkPath.get() == null ? new JDKClassProvider() : new JDKClassProvider(jdkPath.get())).generateMappings(inputPath, ignoredPaths), mappingsPath);
          break;
      }
    } catch(IOException e) {
      e.printStackTrace();
    }
  }

  private Set<String> findMethodExceptions(ClassNode cls, String mdName, String mdDesc, Mappings mappings) {
    //Exception found
    if(mappings.hasMethodMapping(cls.name, mdName, mdDesc)) return mappings.getExceptions(cls.name, mdName, mdDesc);
    //Try inheritance
    return findMethodExceptionsRec(cls, mdName, mdDesc, mappings);
  }

  private Set<String> findMethodExceptionsRec(ClassNode cls, String mdName, String mdDesc, Mappings mappings) {
    if(INHERITABLE_METHODS.getOrDefault(cls.name, new HashSet<>(0)).contains(mdName + mdDesc) && mappings.hasMethodMapping(cls.name, mdName, mdDesc)) return mappings.getExceptions(cls.name, mdName, mdDesc);
    Set<String> result;
    if(classNodes.containsKey(cls.superName) && mappings.hasClassMapping(cls.superName) && (result = findMethodExceptionsRec(classNodes.get(cls.superName), mdName, mdDesc, mappings)) != null) return result;
    for(String iface : cls.interfaces) if(classNodes.containsKey(iface) && mappings.hasClassMapping(iface) && (result = findMethodExceptionsRec(classNodes.get(iface), mdName, mdDesc, mappings)) != null) return result;
    return null;
  }

  private String remapMethodName(ClassNode cls, String mdName, String mdDesc, Mappings mappings) {
    if(mdName.equals("<init>") || mdName.equals("<clinit>")) return mdName;
    if(mappings.hasMethodMapping(cls.name, mdName, mdDesc)) return mappings.getMethodName(cls.name, mdName, mdDesc);
    return findMethodMappingRec(cls, mdName, mdDesc, mappings);
  }

  private String findMethodMappingRec(ClassNode cls, String mdName, String mdDesc, Mappings mappings) {
    if(INHERITABLE_METHODS.getOrDefault(cls.name, new HashSet<>(0)).contains(mdName + mdDesc) && mappings.hasMethodMapping(cls.name, mdName, mdDesc)) return mappings.getMethodName(cls.name, mdName, mdDesc);
    String result;
    if(classNodes.containsKey(cls.superName) && !(result = findMethodMappingRec(classNodes.get(cls.superName), mdName, mdDesc, mappings)).equals(mdName)) return result;
    for(String iface : cls.interfaces) if(classNodes.containsKey(iface) && !(result = findMethodMappingRec(classNodes.get(iface), mdName, mdDesc, mappings)).equals(mdName)) return result;
    return mdName;
  }

  private String remapFieldName(ClassNode cls, String fName, String fDesc, Mappings mappings) {
    if(mappings.hasFieldMapping(cls.name, fName, fDesc)) return mappings.getFieldName(cls.name, fName, fDesc);
    return findFieldMappingRec(cls, fName, fDesc, mappings);
  }

  private String findFieldMappingRec(ClassNode cls, String fName, String fDesc, Mappings mappings) {
    if(SUBCLASS_ACCESSIBLE_FIELDS.getOrDefault(cls.name, new HashSet<>(0)).contains(fName + ":" + fDesc) && mappings.hasFieldMapping(cls.name, fName, fDesc)) return mappings.getFieldName(cls.name, fName, fDesc);
    if(classNodes.containsKey(cls.superName)) return findFieldMappingRec(classNodes.get(cls.superName), fName, fDesc, mappings);
    return fName;
  }

  private static boolean isSynthetic(int access) {
    return (access & Opcodes.ACC_SYNTHETIC) == Opcodes.ACC_SYNTHETIC;
  }

  private void remapJar(Path inputPath, Path mappingsPath, Path outputPath, List<String> ignorePaths, boolean stripBridgeAccess) throws IOException {
    classNodes.putAll(parseClasses(inputPath, ignorePaths, 0));
    Mappings mappings = MappingsHandlers.findFileHandler(mappingsPath.toString()).parseMappings(mappingsPath);
    classNodes.values().forEach(node -> {
          node.methods.forEach(mn -> {
            if(stripBridgeAccess && isSynthetic(mn.access) && !Type.getInternalName(Enum.class).equals(node.superName) && (mn.access & Opcodes.ACC_BRIDGE) == Opcodes.ACC_BRIDGE) {
              System.out.println("class " + mappings.getClassName(node.name) + node.interfaces + " contains bridge method " + mn.name + ". It may have been an anonymous class");
              System.out.println("The remapper will now strip the bridge AND synthetic flag. This CAN introduce compile errors later on and it makes regenerification much harder");
              System.out.println("Look into generating the specialized method?");
              mn.access ^= Opcodes.ACC_BRIDGE;
              mn.access ^= Opcodes.ACC_SYNTHETIC;
            }
            if(Util.hasNone(mn.access, Opcodes.ACC_PRIVATE))
              INHERITABLE_METHODS.computeIfAbsent(node.name, s -> new HashSet<>()).add(mn.name + mn.desc);
          });
          node.fields.forEach(fn -> {
            if(Util.hasNone(fn.access, Opcodes.ACC_PRIVATE))
              SUBCLASS_ACCESSIBLE_FIELDS.computeIfAbsent(node.name, s -> new HashSet<>()).add(fn.name + ":" + fn.desc);
          });
        }
    );
    classNodes.values().forEach(n -> {
      n.fields.forEach(f -> {
        f.name = remapFieldName(n, f.name, f.desc, mappings);
        f.desc = mappings.remapDescriptor(f.desc);
      });
      n.methods.forEach(mn -> {
        Set<String> exceptions = findMethodExceptions(n, mn.name, mn.desc, mappings);
        if(exceptions != null && !exceptions.isEmpty()) {
          if(mn.exceptions != null) exceptions.stream().sorted().forEach(mn.exceptions::add);
          else {
            mn.exceptions = new ArrayList<>(exceptions);
            mn.exceptions.sort(Comparator.naturalOrder());
          }
        }
        mn.name = remapMethodName(n, mn.name, mn.desc, mappings);
        mn.desc = mappings.remapDescriptor(mn.desc);
        if(mn.localVariables != null) mn.localVariables.forEach(l -> {
          l.desc = mappings.remapDescriptor(l.desc);
          l.signature = remapSignature(l.signature, mappings);
        });
        if(mn.signature != null) mn.signature = mappings.remapDescriptor(mn.signature);
        mn.tryCatchBlocks.forEach(tcbn->tcbn.type = mappings.getClassName(tcbn.type));
        mn.instructions.forEach(ins -> {
          if(ins instanceof FieldInsnNode) {
            FieldInsnNode fieldNode = (FieldInsnNode) ins;
            if(classNodes.containsKey(fieldNode.owner)) fieldNode.name = remapFieldName(classNodes.get(fieldNode.owner), fieldNode.name, fieldNode.desc, mappings);
            //If we cant go for inheritance (e.g. the target class is outside the remapped classpath), try to directly match the field name
            else if(mappings.hasFieldMapping(fieldNode.owner, fieldNode.name, fieldNode.desc)) fieldNode.name = mappings.getFieldName(fieldNode.owner, fieldNode.name, fieldNode.desc);
            fieldNode.desc = mappings.remapDescriptor(fieldNode.desc);
            if(fieldNode.owner.startsWith("[")) fieldNode.owner = mappings.remapDescriptor(fieldNode.owner);
            else fieldNode.owner = mappings.getClassName(fieldNode.owner);
          }
          if(ins instanceof MethodInsnNode) {
            MethodInsnNode methodNode = (MethodInsnNode) ins;
            methodNode.name = classNodes.containsKey(methodNode.owner) ? remapMethodName(classNodes.get(methodNode.owner), methodNode.name, methodNode.desc, mappings) : methodNode.name;
            if(methodNode.owner.startsWith("[")) methodNode.owner = mappings.remapDescriptor(methodNode.owner);
              //If we cant go for inheritance (e.g. the target class is outside the remapped classpath), try to directly match the method name
            else if(mappings.hasMethodMapping(methodNode.owner, methodNode.name, methodNode.desc)) methodNode.name = mappings.getMethodName(methodNode.owner, methodNode.name, methodNode.desc);
            else methodNode.owner = mappings.getClassName(methodNode.owner);
            methodNode.desc = mappings.remapDescriptor(methodNode.desc);
          }
          if(ins instanceof MultiANewArrayInsnNode) {
            MultiANewArrayInsnNode manaNode = (MultiANewArrayInsnNode) ins;
            manaNode.desc = mappings.remapDescriptor(manaNode.desc);
          }
          if(ins instanceof TypeInsnNode) {
            TypeInsnNode typeNode = (TypeInsnNode) ins;
            typeNode.desc = typeNode.desc.startsWith("[") ? mappings.remapDescriptor(typeNode.desc) : mappings.getClassName(typeNode.desc);
          }
          if(ins instanceof LdcInsnNode) {
            LdcInsnNode ldcInsnNode = (LdcInsnNode) ins;
            if(ldcInsnNode.cst instanceof Type) ldcInsnNode.cst = Type
                .getType(mappings.remapDescriptor(((Type) ldcInsnNode.cst).getDescriptor()));
          }
          if(ins instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode iDIN = (InvokeDynamicInsnNode) ins;
            String delCls = iDIN.desc.substring(iDIN.desc.indexOf(')') + 2, iDIN.desc.length() - 1);
            if(classNodes.containsKey(delCls)) iDIN.name = remapMethodName(classNodes.get(delCls), iDIN.name, iDIN.bsmArgs[0].toString(), mappings); //Works on default MethodHandleLookup
            iDIN.desc = mappings.remapDescriptor(iDIN.desc);
            for(int i = 0; i < iDIN.bsmArgs.length; i++) {
              Object o = iDIN.bsmArgs[i];
              if(o instanceof Type) iDIN.bsmArgs[i] = Type.getType(mappings.remapDescriptor(((Type) o).getDescriptor()));
              if(o instanceof Handle) {
                Handle h = (Handle) o;
                iDIN.bsmArgs[i] = new Handle(h.getTag(), mappings.getClassName(h.getOwner()), remapMethodName(classNodes.get(h.getOwner()), h.getName(), h.getDesc(), mappings), mappings.remapDescriptor(h.getDesc()), h.isInterface());
              }
            }
          }
        });
      });
    });
    Files.write(outputPath, new byte[]{80, 75, 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    try(FileSystem fs = createFS(outputPath)) {
      for(ClassNode n : classNodes.values()) {
        if(n.nestMembers != null) n.nestMembers = n.nestMembers.stream().map(mappings::getClassName).collect(Collectors.toList());
        n.nestHostClass = mappings.getClassName(n.nestHostClass);
        n.name = mappings.getClassName(n.name);
        n.superName = mappings.getClassName(n.superName);
        n.interfaces = n.interfaces.stream().map(mappings::getClassName).collect(Collectors.toList());
        n.innerClasses.forEach(c -> {
          c.name = mappings.getClassName(c.name);
          c.outerName = mappings.getClassName(c.outerName);
          String s = c.name;
          // Fallback for obfuscated classes.
          c.innerName = c.innerName == null ? null : s.contains("$") ? s.substring(s.lastIndexOf('$') + 1) : s.contains("/") ? s.substring(s.lastIndexOf('/') + 1) : s;
        });
        // Enum Switches and anon classes
        if(n.outerClass != null) {
          // Anon Classes
          if(n.outerMethod != null) {
            n.outerMethod = mappings.getMethodName(n.outerClass, n.outerMethod, n.outerMethodDesc);
            n.outerMethodDesc = mappings.remapDescriptor(n.outerMethodDesc);
          }
          n.outerClass = mappings.getClassName(n.outerClass);
        }
        ClassWriter w = new ClassWriter(0);
        n.accept(w);
        if(n.name.contains("/")) Files.createDirectories(fs.getPath(n.name.substring(0, n.name.lastIndexOf('/'))));
        Files.write(fs.getPath(n.name + ".class"), w.toByteArray());
      }
    }
  }

  private String remapSignature(String signature, Mappings mappings) {
    if(signature == null) return null;
    int p = signature.indexOf('<');
    if(p == -1) {
      return mappings.remapDescriptor(signature);
    }
    String remapped = mappings.remapDescriptor(signature.substring(0, p) + ';');
    StringBuilder builder = new StringBuilder();
    builder.append(remapped, 0, remapped.length() -1);
    builder.append('<');
    PrimitiveIterator.OfInt iterator = signature.substring(p + 1, signature.length() - 2).chars().iterator();
    List<Character> currentName = new ArrayList<>();
    boolean inWord = false;
    int depth = 0;
    while(iterator.hasNext()) {
      char c = (char) iterator.nextInt();
      if(c != 'L' && !inWord) {
        //Reference descriptors start with 'L'
        builder.append(c);
        //T{X} is a generic signature, just pull it out
        if(c == 'T') builder.append(iterator.next());
      } else if(c == 'L') {
        inWord = true;
        currentName.add(c);
        // ';' marks the end of a reference type descriptor
        // but should only finish the signature if depth = 0 (bc of stuff like Ljava/util/function/Function<Ltest/A<Ljava/lang/String;>;Ltest/A<[I>;>;)
      } else if(c == ';') {
        currentName.add(c);
        if(depth != 0) continue;
        // deobfuscate the finished signature and append it
        builder.append(remapSignature(toString(currentName), mappings));
        currentName.clear();
        inWord = false;
      } else if(c == '<') {
        depth++;
      } else if(c == '>') {
        depth--;
      } else currentName.add(c);
    }
    return builder.append(">;").toString();
  }
}
