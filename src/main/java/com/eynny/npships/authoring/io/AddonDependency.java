package com.eynny.npships.authoring.io;

import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;

/** Explicit loader-checked dependency, separate from resource namespaces. */
public record AddonDependency(String modId, boolean required, String versionRange) {
    public AddonDependency {
        if (modId == null || !modId.matches("[a-z][a-z0-9_]{1,63}"))
            throw new IllegalArgumentException(
                    "Use a NeoForge mod ID: 2..64 lowercase letters, digits or underscores, starting with a letter.");
        if (versionRange == null
                || versionRange.length() > 256
                || !(versionRange.startsWith("[") || versionRange.startsWith("(")))
            throw new IllegalArgumentException(
                    "Enter a Maven version range, for example [1.2] or [1.2,2.0).");
        try {
            if (VersionRange.createFromVersionSpec(versionRange).getRestrictions().isEmpty())
                throw new IllegalArgumentException(
                        "A bounded or explicit open Maven range is required.");
        } catch (InvalidVersionSpecificationException e) {
            throw new IllegalArgumentException(
                    "Invalid dependency version range: " + versionRange, e);
        }
    }
}
