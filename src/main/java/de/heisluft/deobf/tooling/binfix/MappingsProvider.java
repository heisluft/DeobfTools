package de.heisluft.deobf.tooling.binfix;

import de.heisluft.deobf.mappings.MappingsBuilder;

/**
 * This interface can be implemented by all Utilities that are able to generate Mappings
 * E.G. automatic inner class renames, this$0, $VALUES, SwitchMap fields, accessor methods
 */
public interface MappingsProvider {
    /**
     * provides the utility with a MappingsBuilder instance. Called before the utility executes.
     *
     * @param builder the mappingsBuilder instance, may already contain mappings, never {@code null}
     */
    void setBuilder(MappingsBuilder builder);

    /**
     * Called after a utility has executed to retrieve the MappingsBuilder for further usage
     * @return the builder, may or may not be empty, never {@code null}
     */
    MappingsBuilder getBuilder();
}
