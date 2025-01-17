package de.heisluft.deobf.mappings.handlers;

import de.heisluft.deobf.mappings.Mappings;
import de.heisluft.deobf.mappings.MemberData;

import java.util.HashMap;
import java.util.Map;

/**
 * RGS Mappings can relocate classes to other packages based on if they match a certain regex.
 */
//TODO: Merge into Mappings so Mappings can be made final
final class RGSMappings extends Mappings {
  /** A map mapping regexes to packages. packages end on a '/'. */
  final Map<String, String> packages = new HashMap<>();

  @Override
  public String getClassName(String className) {
    for(Map.Entry<String, String> relocation : packages.entrySet()) {
      String cNameOnly = className.contains("/") ? className.substring(className.lastIndexOf('/') + 1) : className;
      if(className.matches(relocation.getKey())) return relocation.getValue() + super.getClassName(cNameOnly);
    }
    return super.getClassName(className);
  }

  @Override
  public boolean hasClassMapping(String className) {
    return super.hasClassMapping(className) || packages.keySet().stream().anyMatch(className::matches);
  }

  /**
   * Add a class mapping. Existing mappings will be overridden.
   *
   * @param cName the binary class name to map
   * @param rName the remapped name
   */
  void addClassMapping(String cName, String rName) {
    classes.put(cName, rName);
  }

  /**
   * Add a field mapping. Existing mappings will be overridden.
   *
   * @param cName the binary name of the containing class
   * @param fName the field name to map
   * @param rName the remapped name
   */
  void addFieldMapping(String cName, String fName, String rName) {
    fields.computeIfAbsent(cName, _k -> new HashMap<>()).put(new MemberData(fName, EMPTY_FIELD_DESCRIPTOR), rName);
  }

  /**
   * Add a method mapping. Existing mappings will be overridden.
   *
   * @param cName the binary name of the containing class
   * @param mName the method name to map
   * @param mDesc the methods descriptor
   * @param rName the remapped name
   */
  void addMethodMapping(String cName, String mName, String mDesc, String rName) {
    methods.computeIfAbsent(cName, _k -> new HashMap<>()).put(new MemberData(mName, mDesc), rName);
  }

}
