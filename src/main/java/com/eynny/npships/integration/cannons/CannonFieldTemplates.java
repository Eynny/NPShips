package com.eynny.npships.integration.cannons;

import com.eynny.npships.authoring.patches.PatchFieldTemplates;

import java.util.*;

/** Pure known CBC inventory serialization; remains readable without the optional runtime. */
public final class CannonFieldTemplates implements PatchFieldTemplates {
    public boolean applies(Subject subject) {
        return subject.id().startsWith("createbigcannons:");
    }

    public Set<String> validationFields() {
        return Set.of("Ammo", "Tracers", "Magazine");
    }

    public boolean itemField(Subject subject, List<String> path) {
        return path.size() == 1 && validationFields().contains(path.getFirst());
    }
}
