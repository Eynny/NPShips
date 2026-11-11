package com.eynny.npships.integration;

import com.eynny.npships.integration.aeronautics.*;
import com.eynny.npships.integration.cannons.CannonWelds;

import net.minecraft.nbt.*;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Mandatory native ID sanitation. It never restores an administratively disabled device. */
final class NativeIdentityData {
    private NativeIdentityData() {}

    static void kinetics(BlockEntity entity, CompoundTag data) throws Exception {
        if (entity instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity) {
            resetKinetics(data);
            // Simulated exposes the nested kinetic channel's NBT key via its API.
            try {
                String key =
                        (String)
                                entity.getClass()
                                        .getMethod("getExtraKineticsSaveName")
                                        .invoke(entity);
                if (data.contains(key, Tag.TAG_COMPOUND)) resetKinetics(data.getCompound(key));
            } catch (NoSuchMethodException ignored) {
                // Ordinary Create blocks have no additional kinetic channel.
            }
            // CBC owns two additional kinetic interfaces in nested tags.
            if (data.contains("YawInterface")) resetKinetics(data.getCompound("YawInterface"));
            if (data.contains("PitchInterface")) resetKinetics(data.getCompound("PitchInterface"));
        }
    }

    static boolean sanitize(
            String type, String sourceName, CompoundTag data, boolean unpatched, boolean ropeEnd) {
        if (type.equals("simulated_docking") && DockingPatch.matches(sourceName))
            DockingPatch.strip(data);
        if (type.equals("simulated_spring") && SpringPatch.matches(sourceName))
            SpringPatch.strip(data);
        if (type.equals("create_devices") && authoredBlock(sourceName, data, null)) return true;
        if (type.equals("radars_network")
                && unpatched
                && sourceName.equals("create_radar:monitor")) {
            data.remove("Controller");
            data.remove("Size");
        }
        if (type.equals("simulated_rope") && ropeEnd) {
            AeronauticsPatch.stripRope(data);
            return true;
        }
        if (type.equals("cbc_welds")) data.remove(CannonWelds.KEY);
        return false;
    }

    private static void resetKinetics(CompoundTag data) {
        // Network/source IDs belong to the original world. Native generators retain their
        // configuration and reconnect after placement; inherited speeds must not fight them.
        data.remove("Source");
        data.remove("Network");
        data.remove("Sequence");
        data.remove("ConnectedToExtraKinetics");
        data.putFloat("Speed", 0);
        data.putBoolean("NeedsSpeedUpdate", true);
    }

    static void entityDefaults(net.minecraft.world.entity.Entity entity) {
        if (entity
                instanceof
                com.simibubi.create.content.equipment.blueprint.BlueprintEntity blueprint)
            blueprint.readAdditionalSaveData(new CompoundTag());
    }

    static boolean authoredBlock(String name, CompoundTag data, CompoundTag visual) {
        if (name.equals("create:creative_motor") && !data.contains("ScrollValue"))
            data.putInt("ScrollValue", 16);
        if (visual != null) {
            data.put("Material", visual.copy());
            // Create validates its consumed item against the saved visual on server load.
            var item = new CompoundTag();
            item.putString(
                    "id",
                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(
                                    net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                            .get(
                                                    net.minecraft.resources.ResourceLocation.parse(
                                                            visual.getString("Name")))
                                            .asItem())
                            .toString());
            item.putInt("count", 1);
            data.put("Item", item);
        }
        return name.equals("create:creative_motor") || visual != null;
    }

    static void blockSettings(
            String name, CompoundTag before, CompoundTag after, CompoundTag data) {
        if (name.equals("create:display_board")) {
            for (var key : java.util.List.copyOf(data.getAllKeys()))
                if ((key.startsWith("CustomLine") || key.startsWith("GlowingLine"))
                        && !data.getBoolean(key)) data.remove(key);
        }
        if (name.endsWith("nixie_tube")
                && !java.util.Objects.equals(before.get("CustomText"), after.get("CustomText"))) {
            if (after.contains("CustomText"))
                data.putString("RawCustomText", after.getString("CustomText"));
            else data.remove("RawCustomText");
        }
    }
}
