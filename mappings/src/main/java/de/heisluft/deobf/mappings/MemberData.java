package de.heisluft.deobf.mappings;

import java.util.Objects;

/** An internal immutable data class for storing member metadata. */
public final class MemberData {

  /** The member name. */
  public final String name;
  /** The member descriptor. */
  public final String desc;

  /**
   * Constructs new Member Metadata.
   *
   * @param name the non-null name of this member
   * @param desc the non-null descriptor og this member
   */
   public MemberData(String name, String desc) {
    Objects.requireNonNull(name, "Name must not be null");
    Objects.requireNonNull(desc, "Descriptor must not be null");
    this.name = name;
    this.desc = desc;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MemberData memberData = (MemberData) o;
    return name.equals(memberData.name) && desc.equals(memberData.desc);
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
