package com.eynny.npships.integration;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.lang.reflect.InvocationTargetException;

/** Side-gated code loader. COMMON bytecode/signatures never link a native CLIENT class. */
public final class NativeClientCode {
    private NativeClientCode() {}

    public static <T> T capability(String provider, Class<T> contract) {
        if (FMLEnvironment.dist != Dist.CLIENT) return null;
        if (provider.startsWith("simulated_")
                && !net.neoforged.fml.ModList.get().isLoaded("simulated")) return null;
        if (provider.startsWith("aeronautics_")
                && !net.neoforged.fml.ModList.get().isLoaded("aeronautics")) return null;
        String owner =
                switch (contract.getName()) {
                    case "com.eynny.npships.client.editor.PatchNativeRenderStyle" ->
                            "com.eynny.npships.client.editor.BuiltinBlockInspectors";
                    case "com.eynny.npships.client.editor.PatchBlockAppearance" ->
                            "com.eynny.npships.client.editor.BuiltinBlockInspectors";
                    case "com.eynny.npships.client.editor.BlockInspectorContribution" ->
                            "com.eynny.npships.client.editor.BuiltinBlockInspectors";
                    case "com.eynny.npships.client.editor.PatchInspectorContribution" ->
                            "com.eynny.npships.client.editor.BuiltinPatchInspectors";
                    case "com.eynny.npships.client.editor.PatchEditorParticipant$Factory" ->
                            "com.eynny.npships.client.editor.BuiltinEditorParticipants";
                    case "com.eynny.npships.client.editor.PatchLinkContribution$Factory" ->
                            "com.eynny.npships.client.editor.BuiltinDeviceLinks";
                    case "com.eynny.npships.client.editor.PatchSpatialTool$Factory" ->
                            "com.eynny.npships.client.editor.BuiltinSpatialTools";
                    case "com.eynny.npships.client.editor.PatchTextWorkspace" ->
                            "com.eynny.npships.client.editor.BuiltinComputerWorkspaces";
                    case "com.eynny.npships.api.client.editor.EditorSceneFactory" ->
                            "com.eynny.npships.client.editor.BuiltinSceneContributions";
                    default -> null;
                };
        if (owner == null) return null;
        try {
            var factory = Class.forName(owner, true, contract.getClassLoader());
            return contract.cast(
                    factory.getMethod("capability", String.class, Class.class)
                            .invoke(null, provider, contract));
        } catch (InvocationTargetException error) {
            var cause = error.getCause();
            if (cause instanceof RuntimeException failure) throw failure;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("CLIENT capability failed: " + provider, cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("CLIENT capability unavailable: " + provider, error);
        }
    }
}
