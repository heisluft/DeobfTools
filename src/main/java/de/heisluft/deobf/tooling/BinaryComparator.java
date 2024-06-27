package de.heisluft.deobf.tooling;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class BinaryComparator implements Util {

  Map<String, String> packageMigrations = new HashMap<>();
  Map<String, String> nameMappings = new HashMap<>();
  Set<String> missingNames = new HashSet<>();

  private final MessageDigest MD5;


  private BinaryComparator() throws NoSuchAlgorithmException {
    MD5 = MessageDigest.getInstance("MD5");
    packageMigrations.put("com/mojang/minecraft/", "net/minecraft/client/");
    packageMigrations.put("", "");
  }

  private void doStuff(Path cmp1, Path cmp2) throws IOException {
    Map<String, ClassNode> firstClasses = parseClasses(cmp1, Collections.emptyList(), ClassReader.SKIP_CODE);
    Map<String, ClassNode> secondClasses = parseClasses(cmp2, Collections.emptyList(), ClassReader.SKIP_CODE);
    System.out.println("Performing Dumb Comparison");
    firstClasses.keySet().forEach(s -> {
        String found = packageMigrations.keySet().stream().filter(s::startsWith).findFirst().orElse("");
        String subst = packageMigrations.get(found) + s.substring(found.length());
        if(!secondClasses.containsKey(subst)) {
          missingNames.add(subst);
          return;
        }
        ClassNode old = firstClasses.get(s), _new = secondClasses.get(subst);
        String sfound = packageMigrations.keySet().stream().filter(old.superName::startsWith).findFirst().orElse("");
        String ssubst = packageMigrations.get(sfound) + old.superName.substring(sfound.length());
        if(ssubst.equals(_new.superName)) System.out.println(subst + ": " + ssubst + " vs " + _new.superName);
    });
  }

  private static String toHexString(byte[] bytes) {
    StringBuilder builder = new StringBuilder();
    for(byte b : bytes) builder.append(Integer.toHexString(Byte.toUnsignedInt(b)));
    return builder.toString();
  }

  public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
    Path prt = Paths.get("remap-tests/jars/mc/");

    new BinaryComparator().doStuff(prt.resolve("client/classic/c0.30_s.jar"), prt.resolve("client/indev/in-20091223-2.jar"));
  }
}
