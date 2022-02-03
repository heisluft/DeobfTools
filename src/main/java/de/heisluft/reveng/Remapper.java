package de.heisluft.reveng;

import de.heisluft.reveng.mappings.MappingsInterface;
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

import static de.heisluft.function.FunctionalUtil.*;

//TODO: Remapping of innerClass field, inferring of inner classes will happen before remapping!
//TODO: Don't emit mappings for anonymous classes
//TODO: Renaming of enum value field
//TODO: Think about a clever way to restore generic signatures on fields and based on that, methods
//TODO: Come up with an idea on how to restore generic signatures of obfuscated classes with the help of the specialized subclass bridge methods
//The Ultimate Goal would be a remapper which is smart enough to generate the specialized methods from bridge methods and maybe even inferring checked exceptions.
public class Remapper implements Util {
  /**All Primitive Names*/
  private static final List<String> PRIMITIVES = Arrays.asList("B", "C", "D", "F", "I", "J", "S", "V", "Z");

  private static final int FRG_MAPPING_TYPE_INDEX = 0;
  private static final int FRG_ENTITY_CLASS_NAME_INDEX = 1;
  private static final int FRG_MAPPED_CLASS_NAME_INDEX = 2;
  private static final int FRG_ENTITY_NAME_INDEX = 2;
  private static final int FRG_MAPPED_FIELD_NAME_INDEX = 3;
  private static final int FRG_METHOD_DESCRIPTOR_INDEX = 3;
  private static final int FRG_MAPPED_METHOD_NAME_INDEX = 4;
  //className -> methodName + methodDesc
  private static final Map<String, Set<String>> INHERITABLE_METHODS = new HashMap<>();
  //className -> fieldName + ":" + fieldDesc
  private static final Map<String, Set<String>> SUBCLASS_ACCESSIBLE_FIELDS = new HashMap<>();

  private static final List<String> EMPTY = Collections.emptyList();

  private final Map<String, String> classMappings = new HashMap<>();
  //className -> fieldName -> remappedName
  private final Map<String, Map<String, String>> fieldMappings = new HashMap<>();
  //className -> methodName + methodDesc -> remappedName
  private final Map<String, Map<String, String>> mdMappings = new HashMap<>();
  //className + methodName + methodDesc -> list of exceptions to add (can be obf.)
  private final Map<String, List<String>> exceptions = new HashMap<>();
  private final Map<String, ClassNode> classNodes = new HashMap<>();
  private final Path mappingsPath, inputPath;

  private Remapper(Path inputPath, Path mappingsPath, boolean buildClassTree, List<String> ignorePaths) throws IOException {
    this.inputPath = inputPath;
    this.mappingsPath = mappingsPath;
    if(buildClassTree) try(FileSystem fs = createFS(inputPath)) {
      Files.walk(fs.getPath("/")).filter(path -> path.toString().endsWith(".class") && ignorePaths.stream().noneMatch(s -> path.toString().startsWith(s))).map(thr(this::parseClass)).forEach(c -> classNodes.put(c.name, c));
    }
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

  public static void main(String[] args) {
    if(args.length < 3 || !(args[0].equals("map") || args[0].equals("genReverseMappings") || args[0].equals("remap"))) {
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
      System.out.println("\nAvailable options are:");
      System.out.println("  shorthand         long option                  description");
      System.out.println("  -i pathsToIgnore  --ignorePaths=pathsToIgnore  A List of paths to ignore from the input jar.");
      System.out.println("                                                 Multiple Paths are separated using ; (semicolon).");
      System.out.println("                                                 These Paths are treated as wildcards.");
      System.out.println("                                                 For example, -i /com;/org/unwanted/ would lead the");
      System.out.println("                                                 program to exclude all paths starting with either");
      System.out.println("                                                 '/com' or '/org/unwanted/' eg. '/com/i.class',");
      System.out.println("                                                 '/computer.xml', '/org/unwanted/b.gif'. This option");
      System.out.println("                                                 will be ignored for the 'genReverseMappings' task.");
      System.out.println("\n  -o outputPath    --outputPath=outputPath       Overrides the path where the remapped");
      System.out.println("                                                 jar will be written to. This option will be ignored");
      System.out.println("                                                 for tasks other than 'remap'.");
      return;
    }
    String action = args[0];
    List<String> ignoredPaths = new ArrayList<>();
    Path outPath = null;
    List<String> ignoredOpts = new ArrayList<>(args.length - 3);
    if(args.length > 3) {
      for(int i = 3; i < args.length; i++) {
        String arg = args[i];
        //As we have no flag options, all options require an argument
        if(arg.startsWith("--") && arg.indexOf('=') < 0 || arg.startsWith("-") && i == args.length-1) {
          ignoredOpts.add(arg);
          continue;
        }
        if((arg.startsWith("--outputPath=") || arg.equals("-o"))) {
          if(!action.equals("remap") || outPath != null || arg.contains("=") && arg.split("=", 2)[1].isEmpty()) ignoredOpts.add(arg.equals("-o") ? "-o " + args[++i] : arg);
          else outPath = Paths.get(arg.equals("-o") ? args[++i] : arg.split("=", 2)[1]);
          continue;
        }
        if(arg.startsWith("--ignorePaths") || arg.equals("-i")) {
          if(action.equals("genReverseMappings") || !ignoredPaths.isEmpty() || arg.contains("=") && arg.split("=", 2)[1].isEmpty()) ignoredOpts.add(arg.equals("-i") ? "-i " + args[++i] : arg);
          else ignoredPaths.addAll(Arrays.asList((arg.equals("-i") ? args[++i] : arg.split("=", 2)[1]).split(";")));
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
      Remapper remapper = new Remapper(Paths.get(args[1]), Paths.get(args[2]), action.equals("remap"), ignoredPaths);
      switch(action) {
        case "remap":
          remapper.remapJar(outPath);
          break;
        case "genReverseMappings":
          remapper.buildReverseMappings();
          break;
        default:
          MappingsInterface.writeFergieMappings(MappingsInterface.generateMappings(Paths.get(args[1]), ignoredPaths), Paths.get(args[2]));
          break;
      }
    } catch(IOException e) {
      e.printStackTrace();
    }
  }

  private void readMappingsFile() throws IOException {
    Files.readAllLines(mappingsPath).stream().map(line -> line.split(" ")).forEach(line -> {
      if("MD:".equals(line[FRG_MAPPING_TYPE_INDEX])) {
        if(line.length < 5) throw new IllegalArgumentException("Line too short (" + join(line) + ")");
        String clsName = line[FRG_ENTITY_CLASS_NAME_INDEX];
        String obfName = line[FRG_ENTITY_NAME_INDEX];
        String obfDesc = line[FRG_METHOD_DESCRIPTOR_INDEX];
        mdMappings.computeIfAbsent(clsName, s -> new HashMap<>()).put(obfName + obfDesc, line[FRG_MAPPED_METHOD_NAME_INDEX]);
        for(int i = 5; i < line.length; i++)
          exceptions.computeIfAbsent(clsName + obfName + obfDesc, s -> new ArrayList<>()).add(line[i]);
      } else if("FD:".equals(line[FRG_MAPPING_TYPE_INDEX]))
        fieldMappings.computeIfAbsent(line[FRG_ENTITY_CLASS_NAME_INDEX], s -> new HashMap<>()).put(line[FRG_ENTITY_NAME_INDEX], line[FRG_MAPPED_FIELD_NAME_INDEX]);
      else if("CL:".equals(line[FRG_MAPPING_TYPE_INDEX])) classMappings.put(line[FRG_ENTITY_CLASS_NAME_INDEX], line[FRG_MAPPED_CLASS_NAME_INDEX]);
      else {
        System.out.print("Not operating on line '" + join(line) + "'!");
      }
    });
  }

  private void buildReverseMappings() throws IOException {
    List<String> inputLines = Files.readAllLines(inputPath);
    List<String> revLines = new ArrayList<>(inputLines.size());
    inputLines.stream().map(line -> line.split(" ")).forEach(line -> {
      if("MD:".equals(line[FRG_MAPPING_TYPE_INDEX])) {
        StringBuilder revLine = new StringBuilder( "MD: ");
        revLine.append(classMappings.get(line[FRG_ENTITY_CLASS_NAME_INDEX]));
        revLine.append(" ").append(line[FRG_MAPPED_METHOD_NAME_INDEX]);
        revLine.append(" ").append(remapDescriptor(line[FRG_METHOD_DESCRIPTOR_INDEX]));
        revLine.append(" ").append(line[FRG_ENTITY_NAME_INDEX]);
        for(int i = 5; i < line.length; i++) revLine.append(" !").append(
            classMappings.getOrDefault(line[i], line[i]));
        revLines.add(revLine.toString());
      } else if("FD:".equals(line[FRG_MAPPING_TYPE_INDEX])) {
        revLines.add("FD: " + classMappings.get(line[FRG_ENTITY_CLASS_NAME_INDEX]) + " " + line[FRG_MAPPED_FIELD_NAME_INDEX] + " " +line[FRG_ENTITY_NAME_INDEX]);
      }
      else if("CL:".equals(line[FRG_MAPPING_TYPE_INDEX])) {
        revLines.add("CL: " + line[2] + " " + line[1]);
        classMappings.put(line[1], line[2]);
      }
      else {
        System.out.print("Not operating on line '");
        for(int i = 0; i < line.length - 1; i++) System.out.print(line[i] + " ");
        System.out.print(line[line.length - 1]);
        System.out.println("'");
      }
    });
    Files.write(mappingsPath, revLines);
  }

  private List<String> findMethodExceptions(ClassNode cls, String mdName, String mdDesc) {
    //Exception found
    if(exceptions.containsKey(cls.name + mdName + mdDesc)) return exceptions.get(cls.name + mdName + mdDesc);
    //Mapping present, but lists no exceptions
    if(mdMappings.getOrDefault(cls.name, new HashMap<>()).containsKey(mdName + mdDesc)) return null;
    //Try inheritance
    return findMethodExceptionsRec(cls, mdName, mdDesc);
  }

  private List<String> findMethodExceptionsRec(ClassNode cls, String mdName, String mdDesc) {
    if(INHERITABLE_METHODS.getOrDefault(cls.name, new HashSet<>(0)).contains(mdName + mdDesc) && mdMappings.getOrDefault(cls.name, new HashMap<>()).containsKey(mdName + mdDesc)) return exceptions.getOrDefault(cls.name + mdName + mdDesc, EMPTY);
    List<String> result;
    if(classNodes.containsKey(cls.superName) && (result = findMethodExceptionsRec(classNodes.get(cls.superName), mdName, mdDesc)) != null) return result;
    for(String iface : cls.interfaces) if(classNodes.containsKey(iface) && (result = findMethodExceptionsRec(classNodes.get(iface), mdName, mdDesc)) != null) return result;
    return null;
  }

  private String remapMethodName(ClassNode cls, String mdName, String mdDesc) {
    if(mdName.equals("<init>") || mdName.equals("<clinit>")) return mdName;
    if(mdMappings.getOrDefault(cls.name, new HashMap<>(0)).containsKey(mdName + mdDesc)) return mdMappings.get(cls.name).get(mdName + mdDesc);
    return findMethodMappingRec(cls, mdName, mdDesc);
  }

  private String findMethodMappingRec(ClassNode cls, String mdName, String mdDesc) {
    if(INHERITABLE_METHODS.getOrDefault(cls.name, new HashSet<>(0)).contains(mdName + mdDesc) && mdMappings.getOrDefault(cls.name, new HashMap<>()).containsKey(mdName + mdDesc)) return mdMappings.get(cls.name).get(mdName + mdDesc);
    String result;
    if(classNodes.containsKey(cls.superName) && !(result = findMethodMappingRec(classNodes.get(cls.superName), mdName, mdDesc)).equals(mdName)) return result;
    for(String iface : cls.interfaces) if(classNodes.containsKey(iface) && !(result = findMethodMappingRec(classNodes.get(iface), mdName, mdDesc)).equals(mdName)) return result;
    return mdName;
  }

  private String remapFieldName(ClassNode cls, String fName, String fDesc) {
    if(fieldMappings.getOrDefault(cls.name, new HashMap<>()).containsKey(fName)) return fieldMappings.get(cls.name).get(fName);
    return findFieldMappingRec(cls, fName, fDesc);
  }

  private String findFieldMappingRec(ClassNode cls, String fName, String fDesc) {
    if(SUBCLASS_ACCESSIBLE_FIELDS.getOrDefault(cls.name, new HashSet<>(0)).contains(fName + ":" + fDesc) && fieldMappings.getOrDefault(cls.name, new HashMap<>()).containsKey(fName)) return fieldMappings.get(cls.name).get(fName);
    if(classNodes.containsKey(cls.superName)) return findFieldMappingRec(classNodes.get(cls.superName), fName, fDesc);
    return fName;
  }

  static boolean isSynthetic(int access) {
    return (access & Opcodes.ACC_SYNTHETIC) == Opcodes.ACC_SYNTHETIC;
  }

  private void remapJar(Path outputPath) throws IOException {
    Files.write(outputPath, new byte[] {0x50,0x4B,0x05,0x06,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00});
    readMappingsFile();
    List<String> anonymousClassCandidates = classNodes.values().stream().filter(
        node -> ((!node.fields.isEmpty() && node.fields.stream().map(f -> f.access).allMatch(Remapper::isSynthetic))
            || (node.interfaces.size() == 1 && node.methods.stream().allMatch(m -> !"<init>".equals(m.name) && !"<clinit>".equals(m.name) && isSynthetic(m.access)))))
        .map(c -> c.name).collect(Collectors.toList());
    classNodes.values().forEach(node -> {
          node.methods.forEach(mn -> {
            if(isSynthetic(mn.access) && !Type.getInternalName(Enum.class).equals(node.superName) && (mn.access & Opcodes.ACC_BRIDGE) == Opcodes.ACC_BRIDGE) {
              System.out.println("class " + classMappings.get(node.name) + node.interfaces + " contains bridge method " + mn.name + ". It may have been an anonymous class");
              System.out.println("The remapper will now strip the bridge AND synthetic flag. This CAN introduce compile errors later on and it makes regenerification much harder");
              System.out.println("Look into generating the specialized method?");
              mn.access ^= Opcodes.ACC_BRIDGE;
              mn.access ^= Opcodes.ACC_SYNTHETIC;
            }
            if(hasNone(mn.access, Opcodes.ACC_PRIVATE))
              INHERITABLE_METHODS.computeIfAbsent(node.name, s -> new HashSet<>()).add(mn.name + mn.desc);
          });
          node.fields.forEach(fn -> {
            if(hasNone(fn.access, Opcodes.ACC_PRIVATE))
              SUBCLASS_ACCESSIBLE_FIELDS.computeIfAbsent(node.name, s -> new HashSet<>()).add(fn.name + ":" + fn.desc);
          });
        }
    );
    classNodes.values().forEach(thrc(n -> {
      n.fields.forEach(f -> {
        f.name = remapFieldName(n, f.name, f.desc);
        f.desc = remapDescriptor(f.desc);
      });
      n.methods.forEach(mn -> {
        List<String> exceptions = findMethodExceptions(n, mn.name, mn.desc);
        if(exceptions != null && !exceptions.isEmpty()) {
          if(mn.exceptions != null) mn.exceptions.addAll(exceptions);
          else mn.exceptions = new ArrayList<>(exceptions);
        }
        mn.name = remapMethodName(n, mn.name, mn.desc);
        mn.desc = remapDescriptor(mn.desc);
        if(mn.localVariables != null) mn.localVariables.forEach(l -> {
          l.desc = remapDescriptor(l.desc);
          l.signature = remapSignature(l.signature);
        });
        if(mn.signature != null) mn.signature = remapDescriptor(mn.signature);
        mn.tryCatchBlocks.forEach(tcbn->tcbn.type = classMappings.getOrDefault(tcbn.type, tcbn.type));
        mn.instructions.forEach(ins -> {
          if(ins instanceof FieldInsnNode) {
            FieldInsnNode fieldNode = (FieldInsnNode) ins;
            if(classNodes.containsKey(fieldNode.owner)) fieldNode.name = remapFieldName(classNodes.get(fieldNode.owner), fieldNode.name, fieldNode.desc);
            fieldNode.desc = remapDescriptor(fieldNode.desc);
            if(fieldNode.owner.startsWith("[")) fieldNode.owner = remapDescriptor(fieldNode.owner);
            else fieldNode.owner = classMappings.getOrDefault(fieldNode.owner, fieldNode.owner);
          }
          if(ins instanceof MethodInsnNode) {
            MethodInsnNode methodNode = (MethodInsnNode) ins;
            methodNode.name = classNodes.containsKey(methodNode.owner) ? remapMethodName(classNodes.get(methodNode.owner), methodNode.name, methodNode.desc) : methodNode.name;
            if(methodNode.owner.startsWith("[")) methodNode.owner = remapDescriptor(methodNode.owner);
            else methodNode.owner = classMappings.getOrDefault(methodNode.owner, methodNode.owner);
            methodNode.desc = remapDescriptor(methodNode.desc);
          }
          if(ins instanceof MultiANewArrayInsnNode) {
            MultiANewArrayInsnNode manaNode = (MultiANewArrayInsnNode) ins;
            manaNode.desc = remapDescriptor(manaNode.desc);
          }
          if(ins instanceof TypeInsnNode) {
            TypeInsnNode typeNode = (TypeInsnNode) ins;
            if(typeNode.getOpcode() == Opcodes.NEW && anonymousClassCandidates.contains(typeNode.desc)) {
              String outerMethodName = remapMethodName(n, mn.name, mn.desc);
              String outerMethodDesc = remapDescriptor(mn.desc);
              String outerClassName = classMappings.getOrDefault(n.name, n.name);
              System.out.println(classMappings.getOrDefault(typeNode.desc, typeNode.desc) + " was likely an anonymous class in method " + outerMethodName + outerMethodDesc + " of class " + outerClassName);
              System.out.println("Automatic Reconstruction is not yet finished");
              ClassNode anonClass = classNodes.get(typeNode.desc);
              //anonClass.outerMethodDesc = outerMethodDesc;
              //anonClass.outerMethod = outerMethodName;
              //anonClass.outerClass = outerClassName;
            }
            typeNode.desc = typeNode.desc.startsWith("[") ? remapDescriptor(typeNode.desc) : classMappings
                .getOrDefault(typeNode.desc, typeNode.desc);
          }
          if(ins instanceof LdcInsnNode) {
            LdcInsnNode ldcInsnNode = (LdcInsnNode) ins;
            if(ldcInsnNode.cst instanceof Type) ldcInsnNode.cst = Type
                .getType(remapDescriptor(((Type) ldcInsnNode.cst).getDescriptor()));
          }
          if(ins instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode iDIN = (InvokeDynamicInsnNode) ins;
            String delCls = iDIN.desc.substring(iDIN.desc.indexOf(')') + 2, iDIN.desc.length() - 1);
            if(classNodes.containsKey(delCls)) iDIN.name = remapMethodName(classNodes.get(delCls), iDIN.name, iDIN.bsmArgs[0].toString()); //Works on default MethodHandleLookup
            iDIN.desc = remapDescriptor(iDIN.desc);
            for(int i = 0; i < iDIN.bsmArgs.length; i++) {
              Object o = iDIN.bsmArgs[i];
              if(o instanceof Type) iDIN.bsmArgs[i] = Type.getType(remapDescriptor(((Type) o).getDescriptor()));
              if(o instanceof Handle) {
                Handle h = (Handle) o;
                iDIN.bsmArgs[i] = new Handle(h.getTag(), classMappings.getOrDefault(h.getOwner(), h.getOwner()), remapMethodName(classNodes.get(h.getOwner()), h.getName(), h.getDesc()), remapDescriptor(h.getDesc()), h.isInterface());
              }
            }
          }
        });
      });
    }));
    Files.write(outputPath, new byte[]{80, 75, 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    try(FileSystem fs = createFS(outputPath)) {
      classNodes.values().forEach(thrc(n -> {
        if(n.nestMembers != null) n.nestMembers = n.nestMembers.stream().map(nestMbr -> classMappings.getOrDefault(nestMbr, nestMbr)).collect(Collectors.toList());
        n.nestHostClass = classMappings.getOrDefault(n.nestHostClass, n.nestHostClass);
        n.name = classMappings.getOrDefault(n.name, n.name);
        n.superName = classMappings.getOrDefault(n.superName, n.superName);
        n.interfaces = n.interfaces.stream().map(i -> classMappings.getOrDefault(i, i)).collect(Collectors.toList());
        n.innerClasses.forEach(c -> {
          c.innerName = classMappings.getOrDefault(c.innerName, c.innerName);
          c.outerName = classMappings.getOrDefault(c.outerName, c.outerName);
          c.name = classMappings.getOrDefault(c.name, c.name);
        });
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        n.accept(w);
        if(n.name.contains("/")) Files.createDirectories(fs.getPath(n.name.substring(0, n.name.lastIndexOf('/'))));
        Files.write(fs.getPath(n.name + ".class"), w.toByteArray());
      }));
    }
  }

  private String remapSignature(String signature) {
    if(signature == null) return null;
    int p = signature.indexOf('<');
    if(p == -1) {
      return remapDescriptor(signature);
    }
    String remapped = remapDescriptor(signature.substring(0, p) + ';');
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
        builder.append(remapSignature(toString(currentName)));
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

  /**
   * Remaps a given descriptor with the currently loaded mappings
   * @param descriptor the descriptor to remap
   * @return the remapped descriptor
   */
  private String remapDescriptor(String descriptor) {
    StringBuilder result = new StringBuilder();
    //Method descriptors start with '('
    if(descriptor.startsWith("(")) {
      // split String at ')',
      // example descriptor "(J[Ljava/lang/String;S)[I" -> ["(J[Ljava/lang/String;S", "[I"]
      String[] split = descriptor.split("\\)");
      // "(J[Ljava/lang/String;S" -> "J[Ljava/lang/String;S"
      String argsDescriptor = split[0].substring(1);
      if(argsDescriptor.isEmpty()) result.append("()");
      else {
        result.append("(");
        //Parse chars LTR
        PrimitiveIterator.OfInt iterator = argsDescriptor.chars().iterator();
        List<Character> currentName = new ArrayList<>();
        boolean inWord = false;
        while(iterator.hasNext()) {
          char c = (char) iterator.nextInt();
          if(c != 'L' && !inWord) {
            result.append(c);
            //Reference descriptors start with 'L'
          } else if(c == 'L') {
            inWord = true;
            currentName.add(c);
            // ';' marks the end of a reference type descriptor
          } else if(c == ';') {
            currentName.add(c);
            // deobfuscate the finished descriptor and append it
            result.append(remapDescriptor(toString(currentName)));
            currentName.clear();
            inWord = false;
          } else currentName.add(c);
        }
        result.append(')');
      }
      //descriptor becomes the return type descriptor e.g. "(J[Ljava/lang/String;S)[I" -> [I
      descriptor = split[1];
    }
    //Copy descriptor so e.g simple [I descs can be returned easily
    String cpy = descriptor;
    // strip arrays, count the dimensions for later
    int arrDim = 0;
    while(cpy.startsWith("[")) {
      arrDim++;
      cpy = cpy.substring(1);
    }
    // primitives don't need to be deobfed
    if(PRIMITIVES.contains(cpy)) return result.toString() + descriptor;
    // Strip L and ; for lookup (Lmy/package/Class; -> my/package/Class)
    cpy = cpy.substring(1, cpy.length() - 1);
    // the mappings do not contain the class, no deobfuscation needed (e.g. java/lang/String...)
    if(!classMappings.containsKey(cpy)) return result.toString() + descriptor;
    //prepend the array dimensions if any
    for(int i = 0; i < arrDim; i++) result.append('[');
    //convert deobfed class name to descriptor (my/deobfed/ClassName -> Lmy/deobfed/ClassName;)
    return result.append('L').append(classMappings.get(cpy)).append(';').toString();
  }
}
