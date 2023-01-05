package de.heisluft.deobf.mappings;

import java.util.Objects;

/**
 * An internal immutable data class for storing method metadata
 */
public final class MdMeta {
  /** The methods name */
  public final String name;
  /** The methods descriptor */
  public final String desc;

   public MdMeta(String name, String desc) {
    Objects.requireNonNull(name, "Name must not be null");
    Objects.requireNonNull(desc, "Descriptor must not be null");
    this.name = name;
    this.desc = desc;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MdMeta mdMeta = (MdMeta) o;
    return name.equals(mdMeta.name) && desc.equals(mdMeta.desc);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, desc);
  }

  @Override
  public String toString() {
    return name + desc;
  }
}
