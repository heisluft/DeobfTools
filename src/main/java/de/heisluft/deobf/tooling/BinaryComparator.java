package de.heisluft.deobf.tooling;

import de.heisluft.deobf.mappings.MappingsBuilder;
import de.heisluft.deobf.mappings.MappingsHandlers;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

public class BinaryComparator implements Util {

  private void writeMemberMappings(Path cmp1, Path cmp2, Path cMappings, Path out) throws IOException {
    MappingsBuilder mb = new MappingsBuilder(MappingsHandlers.parseMappings(cMappings));
    Map<String, ClassNode> cmp1Classes = parseClasses(cmp1);
    Map<String, ClassNode> cmp2Classes = parseClasses(cmp2);
    cmp1Classes.forEach((name,classNode) -> {
      if(!cmp2Classes.containsKey(name)){
        System.out.println("class " + name + " not found, skipping");
        return;
      }
      ClassNode cmpClass = cmp2Classes.get(name);
      List<FieldNode> fields = new ArrayList<>(classNode.fields);
      classNode.fields.forEach(field -> {
        List<FieldNode> matchedNodes = cmpClass.fields.stream().filter(fn -> fn.desc.equals(field.desc)).collect(Collectors.toList());
        if(!fields.contains(field)) return;
        if(matchedNodes.isEmpty()) {
          System.out.println("field " + name + "#" + field.name + " " + field.desc + " not found, skipping");
          fields.remove(field);
          return;
        }
        if(matchedNodes.size() == 1) {
          mb.addFieldMapping(name, field.name, field.desc, matchedNodes.get(0).name);
          fields.remove(field);
        }
        List<FieldNode> sameFields = classNode.fields.stream().filter(fn -> fn.desc.equals(field.desc)).collect(Collectors.toList());
        if(sameFields.size() == matchedNodes.size()) {
          System.out.println("fields of type " + field.desc + " will be renamed in sequence.");
          for(int i = 0; i < sameFields.size(); i++) mb.addFieldMapping(name, sameFields.get(i).name, sameFields.get(i).desc, matchedNodes.get(i).name);
          fields.removeAll(sameFields);
        }
      });
      if(!fields.isEmpty()) System.out.println(name + ": " + fields.size());
    });
    MappingsHandlers.writeMappings(mb.build(), out);
  }

  public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
    new BinaryComparator().writeMemberMappings(
        Paths.get("b1.2_01-server.jar"),
        Paths.get("remap-tests/jars/mc/client/beta/b1.2_02-20110517.jar"),
        Paths.get("remap-tests/frg/b1.2_01-server.frg"),
        Paths.get("out.frg")
    );
  }
}
