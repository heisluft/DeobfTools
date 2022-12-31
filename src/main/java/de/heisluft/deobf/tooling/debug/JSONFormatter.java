package de.heisluft.deobf.tooling.debug;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * A utility class for serializing Java collections as JSON
 */
public class JSONFormatter {
  /**
   * Outputs the given map in JSON format. The implementation only includes string keys, as other
   * types are not valid JSON keys. It also operates recursively on sub maps and contained
   * collections. Note that non-JSON values are stringified by calling toString on them, leaving the
   * potential of syntax errors, as the resulting string may not represent a valid JSON identifier
   *
   * @param map
   *     the map to serialize
   * @param indent
   *     how much the output strings lines should be indented
   *
   * @return the resulting JSON String
   *
   * @see JSONFormatter#toJSONString(Collection, int)
   */
  public static String toJSONString(Map<?, ?> map, int indent) {
    StringBuilder builder = new StringBuilder();
    builder.append("{\n");
    for(Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator(); iterator.hasNext(); ) {
      Map.Entry<?, ?> entry = iterator.next();
      if(!(entry.getKey() instanceof String)) continue;
      Object value = entry.getValue();
      for(int i = 0; i < indent; i++) builder.append("  ");
      builder.append("  \"").append(entry.getKey()).append("\": ");
      if(value instanceof Map)
        builder.append(toJSONString((Map<?, ?>) value, indent + 1));
      else if(value instanceof Collection)
        builder.append(toJSONString((Collection<?>) value, indent + 1));
      else if(value instanceof String)
        builder.append('"').append(value).append('"');
      else
        builder.append(value); // This assumes all entries within the map are actually valid json values
      if(iterator.hasNext()) builder.append(',');
      builder.append('\n');
    }
    for(int i = 0; i < indent; i++) builder.append("  ");
    return builder.append('}').toString();
  }

  /**
   * Outputs the given collection in JSON Format, also operating recursively on contained
   * collections and maps. Note that non-JSON values are stringified by calling toString on them,
   * leaving the potential of syntax errors, as the resulting string may not represent a valid JSON
   * identifier
   *
   * @param collection
   *     the collection to serialize
   * @param indent
   *     hom much to indent contained json objects by
   *
   * @return the resulting JSON String
   *
   * @see #toJSONString(Map, int)
   */
  public static String toJSONString(Collection<?> collection, int indent) {
    StringBuilder builder = new StringBuilder("[");
    for(Iterator<?> iterator = collection.iterator(); iterator.hasNext(); ) {
      Object o = iterator.next();
      if(o instanceof String) builder.append('"').append(o).append('"');
      else if(o instanceof Collection) builder.append(toJSONString((Collection<?>) o, indent));
      else if(o instanceof Map) builder.append(toJSONString((Map<?, ?>) o, indent));
      else builder.append(o);
      if(iterator.hasNext()) builder.append(", ");
    }
    return builder.append("]").toString();
  }
}
