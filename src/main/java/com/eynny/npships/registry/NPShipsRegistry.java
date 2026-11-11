package com.eynny.npships.registry;

import com.eynny.npships.NPShips;
import com.eynny.npships.block.ClaimBlockEntity;
import com.eynny.npships.commands.SchematicArgument;

import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class NPShipsRegistry {

    private NPShipsRegistry() {}

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, NPShips.MOD_ID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, NPShips.MOD_ID);

    public static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>>
            BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, NPShips.MOD_ID);

    public static final java.util.function.Supplier<
                    net.minecraft.world.level.block.entity.BlockEntityType<ClaimBlockEntity>>
            CLAIM_BLOCK_ENTITY =
                    BLOCK_ENTITIES.register(
                            "claim_block",
                            () ->
                                    net.minecraft.world.level.block.entity.BlockEntityType.Builder
                                            .of(
                                                    ClaimBlockEntity::new,
                                                    NPShipsRegistry.CLAIM_BLOCK.get())
                                            .build(null));

    public static final DeferredRegister<ArgumentTypeInfo<?, ?>> ARGUMENTS =
            DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, NPShips.MOD_ID);

    public static final net.neoforged.neoforge.registries.DeferredHolder<
                    ArgumentTypeInfo<?, ?>, SingletonArgumentInfo<SchematicArgument>>
            SCHEMATIC_ARGUMENT =
                    ARGUMENTS.register(
                            "schematic",
                            () ->
                                    ArgumentTypeInfos.registerByClass(
                                            SchematicArgument.class,
                                            SingletonArgumentInfo.contextFree(
                                                    SchematicArgument::new)));

    public static final java.util.function.Supplier<Block> SCHEMATIC_EDITOR =
            BLOCKS.register(
                    "schematic_editor",
                    () ->
                            new com.eynny.npships.block.SchematicEditorBlock(
                                    net.minecraft.world.level.block.state.BlockBehaviour.Properties
                                            .of()
                                            .strength(2.5f)
                                            .noOcclusion()
                                            .lightLevel(state -> 0)));
    public static final java.util.function.Supplier<Block> CLAIM_BLOCK =
            BLOCKS.register(
                    "claim_block",
                    () ->
                            new com.eynny.npships.block.ClaimBlock(
                                    net.minecraft.world.level.block.state.BlockBehaviour.Properties
                                            .ofFullCopy(
                                                    com.simibubi.create.AllBlocks.BLAZE_BURNER
                                                            .get())
                                            .noOcclusion()
                                            .lightLevel(state -> 0)));
    public static final java.util.function.Supplier<Item> SCHEMATIC_EDITOR_ITEM =
            ITEMS.register(
                    "schematic_editor",
                    () ->
                            new net.minecraft.world.item.BlockItem(
                                    SCHEMATIC_EDITOR.get(), new Item.Properties()));
    public static final java.util.function.Supplier<Item> CLAIM_BLOCK_ITEM =
            ITEMS.register(
                    "claim_block",
                    () ->
                            new net.minecraft.world.item.BlockItem(
                                    CLAIM_BLOCK.get(), new Item.Properties()));

    public static void register(IEventBus eventBus) {
        eventBus.addListener(NPShipsRegistry::creativeTab);

        BLOCKS.register(eventBus);
        BLOCK_ENTITIES.register(eventBus);
        ITEMS.register(eventBus);
        ARGUMENTS.register(eventBus);
    }

    private static void creativeTab(
            net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(SCHEMATIC_EDITOR_ITEM.get());
            event.accept(CLAIM_BLOCK_ITEM.get());
        }
    }
}
