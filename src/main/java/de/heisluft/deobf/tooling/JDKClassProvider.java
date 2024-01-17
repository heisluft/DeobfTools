package de.heisluft.deobf.tooling;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Provides Access To Classes from a specific JDK, thus not needing to load classes and adding the ability
 * to compile this Project with a different Version than the desired test classpath
 */
public class JDKClassProvider {

  private final Map<String, ClassNode> classCache = new HashMap<>();
  private final Set<Path> paths = new HashSet<>();
  private final boolean usesModules, thisJDK;

  /**
   * Finds a class in the JDK and returns it as a ClassNode
   *
   * @param name the internal name of the class to look for
   *
   * @return the resulting ClassNode
   */
  public ClassNode getClassNode(String name) {
    if(classCache.containsKey(name)) return classCache.get(name);
    if(thisJDK) {
      URL res = JDKClassProvider.class.getResource("/" + name + ".class");
      if(res == null) return null;
      try {
        ClassReader r = new ClassReader(res.openConnection().getInputStream());
        ClassNode n = new ClassNode();
        r.accept(n, ClassReader.SKIP_CODE);
        classCache.put(name, n);
        return n;
      } catch(IOException e) {
        throw new RuntimeException(e);
      }
    }

    for(Path p : paths) {
      try(ZipFile f = new ZipFile(p.toFile())) {
        ZipEntry e = f.getEntry((usesModules ? "classes/" : "") + name + ".class");
        if(e == null) continue;
        try(InputStream is = f.getInputStream(e)) {
          ClassReader cr = new ClassReader(is);
          ClassNode cn = new ClassNode();
          cr.accept(cn, ClassReader.SKIP_CODE);
          classCache.put(name, cn);
          return cn;
        }
      } catch(IOException e) {
        throw new RuntimeException(e);
      }
    }
    classCache.put(name, null);
    return null;
  }

  public JDKClassProvider() {
    thisJDK = true;
    // UNNEEDED
    usesModules = true;
  }

  /**
   * Construct a new JDKClassProvider from a given JDK root path.
   *
   * @param pathToJDK the JDK root path
   */
  public JDKClassProvider(Path pathToJDK) {
    thisJDK = false;
    if(!Files.isDirectory(pathToJDK)) throw new IllegalArgumentException(pathToJDK + " is not a directory");
    Path modulesPath = pathToJDK.resolve("jmods");
    usesModules = Files.isDirectory(modulesPath);
    if(usesModules)  {
      try {
        Files.newDirectoryStream(modulesPath).forEach(p -> {
          if(Files.isRegularFile(p) && p.toString().endsWith(".jmod")) {
            paths.add(p.normalize().toAbsolutePath());
          }
        });
        return;
      } catch(IOException e) {
        throw new IllegalArgumentException(pathToJDK + " could not be searched for modules, " + e);
      }
    }
    Path jreLibPath = pathToJDK.resolve("jre/lib/");
    if(!Files.isDirectory(jreLibPath)) throw new IllegalArgumentException(pathToJDK + " does not point to a valid JDK");
    try {
      Files.newDirectoryStream(jreLibPath).forEach(p -> {
        if(Files.isRegularFile(p) && p.toString().endsWith(".jar")) paths.add(p.normalize().toAbsolutePath());
      });
      Path extPath = jreLibPath.resolve("ext");
      if(!Files.isDirectory(extPath)) return;
      Files.newDirectoryStream(extPath).forEach(p -> {
        if(Files.isRegularFile(p) && p.toString().endsWith(".jar")) paths.add(p.normalize().toAbsolutePath());
      });
    } catch(IOException e) {
      throw new IllegalArgumentException(pathToJDK + " could not be searched for modules, " + e);
    }
  }
}
