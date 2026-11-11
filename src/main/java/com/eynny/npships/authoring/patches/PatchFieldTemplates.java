package com.eynny.npships.authoring.patches;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/** Detached form/reference schemas. These never write the accepted author document. */
public interface PatchFieldTemplates extends PatchSourceFormat {
    record Subject(boolean entity, String id) {
        public Subject {
            java.util.Objects.requireNonNull(id);
        }

        public static Subject block(String id) {
            return new Subject(false, id);
        }

        public static Subject entity(String id) {
            return new Subject(true, id);
        }

        public static Subject unknown() {
            return block("");
        }
    }

    default boolean applies(Subject subject) {
        return false;
    }

    /** Actual native key/index path, not a guessed universal item-field catalogue. */
    default boolean itemField(Subject subject, List<String> path) {
        return false;
    }

    /** Existing conservative native safety positions; never exempt real stacks by policy. */
    default java.util.Set<String> validationFields() {
        return java.util.Set.of();
    }

    default Tag entry(Subject subject, String path) {
        return entry(path);
    }

    default void expand(CompoundTag data) {}

    default Tag entry(String path) {
        return null;
    }

    default List<String> choices(String path, String key) {
        return List.of();
    }
}
