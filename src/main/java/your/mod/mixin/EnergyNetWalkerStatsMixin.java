package your.mod.mixin;

import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNet;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNetWalker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import your.mod.energy.EnergyNetDebugStats;

/**
 * Counts energynet route rebuilds (createNetData calls).
 */
@Mixin(value = EnergyNetWalker.class, remap = false)
public abstract class EnergyNetWalkerStatsMixin {

    @Inject(method = "createNetData", at = @At("HEAD"))
    private static void gtceuHotfix$countNetRebuild(EnergyNet net, BlockPos pipePos,
                                                   CallbackInfoReturnable<?> cir) {
        if (net == null) return;
        final Level level = net.getLevel();
        if (level == null) return;
        EnergyNetDebugStats.recordNetRebuild(net, level);
    }
}
