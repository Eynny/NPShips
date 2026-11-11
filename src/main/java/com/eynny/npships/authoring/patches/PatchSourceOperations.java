package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Set;

/**
 * Known-source grammar and explicit mandatory identity/construction boundaries. Pure
 * read/validation never invokes native callbacks; identity sanitation does not restore disabled
 * devices.
 */
public interface PatchSourceOperations extends PatchSourceFormat {
    default void envelope(JsonObject patch) {}

    default void validate(
            SchematicDocument document,
            JsonObject patch,
            boolean complete,
            Set<BlockPos> occupied) {}

    default void references(SchematicDocument document, List<JsonObject> patches) {}

    default void embed(JsonObject patch, CompoundTag block) {}

    /** One read-only entry view shared by applicable validators during this single source walk. */
    record Capture(
            SchematicDocument document,
            CompoundTag record,
            BlockPos position,
            String name,
            CompoundTag data,
            JsonObject captured,
            Set<BlockPos> paired) {}

    default boolean validatesCapture() {
        return true;
    }

    default int captureValidationRank() {
        return Integer.MAX_VALUE;
    }

    default void requireCapture(Capture source) {}

    default List<BlockPos> captureTargets(JsonObject patch) {
        return List.of();
    }

    default void copied(JsonObject patch) {}

    enum CopyMode {
        DEVICE,
        REGION,
        LAYER,
        SYMMETRY
    }

    /** Host operation context; connected copies retain their existing graph remap. */
    default void copied(JsonObject patch, CopyMode mode) {
        if (mode == CopyMode.DEVICE) copied(patch);
    }

    default void inspect(JsonObject patch) {}

    default boolean selects(SchematicDocument source, JsonObject record, BlockPos selection) {
        return false;
    }

    default boolean acceptsOwner(SchematicDocument source, JsonObject record, BlockPos selection) {
        return false;
    }

    default int selectionRank(JsonObject record) {
        return 1;
    }

    /** Source state identifies the native owner before a provider interprets its fields. */
    default void importedBlock(CompoundTag state, CompoundTag block, Set<BlockPos> positions) {}

    /** Mandatory source-format safety, also when an optional runtime provider is unavailable. */
    default void validateNativeBlock(String name, CompoundTag data) {}

    /** Installed native live-state safety during authorized capture, never restoration. */
    default void validateLive(
            net.minecraft.world.level.block.entity.BlockEntity entity, BlockPos position) {}

    default void inventoryChanged(JsonObject patch, CompoundTag data) {}

    /** Mandatory known-native validation after constructor defaults, not restoration. */
    default void validatePrepared(CompoundTag data, JsonObject patch) {}

    default boolean ownsKind(String kind) {
        return false;
    }

    default boolean tracks(JsonObject patch) {
        return false;
    }

    default void replacing(JsonObject accepted, List<JsonObject> records) {}

    default void removeTarget(JsonObject patch, BlockPos owner, BlockPos target) {}

    /** Mandatory source-root sanitation; this never grants ownership of arbitrary nested NBT. */
    default void clearAddresses(String sourceBlock, CompoundTag nativeData) {}

    default boolean brush(String block, CompoundTag nativeData) {
        return false;
    }

    default boolean sanitize(
            String block, CompoundTag nativeData, boolean unpatched, boolean ropeEnd) {
        return false;
    }

    default void sanitizeEntity(
            net.minecraft.world.level.block.entity.BlockEntity entity, CompoundTag data)
            throws Exception {}

    default void rotateRecord(
            CompoundTag record, net.minecraft.world.level.block.Rotation rotation) {}

    default boolean rotatesData(net.minecraft.world.level.block.state.BlockState state) {
        return false;
    }

    default void rotateData(
            net.minecraft.world.level.block.state.BlockState state,
            CompoundTag original,
            CompoundTag data,
            net.minecraft.world.level.block.Rotation rotation) {}

    default boolean mirror(
            net.minecraft.world.level.block.state.BlockState state, CompoundTag data, int axes) {
        return false;
    }

    /**
     * Callback-borrowed detached delta of one explicit replacement, within the host transaction.
     */
    default void blockReplaced(
            SchematicDocument source, BlockPos at, java.util.Map<BlockPos, CompoundTag> changes) {}

    default void validateDocument(SchematicDocument document) {}

    default void remap(
            CompoundTag result,
            SchematicDocument source,
            java.util.Map<BlockPos, BlockPos> mapping,
            java.util.function.Function<BlockPos, BlockPos> vectors) {}

    default void entityDefaults(net.minecraft.world.entity.Entity entity) {}

    default boolean authoredBlock(String block, CompoundTag data, CompoundTag visual) {
        return false;
    }

    default CompoundTag savedVisual(CompoundTag data) {
        return null;
    }

    default void blockSettings(
            String block, CompoundTag before, CompoundTag after, CompoundTag data) {}

    /** Pure synchronization of known native projections in the same accepted data edit. */
    default SchematicDocument blockDataEdited(
            SchematicDocument before, SchematicDocument after, BlockPos position) {
        return after;
    }
}
