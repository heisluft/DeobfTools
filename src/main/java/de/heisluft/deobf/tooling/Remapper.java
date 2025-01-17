package de.heisluft.deobf.tooling;

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
import java.util.stream.Collectors;

import static de.heisluft.function.FunctionalUtil.thrc;

//TODO: Think about a clever way to restore generic signatures on fields and based on that, methods
//TODO: Come up with an idea on how to restore generic signatures of obfuscated classes with the help of the specialized subclass bridge methods
//The Ultimate Goal would be a Remapper which is smart enough to generate the specialized methods from bridge methods
public class Remapper implements Util {
  //className -> methodName + methodDesc
  private static final Map<String, Set<String>> INHERITABLE_METHODS = new HashMap<>();
  //className -> fieldName + ":" + fieldDesc
  private static final Map<String, Set<String>> SUBCLASS_ACCESSIBLE_FIELDS = new HashMap<>();

  private final Map<String, ClassNode> classNodes = new HashMap<>();

  public static void main(String[] args) {
    if(args.length < 3
        || !(args[0].equals("map") || args[0].equals("genReverseMappings")
        || args[0].equals("remap") || args[0].equals("cleanMappings")
        || args[0].equals("genMediatorMappings") || args[0].equals("genConversionMappings"))) {
      System.out.println("Heislufts Remapping Service version 1.0\n A deobfuscator and mappings generator\n");
      System.out.println("usage: Remapper <task> <input> <mappings> [options]");
      System.out.println("List of valid tasks: ");
      System.out.println("  map:");
      System.out.println("    Generates obfuscation mappings from the <input> jar and writes them to <mappings>.");
      System.out.println("  genReverseMappings:");
      System.out.println("    Generates reverse obfuscation mappings from the <input> mappings and writes them to <mappings>.");
      System.out.println("  remap:");
      System.out.println("    Remaps the <input> jar with the specified <mappings> file and writes it to [output].");
      System.out.println("    If the outputPath option is not specified, it will default to <input>-deobf.jar");
      System.out.println("  cleanMappings:");
      System.out.println("    Writes a clean version of the mappings at <input> to <mapping>");
      System.out.println("  genMediatorMappings:");
      System.out.println("    Writes mappings mapping the output of <input> to the output of <mappings>");
      System.out.println("    to [output]");
      System.out.println("  genConversionMappings:");
      System.out.println("    Writes mappings mapping the input of <input> to the output of <mappings>");
      System.out.println("    to [output]");
      System.out.println("\nAvailable options are:");
      System.out.println("  shorthand         long option                  description");
      System.out.println("  -i pathsToIgnore  --ignorePaths=pathsToIgnore  A List of paths to ignore from the input jar.");
      System.out.println("                                                 Multiple Paths are separated using ; (semicolon).");
      System.out.println("                                                 These Paths are treated as wildcards.");
      System.out.println("                                                 For example, -i /com;/org/unwanted/ would lead the");
      System.out.println("                                                 program to exclude all paths starting with either");
      System.out.println("                                                 '/com' or '/org/unwanted/' eg. '/com/i.class',");
      System.out.println("                                                 '/computer.xml', '/org/unwanted/b.gif'. This option");
      System.out.println("                                                 will be ignored for tasks only operating on mappings");
      System.out.println("\n  -o outputPath      --outputPath=outputPath     Overrides the path where the remapped");
      System.out.println("                                                 jar will be written to. This option will be ignored");
      System.out.println("                                                 for 'map', 'genReverseMappings' and 'cleanMappings'.");
      System.out.println("\n  -s mappingsPath    --supplement=mappingsPath   Valid only for 'map'. Provides supplementary");
      System.out.println("                                                 mappings. For these, no new mappings will be");
      System.out.println("                                                 generated, instead they will directly be merged into");
      System.out.println("                                                 the output mappings file");
      System.out.println("\n  -j jdkPath         --jdkPath=jdkPath            Valid only for 'map'. Path to JDK, used for");
      System.out.println("                                                 inferring exceptions");
      return;
    }
    String action = args[0];
    List<String> ignoredPaths = new ArrayList<>();
    Path outPath = null;
    Path jdkPath = null;
    Mappings supplementaryMappings = null;
    List<String> ignoredOpts = new ArrayList<>(args.length - 3);
    if(args.length > 3) {
      for(int i = 3; i < args.length; i++) {
        String arg = args[i];
        //As we have no flag options, all options require an argument
        if(arg.startsWith("--") && arg.indexOf('=') < 0 || (arg.startsWith("-") && !arg.startsWith("--") && i == args.length-1)) {
          ignoredOpts.add(arg);
          continue;
        }
        if(arg.startsWith("--jdk=") || arg.equals("-j")) {
          if(!action.equals("map")) ignoredOpts.add(arg.equals("-j") ? "-j " + args[++i] : arg);
          else {
            jdkPath = Paths.get(arg.equals("-j") ? args[++i] : arg.substring(arg.indexOf('=') + 1));
            if(!Files.isDirectory(jdkPath)) {
              System.err.println("'" + jdkPath + "' does not point to a regular file");
              return;
            }
          }
          continue;
        }
        if(arg.startsWith("--supplementary=") || arg.equals("-s")) {
          if(!action.equals("map")) ignoredOpts.add(arg.equals("-s") ? "-s " + args[++i] : arg);
          else {
            Path sPath = Paths.get(arg.equals("-s") ? args[++i] : arg.substring(arg.indexOf('=') + 1));
            if(!Files.isRegularFile(sPath)) {
              System.err.println("'" + sPath + "' does not point to a regular file");
              return;
            }
            try {
              supplementaryMappings = MappingsHandlers.findFileHandler(sPath.getFileName().toString()).parseMappings(sPath);
            } catch (IOException e) {
              System.err.println("Could not parse supplementary mappings!");
              e.printStackTrace();
              return;
            }
          }
          continue;
        }
        if((arg.startsWith("--outputPath=") || arg.equals("-o"))) {
          if(!(action.equals("remap") || action.equals("genMediatorMappings") || action.equals("genConversionMappings")) || outPath != null || arg.contains("=") && arg.split("=", 2)[1].isEmpty())
            ignoredOpts.add(arg.equals("-o") ? "-o " + args[++i] : arg);
          else outPath = Paths.get(arg.equals("-o") ? args[++i] : arg.substring(arg.indexOf('=') + 1));
          continue;
        }
        if(arg.startsWith("--ignorePaths") || arg.equals("-i")) {
          if(!(action.equals("remap") || action.equals("map")) || !ignoredPaths.isEmpty() || arg.contains("=") && arg.split("=", 2)[1].isEmpty())
            ignoredOpts.add(arg.equals("-i") ? "-i " + args[++i] : arg);
          else ignoredPaths.addAll(Arrays.asList((arg.equals("-i") ? args[++i] : arg.substring(arg.indexOf('=') + 1)).split(";")));
          continue;
        }
        ignoredOpts.add(arg.startsWith("-") ? arg + " " + args[++i] : arg);
      }
    }
    if(action.equals("remap")) {
      if(args.length > 3 && args[3].equals(args[1])) {
        System.out.println("The output path must not match the input path.");
        return;
      }
      if(outPath == null) outPath = Paths.get(args[1].substring(0, args[1].lastIndexOf('.')) + "-deobf.jar");
    }
    if(!ignoredOpts.isEmpty()) System.out.println("ignored options: " + ignoredOpts);
    try {
      Path inputPath = Paths.get(args[1]);
      Path mappingsPath = Paths.get(args[2]);
      MappingsHandler frgProvider = MappingsHandlers.findHandler("frg");
      MappingsHandler firstProv = MappingsHandlers.findFileHandler(args[1]);
      switch(action) {
        case "cleanMappings":
          frgProvider.writeMappings(firstProv.parseMappings(inputPath).clean(), mappingsPath);
          break;
        case "genMediatorMappings":
          Mappings a2b = firstProv.parseMappings(inputPath);
          Mappings a2c = MappingsHandlers.findFileHandler(args[2]).parseMappings(mappingsPath);
          frgProvider.writeMappings(a2b.generateMediatorMappings(a2c), outPath);
          break;
        case "genConversionMappings":
          a2b = firstProv.parseMappings(inputPath);
          Mappings b2c = MappingsHandlers.findFileHandler(args[2]).parseMappings(mappingsPath);
          frgProvider.writeMappings(a2b.generateConversionMethods(b2c), outPath);
          break;
        case "remap":
          new Remapper().remapJar(inputPath, mappingsPath, outPath, ignoredPaths);
          break;
        case "genReverseMappings":
          frgProvider.writeMappings(firstProv.parseMappings(inputPath).generateReverseMappings(), mappingsPath);
          break;
        default:
          frgProvider.writeMappings(new MappingsGenerator(supplementaryMappings, jdkPath == null ? new JDKClassProvider() : new JDKClassProvider(jdkPath)).generateMappings(inputPath, ignoredPaths), mappingsPath);
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

  private void remapJar(Path inputPath, Path mappingsPath, Path outputPath, List<String> ignorePaths) throws IOException {
    classNodes.putAll(parseClasses(inputPath, ignorePaths, 0));
    Mappings mappings = MappingsHandlers.findFileHandler(mappingsPath.toString()).parseMappings(mappingsPath);
    classNodes.values().forEach(node -> {
          node.methods.forEach(mn -> {
            if(isSynthetic(mn.access) && !Type.getInternalName(Enum.class).equals(node.superName) && (mn.access & Opcodes.ACC_BRIDGE) == Opcodes.ACC_BRIDGE) {
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
    classNodes.values().forEach(thrc(n -> {
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
            fieldNode.desc = mappings.remapDescriptor(fieldNode.desc);
            if(fieldNode.owner.startsWith("[")) fieldNode.owner = mappings.remapDescriptor(fieldNode.owner);
            else fieldNode.owner = mappings.getClassName(fieldNode.owner);
          }
          if(ins instanceof MethodInsnNode) {
            MethodInsnNode methodNode = (MethodInsnNode) ins;
            methodNode.name = classNodes.containsKey(methodNode.owner) ? remapMethodName(classNodes.get(methodNode.owner), methodNode.name, methodNode.desc, mappings) : methodNode.name;
            if(methodNode.owner.startsWith("[")) methodNode.owner = mappings.remapDescriptor(methodNode.owner);
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
    }));
    Files.write(outputPath, new byte[]{80, 75, 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    try(FileSystem fs = createFS(outputPath)) {
      classNodes.values().forEach(thrc(n -> {
        if(n.nestMembers != null) n.nestMembers = n.nestMembers.stream().map(mappings::getClassName).collect(Collectors.toList());
        n.nestHostClass = mappings.getClassName(n.nestHostClass);
        n.name = mappings.getClassName(n.name);
        n.superName =  mappings.getClassName(n.superName);
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
      }));
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
