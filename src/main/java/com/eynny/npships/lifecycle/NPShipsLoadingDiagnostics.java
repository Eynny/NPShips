package com.eynny.npships.lifecycle;

import com.eynny.npships.naturalspawn.NaturalSpawnPhysicalChunkManager;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only, on-demand diagnostics. Never requests chunks or changes any ticket. */
public final class NPShipsLoadingDiagnostics {
    private NPShipsLoadingDiagnostics() {}

    public static List<String> describe(ServerLevel level, UUID id) {
        var result = new ArrayList<String>();
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return List.of("Sable container unavailable.");
        var data = NPShipsSavedData.peek(level);
        result.add(
                "Saved unload deadline: "
                        + (data == null || !data.isTracked(id)
                                ? "untracked"
                                : data.getUnloadDeadline(id))
                        + " (-1 = loaded, otherwise epoch ms)");
        var info = container.getAllTickets().get(id);
        var tickets = new ArrayList<String>();
        if (info != null)
            for (var ticket : info.tickets()) {
                String value = ticket.type().name().toString();
                if (ticket.type().equals(NaturalSpawnPhysicalChunkManager.TICKET_TYPE))
                    value +=
                            " remaining="
                                    + Math.max(0L, (Long) ticket.key() - level.getGameTime())
                                    + " ticks";
                tickets.add(value);
            }
        result.add("Direct Sable tickets: " + tickets);
        if (!(container.getSubLevel(id) instanceof ServerSubLevel body) || body.isRemoved()) {
            result.add(
                    "No live Sable body; holding in memory="
                            + (container.getHoldingChunkMap().getHoldingSubLevel(id) != null));
            return result;
        }
        result.add(
                "Sable force-loaded (including connected bodies): "
                        + container.collectForceLoadedSubLevels().contains(body));
        var bounds = body.boundingBox();
        // Match Sable's one-block margin; its moving-body vertical prediction doesn't affect X/Z.
        int minX = Mth.floor(bounds.minX() - 1) >> 4, maxX = Mth.floor(bounds.maxX() + 1) >> 4;
        int minZ = Mth.floor(bounds.minZ() - 1) >> 4, maxZ = Mth.floor(bounds.maxZ() + 1) >> 4;
        long count = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
        var spawn = new ChunkPos(level.getSharedSpawnPos());
        int radius = level.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS);
        boolean spawnCovered =
                level.dimension().equals(Level.OVERWORLD)
                        && radius > 0
                        && minX >= spawn.x - radius
                        && maxX <= spawn.x + radius
                        && minZ >= spawn.z - radius
                        && maxZ <= spawn.z + radius;
        result.add(
                "World spawn="
                        + level.getSharedSpawnPos().toShortString()
                        + ", spawnChunkRadius="
                        + radius
                        + ", entire footprint inside spawn block-ticking area="
                        + spawnCovered);
        if (count > 0 && count <= 4096) {
            var distances = level.getChunkSource().chunkMap.getDistanceManager();
            int ticking = 0, forced = 0;
            var forcedChunks = level.getForcedChunks();
            for (int x = minX; x <= maxX; x++)
                for (int z = minZ; z <= maxZ; z++) {
                    long chunk = ChunkPos.asLong(x, z);
                    if (distances.inBlockTickingRange(chunk)) ticking++;
                    if (forcedChunks.contains(chunk)) forced++;
                }
            result.add(
                    "Footprint chunks: block-ticking="
                            + ticking
                            + "/"
                            + count
                            + ", explicit /forceload="
                            + forced);
        } else
            result.add("Footprint chunk count=" + count + "; detailed scan skipped (limit 4096).");
        return result;
    }
}
