package de.heisluft.deobf.tooling;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ParameterNode;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.stream.Stream;

import static de.heisluft.function.FunctionalUtil.*;

public class Parametizer implements Util {

  private Parametizer(Path inputPath, Path mappingsPath, Path outputPath) throws IOException {
    List<String> lines = new ArrayList<>();
    Files.copy(inputPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
    try(FileSystem system = createFS(outputPath)) {
      Files.walk(system.getPath("/")).filter(p -> hasClassExt(p) && Stream.of("/paulscode", "/com/jcraft").noneMatch(s -> p.toString().startsWith(s))).map(thr(this::parseClass)).forEach(cn -> {
        cn.methods.forEach(mn -> {
          List<String> paramNames = nameParams(mn.desc);
          List<ParameterNode> params = new ArrayList<>();
          for(String s : paramNames) {
            params.add(new ParameterNode(s, 0));
            lines.add(cn.name + " " + mn.name + " " + mn.desc + " " + s + " " + s);
          }
          mn.parameters = params;
        });
        ClassWriter writer = new ClassWriter(0);
        cn.accept(writer);
        try {
          Files.write(system.getPath("/" + system.getPath(cn.name) + ".class"), writer.toByteArray(), StandardOpenOption.TRUNCATE_EXISTING);
        } catch(IOException e) {e.printStackTrace();}
      });
    }
    lines.forEach(System.out::println);
    Files.write(mappingsPath, lines);
  }

  public static void main(String[] args) throws IOException {
    new Parametizer(Paths.get("inf-20100618.jar"), Paths.get("params.fprc"), Paths.get("inf-20100618-parameterized.jar"));
  }

  private static String decapitalizeString(String s) {
    return s == null || s.isEmpty() ? "" : Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  private String identifierName(String s) {
    switch(s) {
      case "B":
        return "byte";
      case "C":
        return "char";
      case "D":
        return "double";
      case "F":
        return "float";
      case "I":
        return "int";
      case "J":
        return "long";
      case "S":
        return "short";
      case "Z":
        return "boolean";
      default:
        return s;
    }
  }

  private List<String> nameParams(String descriptor) {
    List<String> result = new ArrayList<>();
    String argsDescriptor = descriptor.substring(1, descriptor.lastIndexOf(')'));
    //Parse chars LTR
    PrimitiveIterator.OfInt iterator = argsDescriptor.chars().iterator();
    List<Character> currentName = new ArrayList<>();
    boolean inWord = false;
    while(iterator.hasNext()) {
      char c = (char) iterator.nextInt();
      if(c != 'L' && c != '[' && !inWord) {
        result.add(identifierName(String.valueOf(c)) + (result.size() + 1));
      } else if(c == 'L' && !inWord) inWord = true;
      else if(c == ';') {
        String clsName = toString(currentName);
        result.add(decapitalizeString(clsName.substring(clsName.lastIndexOf('/') + 1)) +
            (result.size() + 1));
        currentName.clear();
        inWord = false;
      } else if(c != '[') currentName.add(c);
    }
    return result;
  }
}