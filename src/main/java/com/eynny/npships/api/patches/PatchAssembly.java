package com.eynny.npships.api.patches;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.Set;

/**
 * Optional native assembly rules through the existing frozen patch registry. Calls occur only on
 * the authorized server thread. Implementations retain no level outside the returned operation.
 */
public interface PatchAssembly {
    /**
     * Whether this provider supplies default connection rules. The host consults defaults only when
     * no ordinary provider returns a plan. Multiple applicable plans within either group refuse;
     * ordering and hard dependencies still apply.
     *
     * @return true for default rules, false for ordinary native rules
     */
    default boolean defaultRules() {
        return false;
    }

    /**
     * Read-only native membership selection, or null when this provider has no applicable rules.
     * Every returned position must belong to the supplied read-only schematic membership. Competing
     * non-null plans refuse; provider priority cannot select a silent winner.
     *
     * @param level current authorized server level; must not be retained after the operation
     * @param point requested source-world assembly coordinate
     * @param permitted immutable set of authored non-air source-world coordinates
     * @return a bounded exclusive native plan, or null when these rules do not apply
     * @throws Exception when the native graph cannot safely be represented
     */
    default Plan plan(ServerLevel level, BlockPos point, Set<BlockPos> permitted) throws Exception {
        return null;
    }

    /**
     * Mandatory movement exclusion, independent of optional restoration settings.
     *
     * @param state native block state being considered
     * @param level current source view; must not be retained
     * @param position coordinate in that source view
     * @return true if moving this block must refuse
     */
    default boolean excluded(BlockState state, BlockGetter level, BlockPos position) {
        return false;
    }

    /**
     * One bounded operation. Selection must not mutate world state. The host validates membership,
     * transfers it with its physics platform and then supplies the exact world-to-body offset.
     */
    interface Plan extends AutoCloseable {
        /**
         * Exact source-world block positions; the host copies and validates these before transfer.
         *
         * @return bounded source membership containing the requested coordinate
         */
        Collection<BlockPos> blocks();

        /**
         * Restore only this plan's native attachments after successful block transfer.
         *
         * @param offset exact integer source-world to native-body translation
         * @throws Exception when restoring the owned attachments fails
         */
        default void transferred(BlockPos offset) throws Exception {}

        /** Release operation-owned references; must also support refusal before transfer. */
        @Override
        default void close() {}
    }
}
