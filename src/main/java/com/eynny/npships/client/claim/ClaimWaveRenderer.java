package com.eynny.npships.client.claim;

import com.eynny.npships.block.ClaimBlock;
import com.eynny.npships.block.ClaimSurface;
import com.eynny.npships.config.NPShipsEditorConfig;
import com.eynny.npships.network.ClaimNetworking;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.*;

/** Short-lived surface snapshots in plot coordinates, projected with the current Sable pose. */
@EventBusSubscriber(modid = "npships", value = Dist.CLIENT)
public final class ClaimWaveRenderer {
    private record Key(UUID body, BlockPos origin) {}

    private static final Map<Key, Wave> WAVES = new LinkedHashMap<>();
    private static ClientLevel owner;
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final class Wave {
        final Key key;
        final long created;
        long impact = -1;
        final List<LevelChunk> chunks;
        final Long2ObjectMap<LevelChunk> lookup = new Long2ObjectOpenHashMap<>();
        final FloatArrayList rectangles = new FloatArrayList();
        final ByteArrayList masks = new ByteArrayList();
        final FloatArrayList distances = new FloatArrayList();
        final Map<List<VoxelShape>, List<ClaimSurface.Face>> surfaces = new HashMap<>();
        // The mutable lookup probe never becomes a retained cache key.
        final List<VoxelShape> shapeProbe = Arrays.asList(new VoxelShape[7]);
        int chunk, section, cell;
        double radius = 1;

        Wave(Key key, ClientSubLevel body, long tick) {
            this.key = key;
            created = tick;
            chunks = new ArrayList<>();
            for (var holder : body.getPlot().getLoadedChunks()) {
                var c = holder.getChunk();
                chunks.add(c);
                lookup.put(c.getPos().toLong(), c);
            }
            var p = new BlockPos.MutableBlockPos();
            for (var c : chunks) {
                // A conservative radius from loaded sections fixes propagation speed before
                // meshing.
                for (int s = 0; s < c.getSectionsCount(); s++)
                    if (!c.getSection(s).hasOnlyAir()) {
                        int y = c.getSectionYFromSectionIndex(s) * 16;
                        for (int corner = 0; corner < 8; corner++) {
                            p.set(
                                    c.getPos().getMinBlockX() + ((corner & 1) == 0 ? 0 : 16),
                                    y + ((corner & 2) == 0 ? 0 : 16),
                                    c.getPos().getMinBlockZ() + ((corner & 4) == 0 ? 0 : 16));
                            radius = Math.max(radius, Math.sqrt(p.distSqr(key.origin)));
                        }
                    }
            }
        }

        VoxelShape shape(BlockPos p) {
            var c = lookup.get(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4));
            if (c == null) return Shapes.empty();
            var state = c.getBlockState(p);
            // Outline geometry follows visible fence rails, not the invisible 1.5-block
            // collision barrier that prevents jumping over them.
            // The stamp model moves while its native outline remains fixed. Claim Blocks are
            // absent from this transient visual mesh, including neighbor occlusion only.
            return state.isAir() || state.getBlock() instanceof ClaimBlock
                    ? Shapes.empty()
                    : state.getShape(owner, p);
        }

        void build(long deadline) {
            var p = new BlockPos.MutableBlockPos();
            var neighbor = new BlockPos.MutableBlockPos();
            int checked = 0;
            while (chunk < chunks.size() && distances.size() < 1_000_000) {
                var c = chunks.get(chunk);
                if (section >= c.getSectionsCount()) {
                    chunk++;
                    section = cell = 0;
                    continue;
                }
                var data = c.getSection(section);
                if (data.hasOnlyAir() || cell >= 4096) {
                    section++;
                    cell = 0;
                    continue;
                }
                int at = cell++;
                int x = at & 15, z = at >> 4 & 15, y = at >> 8;
                var state = data.getBlockState(x, y, z);
                if (!state.isAir() && !(state.getBlock() instanceof ClaimBlock)) {
                    p.set(
                            c.getPos().getMinBlockX() + x,
                            c.getSectionYFromSectionIndex(section) * 16 + y,
                            c.getPos().getMinBlockZ() + z);
                    shapeProbe.set(0, shape(p));
                    for (var direction : DIRECTIONS)
                        shapeProbe.set(
                                direction.ordinal() + 1,
                                shape(neighbor.setWithOffset(p, direction)));
                    var faces = surfaces.get(shapeProbe);
                    if (faces == null) {
                        faces =
                                ClaimSurface.faces(
                                        shapeProbe.getFirst(),
                                        direction -> shapeProbe.get(direction.ordinal() + 1));
                        if (surfaces.size() < 4096) surfaces.put(List.copyOf(shapeProbe), faces);
                    }
                    float distance = (float) Math.sqrt(p.distSqr(key.origin));
                    float dx = p.getX() - key.origin.getX(),
                            dy = p.getY() - key.origin.getY(),
                            dz = p.getZ() - key.origin.getZ();
                    for (var face : faces) {
                        if (distances.size() >= 1_000_000) break;
                        var b = face.bounds();
                        rectangles.add(dx + (float) b.minX);
                        rectangles.add(dy + (float) b.minY);
                        rectangles.add(dz + (float) b.minZ);
                        rectangles.add(dx + (float) b.maxX);
                        rectangles.add(dy + (float) b.maxY);
                        rectangles.add(dz + (float) b.maxZ);
                        masks.add((byte) face.direction().ordinal());
                        distances.add(distance);
                    }
                }
                if (++checked % 16 == 0 && System.nanoTime() >= deadline) break;
            }
        }
    }

    public static void receive(ClaimNetworking.Wave packet) {
        if (packet.action() == ClaimNetworking.CANCEL_ALL
                || !NPShipsEditorConfig.claimWavesEnabled()) {
            clear();
            return;
        }
        if (packet.action() != ClaimNetworking.PREPARE
                && packet.action() != ClaimNetworking.IMPACT
                && packet.action() != ClaimNetworking.CANCEL) return;
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(packet.dimension())) return;
        if (owner != level) {
            WAVES.clear();
            owner = level;
        }
        var key = new Key(packet.body(), packet.origin());
        if (packet.action() == ClaimNetworking.CANCEL) {
            WAVES.remove(key);
            return;
        }
        var body = SubLevelContainer.getContainer(level).getSubLevel(packet.body());
        if (!(body instanceof ClientSubLevel client) || body.isRemoved()) return;
        if (!WAVES.containsKey(key)) {
            if (WAVES.size() >= 4) WAVES.remove(WAVES.keySet().iterator().next());
            WAVES.put(key, new Wave(key, client, level.getGameTime()));
        }
        if (packet.action() == ClaimNetworking.IMPACT) WAVES.get(key).impact = packet.tick();
    }

    @SubscribeEvent
    public static void disconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private static void clear() {
        WAVES.clear();
        owner = null;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!NPShipsEditorConfig.claimWavesEnabled()) {
            clear();
            return;
        }
        var level = Minecraft.getInstance().level;
        if (owner != level) {
            WAVES.clear();
            owner = level;
        }
        if (level == null) return;
        long deadline = System.nanoTime() + 3_000_000;
        var it = WAVES.values().iterator();
        while (it.hasNext()) {
            var wave = it.next();
            var body = SubLevelContainer.getContainer(level).getSubLevel(wave.key.body);
            if (body == null
                    || body.isRemoved()
                    || level.getGameTime() - wave.created > 100
                    || wave.impact >= 0 && level.getGameTime() - wave.impact > 30) {
                it.remove();
                continue;
            }
            if (System.nanoTime() < deadline) wave.build(deadline);
        }
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!NPShipsEditorConfig.claimWavesEnabled()) {
            clear();
            return;
        }
        if (owner == null) return;
        var mc = Minecraft.getInstance();
        if (mc.level != owner) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var buffers = mc.renderBuffers().bufferSource();
        var type = RenderType.debugQuads();
        for (var wave : WAVES.values()) {
            if (wave.impact < 0) continue;
            double elapsed = owner.getGameTime() + partial - wave.impact;
            if (elapsed < 0 || elapsed > 30) continue;
            var sub = SubLevelContainer.getContainer(owner).getSubLevel(wave.key.body);
            if (!(sub instanceof ClientSubLevel body) || body.isRemoved()) continue;
            var transform = body.renderPose(partial);
            var origin = wave.key.origin;
            var world =
                    transform.transformPosition(
                            new Vector3d(origin.getX(), origin.getY(), origin.getZ()));
            var camera = event.getCamera().getPosition();
            var pose = event.getPoseStack();
            pose.pushPose();
            pose.translate(world.x - camera.x, world.y - camera.y, world.z - camera.z);
            pose.mulPose(new Quaternionf(transform.orientation()));
            pose.scale(
                    (float) transform.scale().x(),
                    (float) transform.scale().y(),
                    (float) transform.scale().z());
            var consumer = buffers.getBuffer(type);
            for (int i = 0; i < wave.distances.size(); i++) {
                double age = elapsed - wave.distances.getFloat(i) / wave.radius * 20;
                if (age < 0 || age > 8) continue;
                float alpha = (float) (.48 * (1 - age / 8));
                face(
                        consumer,
                        pose.last().pose(),
                        wave.rectangles,
                        i * 6,
                        DIRECTIONS[wave.masks.getByte(i)],
                        alpha);
            }
            pose.popPose();
        }
        buffers.endBatch(type);
    }

    private static void face(
            VertexConsumer out,
            org.joml.Matrix4f matrix,
            FloatArrayList rectangles,
            int at,
            Direction direction,
            float alpha) {
        int axis = direction.getAxis().ordinal(), u = (axis + 1) % 3, v = (axis + 2) % 3;
        float offset =
                direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? .003f : -.003f;
        for (int i = 0; i < 4; i++) {
            int du = i == 1 || i == 2 ? 3 : 0, dv = i >= 2 ? 3 : 0;
            float px =
                    rectangles.getFloat(at + (axis == 0 ? 0 : u == 0 ? du : dv))
                            + (axis == 0 ? offset : 0);
            float py =
                    rectangles.getFloat(at + 1 + (axis == 1 ? 0 : u == 1 ? du : dv))
                            + (axis == 1 ? offset : 0);
            float pz =
                    rectangles.getFloat(at + 2 + (axis == 2 ? 0 : u == 2 ? du : dv))
                            + (axis == 2 ? offset : 0);
            out.addVertex(matrix, px, py, pz).setColor(.2f, 1f, .02f, alpha);
        }
    }
}
