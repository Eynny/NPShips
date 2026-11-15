package com.eynny.npships.commands;

import com.eynny.npships.ship.SchematicNames;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.List;

/** Registered on both sides so uppercase names and namespaces parse identically in multiplayer. */
public final class SchematicArgument implements ArgumentType<ResourceLocation> {
    private static final DynamicCommandExceptionType INVALID =
            new DynamicCommandExceptionType(message -> Component.literal(String.valueOf(message)));

    @Override
    public ResourceLocation parse(final StringReader reader) throws CommandSyntaxException {
        final int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        try {
            return SchematicNames.parse(reader.getString().substring(start, reader.getCursor()));
        } catch (IllegalArgumentException exception) {
            reader.setCursor(start);
            throw INVALID.createWithContext(reader, exception.getMessage());
        }
    }

    public static ResourceLocation getId(final CommandContext<?> context, final String name) {
        return context.getArgument(name, ResourceLocation.class);
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("MyShip", "npships:MyShip", "Fleet/MyShip");
    }
}
