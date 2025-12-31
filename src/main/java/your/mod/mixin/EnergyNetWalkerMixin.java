package your.mod.mixin;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.common.blockentity.CableBlockEntity;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNetWalker;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyRoutePath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nullable;

@Mixin(value = EnergyNetWalker.class, remap = false)
public abstract class EnergyNetWalkerMixin {
    private static final java.util.Set<Long> ENDPOINT_LOGGED = java.util.Collections.synchronizedSet(new java.util.HashSet<>());



    @org.spongepowered.asm.mixin.Unique private static boolean HOTFIX_LOG_ENERGYNET_WALKER = false;
    /**
     * GTCEu 7.4.0 field layout (decompiled):
     * - private final List<EnergyRoutePath> routes
     * - private CableBlockEntity[] pipes
     * - private int loss
     * and {@code ((EnergyNetWalker)(Object)this).getWalkedBlocks()} is inherited (public) from {@code PipeNetWalker}.
     *
     * Using the correct access modifiers/types here avoids Mixin AP
     * "Cannot find target for @Shadow" warnings.
     */
    @Shadow private CableBlockEntity[] pipes;
    @Shadow @Final private java.util.List<EnergyRoutePath> routes;
    @Shadow private int loss;
/**
     * Ensure FE neighbors are treated as endpoints by using GTCapabilityHelper.getEnergyContainer,
     * which our mixin extends to wrap Forge Energy storages.
     */
    /**
 * @author Henry
 * @reason Treat FE-capable neighbors as valid IEnergyContainer endpoints during route discovery
 */
@Overwrite
protected void checkNeighbour(CableBlockEntity pipeTile, BlockPos pipePos, Direction faceToNeighbour,
                                  @Nullable BlockEntity neighbourTile) {
        if (!HOTFIX_LOG_ENERGYNET_WALKER) {
            HOTFIX_LOG_ENERGYNET_WALKER = true;
            org.apache.logging.log4j.LogManager.getLogger("GTCEuEnergyNetHotfix").info("Mixin active: EnergyNetWalkerMixin (FE endpoint discovery)");
        }

        if (pipeTile != pipes[pipes.length - 1]) {
            throw new IllegalStateException("The current pipe is not the last added pipe. Something went seriously wrong!");
        }
        if (neighbourTile == null) return;

        Level level = pipeTile.getLevel();
        if (level == null) return;

        BlockPos neighbourPos = pipePos.relative(faceToNeighbour);
        Direction towardsPipe = faceToNeighbour.getOpposite();

        IEnergyContainer container = GTCapabilityHelper.getEnergyContainer(level, neighbourPos, towardsPipe);
        
if (container != null) {
    long key = neighbourPos.asLong();
    if (ENDPOINT_LOGGED.add(key)) {
}
}
if (container != null) {
            // EnergyRoutePath expects maxLoss as long; GTCEu tracks loss as int during walk.
            routes.add(new EnergyRoutePath(pipePos.immutable(), faceToNeighbour, pipes, ((EnergyNetWalker)(Object)this).getWalkedBlocks(), (long) loss));
        }
    }
}
