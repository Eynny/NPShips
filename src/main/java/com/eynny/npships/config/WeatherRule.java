package com.eynny.npships.config;

import java.util.List;

/**
 * Detached weather selection rule. Matching forbids take precedence over allows. Unknown condition
 * names do not match. This original FQN is an intentional API dependency.
 *
 * @param allow names of which at least one must match; empty permits every non-forbidden condition
 * @param forbid names that reject placement when active
 */
public record WeatherRule(List<String> allow, List<String> forbid) {

    /**
     * Copies both lists; null lists become empty and null elements are rejected.
     *
     * @param allow names of which at least one must match; empty permits every non-forbidden
     *     condition
     * @param forbid names that reject placement when active
     */
    public WeatherRule {
        allow = List.copyOf(allow == null ? List.of() : allow);

        forbid = List.copyOf(forbid == null ? List.of() : forbid);
    }

    /**
     * Creates an unrestricted weather rule.
     *
     * @return two empty condition lists
     */
    public static WeatherRule allowAll() {
        return new WeatherRule(List.of(), List.of());
    }
}
