package com.eynny.npships.client.editor;

/** A retained field is its session target token; semantic reconstruction expires that token. */
final class EditorValidationFeedback {
    private Object owner;
    private long draft;

    private record Error(Object owner, long generation, String message) {}

    private Error error;
    private final java.util.Map<Object, Field> fields = new java.util.LinkedHashMap<>();

    private static final class Field {
        final String section;
        long generation;
        boolean pending;
        Error error;

        Field(String section) {
            this.section = section;
        }
    }

    void context(Object owner) {
        if (owner == null || this.owner != null && this.owner != owner) fields.clear();
        this.owner = owner;
        draft = 0;
        error = null;
    }

    void draft(Object owner, long generation) {
        if (this.owner == owner && generation >= draft) draft = generation;
        var field = fields.get(owner);
        if (field != null && generation >= field.generation) {
            field.generation = generation;
            field.pending = true;
        }
    }

    void result(Object owner, long generation, String failure) {
        if (this.owner == owner && generation == draft)
            error = failure == null ? null : new Error(owner, generation, failure);
        var field = fields.get(owner);
        if (field != null && generation == field.generation) {
            field.error = failure == null ? null : new Error(owner, generation, failure);
            field.pending = failure != null;
        }
    }

    boolean owns(Object source) {
        return owner == source || fields.containsKey(source);
    }

    String error() {
        if (error != null) return error.message();
        for (var field : fields.values()) if (field.error != null) return field.error.message();
        return null;
    }

    void register(Object field, String section) {
        fields.putIfAbsent(field, new Field(section));
    }

    void forget(Object field) {
        fields.remove(field);
        if (owner == field) {
            owner = null;
            error = null;
            draft = 0;
        }
    }

    long generation(Object owner) {
        var field = fields.get(owner);
        return field == null ? 0 : field.generation;
    }

    void changed(Object owner) {
        var field = fields.get(owner);
        if (field != null) {
            field.generation++;
            field.error = null;
            field.pending = true;
        }
    }

    Object errorOwner() {
        if (error != null) return error.owner();
        for (var field : fields.values()) if (field.error != null) return field.error.owner();
        return null;
    }

    String sectionError(String section) {
        for (var field : fields.values())
            if (field.section.equals(section) && field.error != null) return field.error.message();
        return null;
    }

    boolean sectionPending(String section) {
        return fields.values().stream()
                .anyMatch(field -> field.section.equals(section) && field.pending);
    }

    static final class Failure extends RuntimeException {
        final Object owner;

        Failure(RuntimeException cause) {
            this(cause, null);
        }

        Failure(RuntimeException cause, Object owner) {
            super(cause.getMessage(), cause);
            this.owner = owner;
        }
    }
}
