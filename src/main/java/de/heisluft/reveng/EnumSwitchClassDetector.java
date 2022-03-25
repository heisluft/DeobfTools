package de.heisluft.reveng;

import de.heisluft.function.Tuple2;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnList;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class EnumSwitchClassDetector implements Util {
  public static void main(String[] args) throws IOException {
    if(args.length < 2) {
      System.out.println("Usage EnumSwitchClassDetector <input> <output>");
      return;
    }
    new EnumSwitchClassDetector().restoreMeta(Paths.get(args[0]), Paths.get(args[1]));
  }

  private boolean containsFieldGet(InsnList list, String cName, String fName) {
    for(int i = 0; i < list.size(); i++) {
      AbstractInsnNode ain = list.get(i);
      if(ain.getOpcode() != Opcodes.GETSTATIC) continue;
      FieldInsnNode fin = ((FieldInsnNode) ain);
      if(!fin.owner.equals(cName)) continue;
      if(fin.name.equals(fName)) return true;
    }
    return false;
  }

  private void restoreMeta(Path p, Path out) throws IOException {
    Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
    Map<String, ClassNode> nodeCache = parseClasses(out);
    Set<Tuple2<String, String>> candidates = new HashSet<>();
    Set<String> dirtyClasses = new HashSet<>();
    nodeCache.values().stream().filter(cn -> (cn.access & Opcodes.ACC_SYNTHETIC) != 0 && cn.fields.size() == 1 && "[I".equals(cn.fields.get(0).desc)).forEach(n -> candidates.add(new Tuple2<>(n.name, n.fields.get(0).name)));
    Map<String, Set<String>> uses = new HashMap<>();
    nodeCache.values().forEach(cn ->
        cn.methods.forEach(mn ->
            candidates.stream().filter(c -> !cn.name.equals(c._1) && containsFieldGet(mn.instructions, c._1, c._2)
            ).forEach(candidate ->
                getOrPut(uses, candidate._1, new HashSet<>()).add(cn.name)
            )
        )
    );
    uses.forEach((syn, used) -> {
      if(used.size() != 1) return;
      ClassNode useNode = nodeCache.get(used.iterator().next());
      ClassNode synNode = nodeCache.get(syn);
      InnerClassNode nestDesc = new InnerClassNode(syn, null, null, synNode.access);
      System.out.println("making " + syn + " a nested class of " + useNode.name);
      useNode.innerClasses.add(nestDesc);
      synNode.innerClasses.add(nestDesc);
      dirtyClasses.add(useNode.name);
      dirtyClasses.add(syn);
    });
    if(dirtyClasses.isEmpty()) return;
    try(FileSystem fs = createFS(out)) {
      Path root = fs.getPath("/");
      for(String d : dirtyClasses) {
        ClassWriter writer = new ClassWriter(0);
        nodeCache.get(d).accept(writer);
        Files.write(root.resolve(d + ".class"), writer.toByteArray());
      }
    }
  }
}
