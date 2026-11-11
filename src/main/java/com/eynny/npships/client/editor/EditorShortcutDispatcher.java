package com.eynny.npships.client.editor;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;

import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

/** Native mappings plus one Screen-owned router; no document, tool or draft state. */
public final class EditorShortcutDispatcher {
    public static final String CATEGORY = "npships.editor.keys.category";
    public static final IKeyConflictContext CONTEXT =
            new IKeyConflictContext() {
                @Override
                public boolean isActive() {
                    var mc = Minecraft.getInstance();
                    return mc.isWindowActive()
                            && mc.screen instanceof SchematicEditorScreen s
                            && s.shortcutContextAvailable();
                }

                @Override
                public boolean conflicts(IKeyConflictContext other) {
                    return other == this
                            || other == KeyConflictContext.GUI
                            || other == KeyConflictContext.UNIVERSAL;
                }
            };

    public enum Action {
        SELECT(GLFW.GLFW_KEY_V),
        CREATE(GLFW.GLFW_KEY_B),
        DELETE_MODE(GLFW.GLFW_KEY_X),
        BLOCKS(GLFW.GLFW_KEY_1),
        ZONES(GLFW.GLFW_KEY_5),
        ENTITIES(GLFW.GLFW_KEY_3),
        SEGMENTS(GLFW.GLFW_KEY_4),
        LOOT(GLFW.GLFW_KEY_6),
        PATCHES(GLFW.GLFW_KEY_7),
        GLUE(GLFW.GLFW_KEY_8),
        BLOCKS_AREAS(GLFW.GLFW_KEY_2),
        ROTATE_BRUSH_BACKWARD(GLFW.GLFW_KEY_Q),
        ROTATE_BRUSH_FORWARD(GLFW.GLFW_KEY_E),
        SLICE_PREVIOUS(GLFW.GLFW_KEY_PAGE_DOWN),
        SLICE_NEXT(GLFW.GLFW_KEY_PAGE_UP),
        CAMERA(GLFW.GLFW_KEY_F5),
        WORKSPACE(GLFW.GLFW_KEY_F4),
        SNBT_REFERENCE(GLFW.GLFW_KEY_F1);
        public final KeyMapping mapping;

        Action(int key) {
            mapping =
                    new KeyMapping(
                            "npships.editor.keys." + name().toLowerCase(java.util.Locale.ROOT),
                            CONTEXT,
                            KeyModifier.NONE,
                            InputConstants.Type.KEYSYM,
                            key,
                            CATEGORY);
        }
    }

    public static void register(RegisterKeyMappingsEvent event) {
        for (var action : Action.values()) event.register(action.mapping);
    }

    static Component binding(Action action) {
        var mapping = action.mapping;
        if (mapping.isUnbound()) return Component.translatable("npships.editor.keys.unbound");
        var bound = mapping.getKey();
        var key = mapping.getKeyModifier().getCombinedName(bound, () -> currentKeyName(bound));
        return mapping.getKey().getType() == InputConstants.Type.MOUSE
                ? Component.translatable("npships.editor.keys.mouse_inactive", key)
                : key;
    }

    /**
     * Printable names follow the current OS layout instead of InputConstants' memoized first name.
     */
    static Component currentKeyName(InputConstants.Key bound) {
        String printable =
                bound.getType() == InputConstants.Type.MOUSE
                        ? null
                        : GLFW.glfwGetKeyName(
                                bound.getType() == InputConstants.Type.KEYSYM
                                        ? bound.getValue()
                                        : GLFW.GLFW_KEY_UNKNOWN,
                                bound.getType() == InputConstants.Type.SCANCODE
                                        ? bound.getValue()
                                        : 0);
        return printable == null
                ? bound.getDisplayName()
                : Component.literal(printable.toUpperCase(java.util.Locale.ROOT));
    }

    static Component hint(Component original, Action... actions) {
        var text = original.copy();
        for (var action : actions)
            text.append("\n")
                    .append(
                            Component.translatable("npships.editor.keys.hint", binding(action))
                                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        return text;
    }

    static void hint(
            com.eynny.npships.client.editor.widget.EditorIconButton button, Action... actions) {
        button.hint(() -> hint(button.baseHint(), actions));
    }

    private final SchematicEditorScreen screen;
    private final Set<Integer> heldAtEntry = new HashSet<>();
    private final Set<Integer> unknownScansAtEntry = new HashSet<>();
    private final Set<Integer> edgeOwned = new HashSet<>();
    private EditorKeyboardInput.Event claimed;
    private int characterKey = -1;
    private boolean commandCharacter;
    private boolean active;

    EditorShortcutDispatcher(SchematicEditorScreen screen) {
        this.screen = screen;
    }

    void reset() {
        active = false;
        heldAtEntry.clear();
        unknownScansAtEntry.clear();
        edgeOwned.clear();
        claimed = null;
        characterKey = -1;
        commandCharacter = false;
    }

    void focus() {
        var mc = Minecraft.getInstance();
        if (!mc.isWindowActive()) {
            reset();
            return;
        }
        long window = mc.getWindow().getWindow();
        if (!active) {
            active = true;
            // Polling only release-arms already-held keys; it never runs a command.
            for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++)
                if (InputConstants.isKeyDown(window, key)) heldAtEntry.add(key);
            // GLFW cannot poll an unknown keysym. Conservatively release-arm such scan events.
            for (var action : Action.values()) {
                var key = action.mapping.getKey();
                if (key.getType() == InputConstants.Type.SCANCODE)
                    unknownScansAtEntry.add(key.getValue());
            }
        } else heldAtEntry.removeIf(key -> !InputConstants.isKeyDown(window, key));
    }

    void released(int key, int scan) {
        heldAtEntry.remove(key);
        unknownScansAtEntry.remove(scan);
        edgeOwned.remove(key);
        if (characterKey == key) {
            characterKey = -1;
            commandCharacter = false;
        }
    }

    boolean character() {
        boolean consume = commandCharacter;
        commandCharacter = false;
        return consume;
    }

    boolean repeatedOwner(int key, int scan, int modifiers) {
        var e = EditorKeyboardInput.event(screen, key, scan, modifiers);
        if (e != null && e == claimed) return true;
        if (e != null && e.action() == GLFW.GLFW_PRESS) {
            edgeOwned.remove(key);
            commandCharacter = false;
        }
        if (e != null && e.action() == GLFW.GLFW_REPEAT && characterKey == key)
            commandCharacter = true;
        return e != null && e.action() == GLFW.GLFW_REPEAT && edgeOwned.contains(key);
    }

    boolean edge(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_SPACE) {
            characterKey = key;
            commandCharacter = true;
        }
        edgeOwned.add(key);
        if (!initial(key, scan, modifiers)) return false;
        claimed = EditorKeyboardInput.event(screen, key, scan, modifiers);
        return true;
    }

    boolean initial(int key, int scan, int modifiers) {
        var e = EditorKeyboardInput.event(screen, key, scan, modifiers);
        return Minecraft.getInstance().isWindowActive()
                && e != null
                && Minecraft.getInstance().screen == screen
                && e != claimed
                && e.action() == GLFW.GLFW_PRESS
                && !heldAtEntry.contains(key)
                && (key != GLFW.GLFW_KEY_UNKNOWN || !unknownScansAtEntry.contains(scan));
    }

    boolean dispatch(int key, int scan, int modifiers) {
        return dispatch(key, scan, modifiers, false);
    }

    boolean dispatchPresentation(int key, int scan, int modifiers) {
        return dispatch(key, scan, modifiers, true);
    }

    private boolean dispatch(int key, int scan, int modifiers, boolean presentation) {
        Action match = null;
        for (var action : Action.values()) {
            if (!matches(action.mapping, key, scan, modifiers)
                    || !screen.shortcutApplicable(action)) continue;
            if (match != null) {
                characterKey = key;
                commandCharacter = true;
                if (edge(key, scan, modifiers))
                    screen.status(
                            Component.translatable(
                                            "npships.editor.keys.conflict",
                                            match.mapping.getDisplayName(),
                                            action.mapping.getDisplayName())
                                    .getString());
                return true;
            }
            match = action;
        }
        if (match == null) return false;
        if (presentation && match != Action.WORKSPACE && match != Action.SNBT_REFERENCE)
            return false;
        // Validation may focus a field before GLFW sends this command's character callback.
        characterKey = key;
        commandCharacter = true;
        if (edge(key, scan, modifiers)) screen.shortcut(match);
        return true;
    }

    static boolean matches(KeyMapping mapping, int key, int scan, int modifiers) {
        var bound = mapping.getKey();
        if (bound == InputConstants.UNKNOWN || bound.getType() == InputConstants.Type.MOUSE)
            return false;
        int expected =
                switch (mapping.getKeyModifier()) {
                    case NONE -> 0;
                    case CONTROL -> Minecraft.ON_OSX ? GLFW.GLFW_MOD_SUPER : GLFW.GLFW_MOD_CONTROL;
                    case SHIFT -> GLFW.GLFW_MOD_SHIFT;
                    case ALT -> GLFW.GLFW_MOD_ALT;
                };
        int mask =
                GLFW.GLFW_MOD_CONTROL
                        | GLFW.GLFW_MOD_SHIFT
                        | GLFW.GLFW_MOD_ALT
                        | GLFW.GLFW_MOD_SUPER;
        if ((modifiers & mask) != expected) return false;
        var input =
                bound.getType()
                        .getOrCreate(bound.getType() == InputConstants.Type.SCANCODE ? scan : key);
        return mapping.isActiveAndMatches(input);
    }
}
