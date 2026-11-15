package com.eynny.npships.api.patches;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Detached template input available through host capability negotiation. The supplying owner must
 * return a fresh privately owned copy and reserve its identifiable growth before copying. This
 * capability does not expose mutable live source, bypass admission, or mutate the prepared world.
 */
public final class PatchTemplateInput {
    private final Supplier<CompoundTag> copies;
    private final Predicate<String> contains;
    private final Function<ResourceLocation, List<PatchAuthoring.Data>> authorData;

    /**
     * Compatibility constructor; marker checks use a detached copy when no cheap probe exists.
     *
     * @param copies guarded fresh detached-copy supplier
     */
    public PatchTemplateInput(Supplier<CompoundTag> copies) {
        this(copies, key -> Objects.requireNonNull(copies.get()).contains(key));
    }

    /**
     * Retains callback-guarded detached-copy and marker suppliers.
     *
     * @param copies guarded fresh detached-copy supplier
     * @param contains guarded source-root marker probe
     */
    public PatchTemplateInput(Supplier<CompoundTag> copies, Predicate<String> contains) {
        this(copies, contains, id -> List.of());
    }

    /**
     * Callback-scoped single-provider input; no complete template copy is necessary.
     *
     * @param copies guarded fresh detached-copy supplier
     * @param contains guarded source-root marker probe
     * @param authorData named provider-owned NBT schemas
     */
    public PatchTemplateInput(
            Supplier<CompoundTag> copies,
            Predicate<String> contains,
            Function<ResourceLocation, List<PatchAuthoring.Data>> authorData) {
        this.copies = Objects.requireNonNull(copies);
        this.contains = Objects.requireNonNull(contains);
        this.authorData = Objects.requireNonNull(authorData);
    }

    /**
     * Detached records for one provider, retaining no live source or editor reference.
     *
     * @param type provider ID whose records are requested
     * @return detached records owned by the requested provider
     */
    public List<PatchAuthoring.Data> authorData(ResourceLocation type) {
        return List.copyOf(authorData.apply(Objects.requireNonNull(type)));
    }

    /**
     * Cheap source-root marker probe. The host guards both suppliers to the callback window.
     *
     * @param key top-level serializer field name
     * @return whether the source root contains this marker
     */
    public boolean contains(String key) {
        return contains.test(Objects.requireNonNull(key));
    }

    /**
     * The returned detached copy belongs to the caller; never retain the host capability.
     *
     * @return fresh detached source copy owned by the caller
     */
    public CompoundTag sourceCopy() {
        return Objects.requireNonNull(copies.get(), "Patch template source copy");
    }
}
