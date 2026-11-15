package com.eynny.npships.api.client.editor;

import com.eynny.npships.api.patches.PatchAuthoring;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** CLIENT-only, fresh per open editor. Addon owns UI/drafts; the host alone accepts history. */
public interface EditorExtension extends AutoCloseable {
    /**
     * Notifies this fresh owner that its editor session has opened.
     *
     * @param session callback-scoped CLIENT editor session
     */
    default void opened(Session session) {}

    /**
     * Called on activation and layout changes. Add only privately owned native widgets.
     *
     * @param session callback-scoped CLIENT editor session
     * @param panel callback-scoped layout owner
     */
    default void layout(Session session, Panel panel) {}

    /**
     * Notifies activation or deactivation of this contribution.
     *
     * @param session callback-scoped CLIENT editor session
     * @param selected whether this contribution is active
     */
    default void selected(Session session, boolean selected) {}

    /**
     * Notifies a host document or selection change.
     *
     * @param session callback-scoped CLIENT editor session
     * @param change host change category
     */
    default void changed(Session session, Change change) {}

    /**
     * Selected extension input, after host modal controls and before native scene tools.
     *
     * @param session callback-scoped CLIENT editor session
     * @param input routed native input
     * @return whether this input was consumed
     */
    default boolean input(Session session, Input input) {
        return false;
    }

    /**
     * Escape/tool switch cancels temporary interaction; it does not undo accepted changes.
     *
     * @param session callback-scoped CLIENT editor session
     */
    default void cancel(Session session) {}

    /**
     * Called only for the selected extension, in the native scene camera; never world ticking.
     *
     * @param session callback-scoped CLIENT editor session
     * @param preview callback-scoped isolated preview access
     * @throws Exception if addon rendering fails; the host isolates the contribution
     */
    default void preview(Session session, Preview preview) throws Exception {}

    /**
     * Explicit optional capability, sampled at activation; UI-only tabs incur no scene callback.
     * Scene factories also resample after document/resource invalidation, never every frame.
     *
     * @return whether the owner requests preview callbacks
     */
    default boolean hasPreview() {
        return false;
    }

    /**
     * Release addon native resources here; called before the next preview using a new epoch.
     *
     * @param session callback-scoped CLIENT editor session
     * @param generation new native resource generation
     */
    default void resourcesChanged(Session session, long generation) {}

    /**
     * Releases this contribution after active callbacks have unwound.
     *
     * @throws Exception if addon resource disposal fails
     */
    @Override
    default void close() throws Exception {}

    /** Bounded to this screen activation and CLIENT thread; retaining it cannot extend lifetime. */
    interface Session {
        /**
         * Resolves optional host views only during this owner's active callback. Unavailable views
         * are explicit; a borrowed view must not outlive that callback. This is not permission to
         * retain native objects or mutate host state. Existing custom sessions offer no views.
         *
         * @param <T> requested capability type
         * @param contract requested host contract
         * @return callback-scoped capability, or empty when unavailable
         */
        default <T> java.util.Optional<T> findCapability(Class<T> contract) {
            java.util.Objects.requireNonNull(contract);
            return java.util.Optional.empty();
        }

        /**
         * Reads the current opaque document revision.
         *
         * @return opaque current owner revision
         */
        UUID revision();

        /**
         * Inspects callback-local provider views without accepting an edit.
         *
         * @param <T> detached inspection result type
         * @param read synchronous inspector; do not retain its context
         * @return detached result chosen by the caller
         */
        <T> T inspect(Function<PatchAuthoring.Context, T> read);

        /**
         * Proposes one provider-owned transaction against an expected revision.
         *
         * @param expectedRevision opaque revision against which the transaction is proposed
         * @param edit synchronous provider-local proposal callback
         * @return typed acceptance or refusal result
         */
        Result submit(
                UUID expectedRevision,
                Function<PatchAuthoring.Context, PatchAuthoring.Proposal> edit);

        /**
         * Reads a detached local selection snapshot.
         *
         * @return detached local selection and authored roots
         */
        Selection selection();

        /**
         * Sets whether this contribution owns a temporary tool interaction.
         *
         * @param active whether this contribution owns the interaction
         */
        void toolActive(boolean active);

        /**
         * Reads whether this contribution owns a temporary tool interaction.
         *
         * @return whether this contribution owns a temporary tool interaction
         */
        boolean toolActive();

        /**
         * Panel-local popup input ownership; host modals still have precedence.
         *
         * @param active whether this contribution owns the interaction
         */
        void modal(boolean active);

        /**
         * Observes this contribution panel-local popup input ownership.
         *
         * @return whether this contribution owns panel-local popup input
         */
        boolean modal();

        /**
         * Reflow privately owned widgets after the selected contribution's input callback; no new
         * history entry. Observation callbacks such as changed, layout and preview cannot request a
         * reflow. They may update privately owned widget values without calling this method.
         *
         * @throws IllegalStateException outside the selected input callback or during submission
         */
        void refresh();

        /**
         * Displays contribution feedback through the host message owner.
         *
         * @param message user-facing feedback component
         * @param error whether feedback represents a failure
         */
        void feedback(Component message, boolean error);
    }

    /**
     * The document view inside inspect/submit expires at callback return, including iterators.
     *
     * @param status typed observation or transaction outcome
     * @param revision opaque owner revision
     * @param message user-facing feedback component
     */
    record Result(Status status, UUID revision, Component message) {
        /**
         * Tests whether the transaction was accepted or already unchanged.
         *
         * @return true for ACCEPTED or UNCHANGED
         */
        public boolean accepted() {
            return status == Status.ACCEPTED || status == Status.UNCHANGED;
        }
    }

    /** Outcome of submitting a provider proposal to the current editor. */
    enum Status {
        /** The owner accepted the complete proposal. */
        ACCEPTED,
        /** The proposal already matches current owner state. */
        UNCHANGED,
        /** The expected revision no longer matches. */
        STALE,
        /** The contribution lifetime has ended. */
        CLOSED,
        /** The host could not admit this operation. */
        REFUSED,
        /** The callback or host validation failed. */
        FAILED
    }

    /** Host change that invalidates an extension observation or draft. */
    enum Change {
        /** Document. */
        DOCUMENT,
        /** Selection. */
        SELECTION
    }

    /**
     * Detached local block selection and stable authored root identities.
     *
     * @param primary primary local cell, or null
     * @param first first local selection corner, or null
     * @param second second local selection corner, or null
     * @param entityRoots stable authored root identities
     */
    record Selection(
            @Nullable BlockPos primary,
            @Nullable BlockPos first,
            @Nullable BlockPos second,
            List<UUID> entityRoots) {
        /**
         * Copies mutable local coordinates and root identities.
         *
         * @param primary primary local cell, or null
         * @param first first local selection corner, or null
         * @param second second local selection corner, or null
         * @param entityRoots stable authored root identities
         */
        public Selection {
            primary = primary == null ? null : primary.immutable();
            first = first == null ? null : first.immutable();
            second = second == null ? null : second.immutable();
            entityRoots = List.copyOf(entityRoots);
        }
    }

    /**
     * Panel-local pixel rectangle with exclusive maximum edges.
     *
     * @param x left pixel coordinate
     * @param y top pixel coordinate
     * @param width rectangle width in pixels
     * @param height rectangle height in pixels
     */
    record Bounds(int x, int y, int width, int height) {
        /**
         * Tests a point using exclusive right and bottom edges.
         *
         * @param px horizontal point coordinate
         * @param py vertical point coordinate
         * @return whether the point lies inside this pixel rectangle
         */
        public boolean contains(double px, double py) {
            return px >= x && py >= y && px < (long) x + width && py < (long) y + height;
        }
    }

    /** Callback-scoped layout owner. Widgets are never installed in unrelated host containers. */
    interface Panel {
        /**
         * Reads the current panel-local pixel rectangle.
         *
         * @return panel-local pixel bounds
         */
        Bounds bounds();

        /**
         * Installs a privately owned widget in this contribution panel.
         *
         * @param <T> privately owned widget type
         * @param ownedWidget privately owned widget
         * @return the supplied privately owned widget
         */
        <T extends AbstractWidget> T add(T ownedWidget);

        /**
         * Declares scrollable content height for this layout callback.
         *
         * @param pixels scrollable content height in pixels
         */
        void contentHeight(int pixels);
    }

    /** Selected-extension input subject to host modal and focus ownership. */
    sealed interface Input permits Mouse, Key, Text {}

    /** Native mouse operation routed to the selected contribution. */
    enum MouseAction {
        /** Press. */
        PRESS,
        /** Drag. */
        DRAG,
        /** Release. */
        RELEASE,
        /** Scroll. */
        SCROLL
    }

    /**
     * Panel-local native mouse input and optional scene picking ray.
     *
     * @param action native mouse operation
     * @param x panel-local horizontal pointer coordinate
     * @param y panel-local vertical pointer coordinate
     * @param button native mouse button index
     * @param modifiers native modifier bit mask
     * @param dx horizontal drag delta
     * @param dy vertical drag delta
     * @param scroll native scroll amount
     * @param ray schematic-local picking ray, or null outside the scene
     */
    record Mouse(
            MouseAction action,
            double x,
            double y,
            int button,
            int modifiers,
            double dx,
            double dy,
            double scroll,
            @Nullable Ray ray)
            implements Input {}

    /**
     * Native key press or release with modifier state.
     *
     * @param key native key code
     * @param scanCode native keyboard scan code
     * @param modifiers native modifier bit mask
     * @param pressed true for key press, false for release
     */
    record Key(int key, int scanCode, int modifiers, boolean pressed) implements Input {}

    /**
     * Native typed character with modifier state.
     *
     * @param character typed character
     * @param modifiers native modifier bit mask
     */
    record Text(char character, int modifiers) implements Input {}

    /**
     * Detached schematic-local picking segment.
     *
     * @param from local segment start
     * @param to local segment end
     */
    record Ray(Vec3 from, Vec3 to) {}

    /**
     * Detached local-to-screen matrix and viewport; no live camera, Level or Screen reference.
     *
     * @param localToScreen detached local-to-screen matrix
     * @param viewport pixel viewport
     */
    record Camera(Matrix4f localToScreen, Bounds viewport) {
        /**
         * Copies the supplied projection matrix.
         *
         * @param localToScreen detached local-to-screen matrix
         * @param viewport pixel viewport
         */
        public Camera {
            localToScreen = new Matrix4f(localToScreen);
        }

        /**
         * Copies the detached local-to-screen matrix.
         *
         * @return a fresh matrix owned by the caller
         */
        @Override
        public Matrix4f localToScreen() {
            return new Matrix4f(localToScreen);
        }
    }

    /** Callback-scoped detached pose/private output. Native references must not be retained. */
    interface Preview {
        /**
         * Creates privately posed native graphics for APIs requiring GuiGraphics. It shares only
         * this callback's isolated buffers, copies the current pose/normal matrix and exposes no
         * Screen. Do not retain it. Existing custom previews must supply a native BufferSource.
         *
         * @return callback-scoped privately posed native graphics
         * @throws UnsupportedOperationException if this custom preview has no native buffer owner
         */
        default net.minecraft.client.gui.GuiGraphics graphics() {
            if (!(buffers()
                    instanceof
                    net.minecraft.client.renderer.MultiBufferSource.BufferSource nativeBuffers))
                throw new UnsupportedOperationException("Native preview graphics are unavailable");
            var graphics =
                    new net.minecraft.client.gui.GuiGraphics(
                            net.minecraft.client.Minecraft.getInstance(), nativeBuffers);
            graphics.pose().mulPose(pose().last().pose());
            graphics.pose().last().normal().set(pose().last().normal());
            return graphics;
        }

        /**
         * Reads the host's current local cell visibility, including the active layer/slice mask.
         * Custom hosts without a visibility capability retain their unrestricted view.
         *
         * @param position schematic-local cell
         * @return whether this cell participates in the current preview
         */
        default boolean visible(BlockPos position) {
            java.util.Objects.requireNonNull(position);
            return true;
        }

        /**
         * Borrows the isolated pose stack for this preview callback.
         *
         * @return callback-scoped isolated pose stack
         */
        PoseStack pose();

        /**
         * Borrows isolated native output for this preview callback.
         *
         * @return callback-scoped isolated native output
         */
        MultiBufferSource buffers();

        /**
         * Reads detached camera and viewport information.
         *
         * @return detached camera snapshot
         */
        Camera camera();

        /**
         * Reads the actual ghost flag supplied by the host.
         *
         * @return actual host-supplied ghost flag
         */
        boolean ghost();

        /**
         * Reads the resource epoch of this preview callback.
         *
         * @return native resource generation
         */
        long resourceGeneration();
    }
}
