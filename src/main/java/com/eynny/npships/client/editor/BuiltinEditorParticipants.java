package com.eynny.npships.client.editor;

/** Factories only; all native state belongs to the requesting editor activation. */
public final class BuiltinEditorParticipants {
    private BuiltinEditorParticipants() {}

    public static <T> T capability(String provider, Class<T> contract) {
        return contract == PatchEditorParticipant.Factory.class
                        && provider.equals("aeronautics_lifting_gas")
                ? contract.cast((PatchEditorParticipant.Factory) BuiltinBalloonEditor::new)
                : null;
    }
}
