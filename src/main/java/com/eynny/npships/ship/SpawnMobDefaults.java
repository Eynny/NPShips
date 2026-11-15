package com.eynny.npships.ship;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Initialize only new zone mobs, once, then restore explicitly authored overrides. */
@EventBusSubscriber(modid = "npships")
public final class SpawnMobDefaults {
    private SpawnMobDefaults() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void joined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof Mob mob)
                || !mob.getPersistentData().contains("npships_initial_spawn")) return;
        var pending = mob.getPersistentData().getCompound("npships_initial_spawn").copy();
        mob.getPersistentData().remove("npships_initial_spawn");
        boolean equipmentEnvelope =
                pending.getInt("npships:format") == 2
                        && pending.contains("overrides", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        && pending.contains("equipment", net.minecraft.nbt.Tag.TAG_COMPOUND);
        var overrides = equipmentEnvelope ? pending.getCompound("overrides") : pending;
        var equipment = equipmentEnvelope ? pending.getCompound("equipment") : null;
        boolean persistent =
                !overrides.contains("PersistenceRequired")
                        || overrides.getBoolean("PersistenceRequired");
        overrides.remove("PersistenceRequired");
        mob.finalizeSpawn(
                level,
                level.getCurrentDifficultyAt(mob.blockPosition()),
                MobSpawnType.STRUCTURE,
                null);
        // Most generated crew use native defaults. Avoid a complete save/load cycle per mob.
        if (!overrides.isEmpty() || (!persistent && mob.isPersistenceRequired())) {
            var initialized = new CompoundTag();
            mob.saveWithoutId(initialized);
            initialized.merge(overrides);
            initialized.putBoolean("PersistenceRequired", persistent);
            mob.load(initialized);
        }
        if (equipment != null)
            for (var slot : com.eynny.npships.authoring.CrewEquipment.SLOTS)
                if (equipment.contains(slot, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    var item = equipment.getCompound(slot);
                    var stack =
                            item.isEmpty()
                                    ? net.minecraft.world.item.ItemStack.EMPTY
                                    : net.minecraft.world.item.ItemStack.parse(
                                                    level.registryAccess(), item)
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "Prevalidated generated equipment unavailable: "
                                                                            + slot));
                    var nativeSlot =
                            switch (slot) {
                                case "mainhand" ->
                                        net.minecraft.world.entity.EquipmentSlot.MAINHAND;
                                case "offhand" -> net.minecraft.world.entity.EquipmentSlot.OFFHAND;
                                case "feet" -> net.minecraft.world.entity.EquipmentSlot.FEET;
                                case "legs" -> net.minecraft.world.entity.EquipmentSlot.LEGS;
                                case "chest" -> net.minecraft.world.entity.EquipmentSlot.CHEST;
                                default -> net.minecraft.world.entity.EquipmentSlot.HEAD;
                            };
                    mob.setItemSlot(nativeSlot, stack);
                }
        mob.getPersistentData().remove("npships_initial_spawn");
        if (persistent) mob.setPersistenceRequired();
        // False survives finalizeSpawn too; no full save/load is needed for ordinary defaults.
    }
}
