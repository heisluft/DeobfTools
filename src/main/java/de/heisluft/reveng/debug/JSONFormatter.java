package de.heisluft.reveng.debug;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

public class JSONFormatter {
  public static String toJSONString(Map<?, ?> map, int indent) {
    StringBuilder builder = new StringBuilder();
    builder.append("{\n");
    for (Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator(); iterator.hasNext();) {
      Map.Entry<?, ?> entry = iterator.next();
      if(!(entry.getKey() instanceof String)) continue;
      Object value = entry.getValue();
      for (int i = 0; i < indent; i++) builder.append("  ");
      builder.append("  \"").append(entry.getKey()).append("\": ");
      if (value instanceof Map) builder.append(toJSONString((Map<?, ?>) value, indent + 1));
      else if (value instanceof Collection) builder.append(toJSONString((Collection<?>) value));
      else if (value instanceof String) builder.append('"').append(value).append('"');
      else builder.append(value); // This assumes all entries within the map are actually valid json values
      if (iterator.hasNext()) builder.append(',');
      builder.append('\n');
    }
    for (int i = 0; i < indent; i++) builder.append("  ");
    return builder.append('}').toString();
  }

  public static String toJSONString(Collection<?> collection) {
    StringBuilder builder = new StringBuilder("[");
    for (Iterator<?> iterator = collection.iterator(); iterator.hasNext(); ) {
      Object o = iterator.next();
      if (o instanceof String) builder.append('"').append(o).append('"');
      else builder.append(o);
      if(iterator.hasNext()) builder.append(", ");
    }
    return builder.append("]").toString();
  }
}
