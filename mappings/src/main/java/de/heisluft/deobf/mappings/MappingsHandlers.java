package de.heisluft.deobf.mappings;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * This Class is the main interface for fetching MappingsHandler instances
 */
public final class MappingsHandlers {

  /**
   * Whether Handlers have been fetched
   */
  private static boolean hasFetched = false;
  /**
   * All gathered MappingsHandler instances mapped by their handled fileExtension
   */
  private static final Map<String, MappingsHandler> HANDLERS = new HashMap<>();

  /**
   * Finds a {@link MappingsHandler} for a given file. Builtin are:
   * <ol>
   *   <li>Fergie, handling .frg files</li>
   *   <li>RetroGuardScript, handling .rgs files</li>
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
    ServiceLoader.load(MappingsHandler.class).forEach(m -> HANDLERS.put(m.fileExt(), m));
    hasFetched = true;
  }

  /**
   * Finds a {@link MappingsHandler} for a given file extension. Builtin are:
   * <ol>
   *   <li>Fergie, handling frg</li>
   *   <li>RetroGuardScript, handling rgs</li>
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
}