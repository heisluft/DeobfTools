package de.heisluft.deobf.mappings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * This Class is the main interface for fetching and using MappingsHandler instances.
 * The builtin MappingsHandlers are:
 * <ol>
 *   <li>Fergie, handling .frg files and "frg"</li>
 *   <li>RetroGuardScript, handling .rgs files and "rgs"</li>
 *   <li>SRG, handling .srg files and "srg"</li>
 * </ol>
 *
 * Static helper methods are provided to directly fetch and use a MappingsHandler for a given operation
 */
public final class MappingsHandlers {

  /** This class should not be instantiated. */
  private MappingsHandlers() {
    throw new UnsupportedOperationException();
  }

  /** Whether Handlers have been fetched. */
  private static boolean hasFetched = false;
  /** All gathered MappingsHandler instances mapped by their handled fileExtension. */
  private static final Map<String, MappingsHandler> HANDLERS = new HashMap<>();

  /**
   * Finds a {@link MappingsHandler} for a given file. Builtin are:
   * <ol>
   *   <li>Fergie, handling .frg files</li>
   *   <li>RetroGuardScript, handling .rgs files</li>
   *   <li>SRG, handling .srg files</li>
   * </ol>
   *
   * @param fileName
   *     the name of the to find a provider for
   *
   * @return an instance of a MappingsHandler or {@code null} if no such handler exists for the given file
   */
  public static MappingsHandler findFileHandler(String fileName) {
    checkInit();
    return HANDLERS.get(fileName.substring(fileName.lastIndexOf('.') + 1));
  }

  /**
   * Gathers all MappingsHandler instances if they haven't been gathered yet.
   */
  private static void checkInit() {
    if (hasFetched) return;
    ServiceLoader.load(MappingsHandler.class)
        .forEach(m -> m.fileExts().forEach(ext -> HANDLERS.put(ext, m.withFileExt(ext))));
    hasFetched = true;
  }

  /**
   * Finds a {@link MappingsHandler} for a given file extension. Builtin are:
   * <ol>
   *   <li>Fergie, handling frg</li>
   *   <li>RetroGuardScript, handling rgs</li>
   *   <li>SRG, handling srg</li>
   * </ol>
   *
   * @param fileExt
   *     the file extension to find a provider for
   *
   * @return an instance of a MappingsHandler or {@code null} if no such handler exists for the given file extension
   */
  public static MappingsHandler findHandler(String fileExt) {
    checkInit();
    return HANDLERS.get(fileExt);
  }

  /**
   * Fetches a MappingsHandler for a given path and uses it to
   * parse the Mappings located there.
   * <br>
   * Note: the fetching of a handler instance is based on the file extension
   *
   * @param path the path where the mappings are located
   * @return the parsed mappings
   * @throws IOException if the input path could not be read
   */
  public static Mappings parseMappings(Path path) throws IOException {
    return findFileHandler(path.toString()).parseMappings(path);
  }

  /**
   * Fetches a MappingsHandler for a given path and uses it to write Mappings to it.
   * <br>
   * Note: the fetching of a handler instance is based on the file extension
   *
   * @param mappings the mappings to write
   * @param path the path to write to
   * @throws IOException if the path could not be written to
   */
  public static void writeMappings(Mappings mappings, Path path) throws IOException {
    findFileHandler(path.toString()).writeMappings(mappings, path);
  }
}
