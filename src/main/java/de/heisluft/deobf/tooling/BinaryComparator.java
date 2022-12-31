package de.heisluft.deobf.tooling;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class BinaryComparator implements Util {

  private final MessageDigest MD5;

  private BinaryComparator() throws NoSuchAlgorithmException {
    MD5 = MessageDigest.getInstance("MD5");
  }

  private void doStuff(Path cmp1, Path cmp2) throws IOException{
    try(FileSystem fs = createFS(cmp1); FileSystem fs2 = createFS(cmp2)) {
      Files.walk(fs.getPath("/")).filter(this::hasClassExt).forEach(p -> {
        Path p2 = fs2.getPath(p.toString());
        if(!Files.exists(p2)) {
          System.out.println("class " + p + " not present in cmp2");
          return;
        }
        try {
          long size1 = Files.size(p);
          long size2 = Files.size(p2);
          if(size1 > size2) {
            System.out.println("Class " + p + " is bigger in cmp1");
            return;
          }
          if(size2 > size1) {
            System.out.println("Class " + p + " is bigger in cmp2");
            return;
          }
          byte[] md51 = MD5.digest(Files.readAllBytes(p));
          MD5.reset();
          byte[] md52 = MD5.digest(Files.readAllBytes(p2));
          MD5.reset();
          if(Arrays.equals(md51, md52)) return;
          System.out.println("checksum mismatch for " + p + ":");
          System.out.println("cmp1: " + toHexString(md51));
          System.out.println("cmp2: " + toHexString(md52));
        } catch(IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    }catch(UncheckedIOException ex) {
      throw ex.getCause();
    }
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
