package de.heisluft.reveng.util;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public interface Util extends AsmUtil, FunctionalUtil{
   default FileSystem createFS(Path path) throws IOException {
    Map<String, String> map = new HashMap<>(1);
    map.put("create", "true");
    URI uri = URI.create("jar:file:/" + path.toAbsolutePath().toString().replace('\\', '/'));
    return FileSystems.newFileSystem(uri, map);
  }
  /**
   * Joins the given Collection of characters to a string
   *
   * @param chars
   *     the chars to be joined
   *
   * @return the joined string
   */
  default String toString(Collection<Character> chars) {
    Iterator<Character> it = chars.iterator();
    StringBuilder builder = new StringBuilder();
    chars.forEach(builder::append);
    return builder.toString();
  }

}
