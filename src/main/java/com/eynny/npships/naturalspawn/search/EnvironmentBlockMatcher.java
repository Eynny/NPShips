package com.eynny.npships.naturalspawn.search;

import com.eynny.npships.config.EnvironmentRule;
import com.eynny.npships.config.SupportBlockDetector;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Exact matcher for one world block against one EnvironmentRule.
 *
 * <p>Contains no search state.
 *
 * <p>The supplied BlockState is treated as the authoritative state for the block being tested. This
 * allows placement search to operate entirely from SpawnSpatialSnapshot without performing
 * additional world block lookups.
 */
public final class EnvironmentBlockMatcher {

    private EnvironmentBlockMatcher() {}

    public static boolean matchesAll(
            final ServerLevel level,
            final BlockPos pos,
            final BlockState state,
            final EnvironmentRule rule) {

        if (level == null) {
            throw new IllegalArgumentException("level cannot be null.");
        }

        if (pos == null) {
            throw new IllegalArgumentException("pos cannot be null.");
        }

        if (state == null) {
            throw new IllegalArgumentException("state cannot be null.");
        }

        if (rule == null) {
            throw new IllegalArgumentException("rule cannot be null.");
        }

        if (rule.type() != EnvironmentRule.Type.ALL) {

            throw new IllegalArgumentException("matchesAll called for rule type " + rule.type());
        }

        /*
         * Exclusions always win.
         */
        if (matchesExcluded(state, rule)) {
            return false;
        }

        /*
         * Source fluids are not valid ALL blocks.
         */
        if (state.getFluidState().isSource()) {

            return false;
        }

        /*
         * Air requires explicit permission.
         */
        if (state.isAir()) {
            return rule.allowAir();
        }

        /*
         * Use the supplied already-scanned BlockState.
         *
         * No additional world block lookup is performed.
         */
        final boolean support = SupportBlockDetector.isSupportBlock(level, pos, state);

        /*
         * A non-support block may bypass positive selectors when
         * explicitly allowed.
         */
        if (!support && rule.allowNonSupport()) {

            return true;
        }

        /*
         * Support blocks, or non-support blocks when they are not
         * explicitly allowed, must satisfy the positive selectors.
         */
        return matchesPositiveSelector(state, rule);
    }

    public static boolean matchesSurfaceSupport(
            final BlockState state, final EnvironmentRule rule) {

        if (state == null) {
            throw new IllegalArgumentException("state cannot be null.");
        }

        if (rule == null) {
            throw new IllegalArgumentException("rule cannot be null.");
        }

        if (rule.type() != EnvironmentRule.Type.SURFACE) {

            throw new IllegalArgumentException(
                    "matchesSurfaceSupport called for rule type " + rule.type());
        }

        /*
         * Positive block and tag selectors are OR-ed.
         *
         * When neither is specified, any support block is valid.
         */
        return !matchesExcluded(state, rule) && matchesPositiveSelector(state, rule);
    }

    public static boolean isExcluded(final BlockState state, final EnvironmentRule rule) {

        if (state == null) {
            throw new IllegalArgumentException("state cannot be null.");
        }

        if (rule == null) {
            throw new IllegalArgumentException("rule cannot be null.");
        }

        return matchesExcluded(state, rule);
    }

    private static boolean matchesExcluded(final BlockState state, final EnvironmentRule rule) {

        /*
         * Explicit block exclusions.
         */
        if (!rule.excludeBlocks().isEmpty()) {

            final ResourceLocation blockId = getBlockId(state);

            if (blockId != null && rule.excludeBlocks().contains(blockId)) {

                return true;
            }
        }

        /*
         * Tag exclusions.
         *
         * EnvironmentRule stores ResourceLocation values,
         * while BlockState.is(...) expects TagKey<Block>.
         */
        for (final ResourceLocation tagId : rule.excludeTags()) {

            final TagKey<Block> tag = createBlockTag(tagId);

            if (state.is(tag)) {
                return true;
            }
        }

        return false;
    }

    private static boolean matchesPositiveSelector(
            final BlockState state, final EnvironmentRule rule) {

        final List<ResourceLocation> blocks = rule.blocks();

        final List<ResourceLocation> tags = rule.tags();

        /*
         * No positive selectors means any block is accepted.
         */
        if (blocks.isEmpty() && tags.isEmpty()) {

            return true;
        }

        /*
         * Explicit block selectors.
         */
        if (!blocks.isEmpty()) {

            final ResourceLocation blockId = getBlockId(state);

            if (blockId != null && blocks.contains(blockId)) {

                return true;
            }
        }

        /*
         * Tag selectors.
         *
         * Blocks and tags are OR-ed.
         */
        for (final ResourceLocation tagId : tags) {

            final TagKey<Block> tag = createBlockTag(tagId);

            if (state.is(tag)) {
                return true;
            }
        }

        return false;
    }

    private static ResourceLocation getBlockId(final BlockState state) {

        return state.getBlockHolder().unwrapKey().map(key -> key.location()).orElse(null);
    }

    private static TagKey<Block> createBlockTag(final ResourceLocation id) {

        return TagKey.create(Registries.BLOCK, id);
    }
}
