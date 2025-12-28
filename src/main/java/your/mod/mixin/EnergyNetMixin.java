package your.mod.mixin;

import com.gregtechceu.gtceu.common.blockentity.CableBlockEntity;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNet;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyRoutePath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import your.mod.energy.EndpointChangeTracker;
import your.mod.energy.HandlerCache;

import java.util.List;
import java.util.Map;

/**
 * EnergyNet cache invalidation improvements.
 *
 * Goals:
 *  A) Avoid doing any work for neighbor updates that are unrelated to this pipe net (near-cable filter).
 *  B) Coalesce expensive global invalidation so it happens at most once per tick per EnergyNet (dirty flag + lazy clear).
 *
 * Correctness:
 *  - When an endpoint (machine) is added/removed/replaced, we mark the net dirty. The next getNetData() call
 *    performs a one-time global invalidation (NET_DATA.clear()), ensuring new machines are discovered even for
 *    distant sources without requiring cable layout changes.
 *  - For noisy/irrelevant neighbor updates, we do cheap local invalidation only (fromPos + 6 neighbors).
 */
@Mixin(value = EnergyNet.class, remap = false)
public abstract class EnergyNetMixin {

    @Shadow
    private Map<BlockPos, List<EnergyRoutePath>> NET_DATA;

    @Unique private boolean gtceuHotfixDirty = false;
    @Unique private long gtceuHotfixLastGlobalClearTick = Long.MIN_VALUE;

    /**
     * (A) Near-cable filter: only consider updates that touch this net.
     */
    @Unique
    private boolean gtceuHotfixIsNearCable(Level level, BlockPos pos) {
        if (level == null || pos == null) return false;
        if (level.getBlockEntity(pos) instanceof CableBlockEntity) return true;
        for (Direction d : Direction.values()) {
            BlockEntity be = level.getBlockEntity(pos.relative(d));
            if (be instanceof CableBlockEntity) return true;
        }
        return false;
    }

    /**
     * (B) Coalesce global invalidation: if the net is marked dirty due to an endpoint change, clear NET_DATA
     * at most once per tick on first access via getNetData().
     */
    @Inject(method = "getNetData", at = @At("HEAD"))
    private void gtceuHotfixBeforeGetNetData(BlockPos pipePos, CallbackInfoReturnable<List<EnergyRoutePath>> cir) {
        EnergyNet self = (EnergyNet) (Object) this;
        Level level = self.getLevel();
        if (level == null) return;

        long tick = level.getGameTime();
        if (gtceuHotfixDirty && gtceuHotfixLastGlobalClearTick != tick) {
            // One global invalidation per tick per net, then rebuild lazily per pipePos.
            NET_DATA.clear();
            HandlerCache.clear(self);
            gtceuHotfixLastGlobalClearTick = tick;
            gtceuHotfixDirty = false;
        }
    }

    /**
     * @author henry
     * @reason Replace global invalidation with local invalidation for noisy neighbor updates, but still guarantee
     *         newly attached endpoints receive power by marking the net dirty on real endpoint changes.
     */
    @Overwrite(remap = false)
    public void onNeighbourUpdate(BlockPos fromPos) {
        if (fromPos == null) return;

        EnergyNet self = (EnergyNet) (Object) this;
        Level level = self.getLevel();
        if (level == null) return;

        // (A) Near-cable filter: ignore unrelated updates
        if (!gtceuHotfixIsNearCable(level, fromPos)) {
            return;
        }

        // Always perform local invalidation around the update position (cheap).
        NET_DATA.remove(fromPos);
        for (Direction dir : Direction.values()) {
            NET_DATA.remove(fromPos.relative(dir));
        }

        // Invalidate cached endpoint handlers around this update position.
        HandlerCache.invalidateAround(self, fromPos);

        // Mark dirty ONLY when the BlockEntity identity at fromPos actually changes.
        // This captures real endpoint add/remove/replace without reacting to noisy neighbor updates.
        BlockEntity be = level.getBlockEntity(fromPos);
        if (EndpointChangeTracker.didBlockEntityChange(self, fromPos, be)) {
            gtceuHotfixDirty = true;
        }
    }
}
