package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchExecutionContext;
import com.eynny.npships.api.patches.PatchStage;

/**
 * Optional per-provider demand for an existing source-unit pass. Borrowed execution expires after
 * this decision; absent capability means an explicitly declared stage has work. No new phase.
 */
public interface PatchSourcePass {
    boolean needed(PatchStage stage, PatchExecutionContext execution);
}
