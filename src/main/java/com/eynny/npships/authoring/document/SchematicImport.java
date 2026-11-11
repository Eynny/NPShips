package com.eynny.npships.authoring.document;

import com.eynny.npships.authoring.EmbeddedLoot;
import com.eynny.npships.authoring.LootAssignments;
import com.eynny.npships.authoring.StructureSegments;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.google.gson.*;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/** Fixed-volume replacement of base geometry, entity data, loot and internal patch links. */
public final class SchematicImport {
    private SchematicImport() {}

    public static SchematicDocument paste(
            SchematicDocument target,
            SchematicDocument source,
            BlockPos from,
            BlockPos size,
            BlockPos to) {
        if (SchematicExtensions.present(source))
            SchematicExtensions.refuse(
                    source, "partial-document import; use supported document-region copy instead");
        if (SchematicExtensions.present(target))
            SchematicExtensions.refuse(target, "partial-document import into provider-owned data");
        var sourceTag = source.nbtCopy();
        SchematicPatches.embed(sourceTag, source.configCopy());
        var part =
                StructureSegments.crop(
                        new SchematicDocument(sourceTag, source.configCopy()), from, size);
        // Reject severed device connections before they can accidentally bind to unrelated
        // destination blocks.
        SchematicPatches.validate(part);
        String id = UUID.randomUUID().toString();
        var config = target.configCopy();
        var tables = EmbeddedLoot.read(source.configCopy());
        var importedLoot = new ArrayList<LootAssignments.Entry>();
        for (var entry : StructureSegments.cropLoot(source.configCopy(), from, size)) {
            var table = entry.table();
            if (tables.has(table.toString())) {
                var scoped =
                        ResourceLocation.parse(
                                "npships:local/import_" + id + "/" + table.getPath());
                config =
                        EmbeddedLoot.assign(
                                config,
                                scoped.toString(),
                                tables.getAsJsonObject(table.toString()));
                table = scoped;
            }
            importedLoot.add(new LootAssignments.Entry(entry.pos(), table, entry.rolls()));
        }
        var tag = target.nbtCopy();
        SchematicPatches.embed(tag, config);
        var originalVariants = tag.getCompound(StructureSegments.VARIANTS).copy();
        var variants = tag.getCompound(StructureSegments.VARIANTS);
        variants.put(id, part);
        tag.put(StructureSegments.VARIANTS, variants);
        var segment =
                new StructureSegments.Segment(
                        id,
                        to,
                        size,
                        true,
                        List.of(new StructureSegments.Variant(id, id, 1, importedLoot)),
                        UUID.fromString(id));
        var composition =
                StructureSegments.compose(
                        tag,
                        List.of(segment),
                        LootAssignments.read(config),
                        new StructureSegments.Plan(Map.of(id, id)));
        tag = composition.nbt();
        if (!originalVariants.isEmpty()) tag.put(StructureSegments.VARIANTS, originalVariants);
        for (var entry : LootAssignments.read(config))
            config = LootAssignments.assign(config, entry.pos(), null);
        for (var entry : composition.loot())
            config = LootAssignments.assign(config, entry.pos(), entry.table(), entry.rolls());
        SchematicPatches.set(config, SchematicPatches.fromTemplate(tag));
        SchematicPatches.embed(tag, config);
        SchematicPatches.validate(tag);
        StructureSegments.validate(tag, StructureSegments.read(config));
        return new SchematicDocument(tag, config);
    }
}
