package com.eynny.npships.authoring.validation;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/** Optional source identity; the original exception type, message and cause stay authoritative. */
public final class ValidationProblem {
    private ValidationProblem() {}

    public record Detail(String code, List<String> arguments) {}

    private static final class Marker extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;
        final String[] arguments;

        Marker(String code, Object[] arguments) {
            super(code, null, false, false);
            this.code = code;
            this.arguments = Arrays.stream(arguments).map(String::valueOf).toArray(String[]::new);
        }
    }

    public static IllegalArgumentException invalid(
            String code, String technical, Object... arguments) {
        return describe(new IllegalArgumentException(technical), code, arguments);
    }

    public static <T extends RuntimeException> T describe(
            T failure, String code, Object... arguments) {
        if (detail(failure) == null) failure.addSuppressed(new Marker(code, arguments));
        return failure;
    }

    public static Detail detail(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (var current = failure;
                current != null && seen.add(current);
                current = current.getCause())
            for (var suppressed : current.getSuppressed())
                if (suppressed instanceof Marker marker)
                    return new Detail(marker.code, List.of(marker.arguments));
        return null;
    }
}
