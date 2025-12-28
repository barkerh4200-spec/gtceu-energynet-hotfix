package your.mod.mixin;

import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNet;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyRoutePath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import your.mod.energy.EndpointChangeTracker;
import your.mod.energy.HandlerCache;

import java.util.List;
import java.util.Map;

/**
 * Local invalidation for EnergyNet route cache, with correctness fallback for endpoint changes.
 *
 * Key point: NET_DATA is keyed by *source pipe position*. When an endpoint is added/removed, cached
 * route lists for many pipe positions may become stale. However, neighbor updates are also noisy and
 * frequent during normal ticking. Global invalidation on every neighbor update causes repeated full
 * network walks and TPS regressions.
 *
 * Strategy:
 * - Always do cheap local invalidation (fromPos + 6 neighbors).
 * - Only do a global invalidate when we detect a *real* endpoint add/remove/replace, approximated by
 *   BlockEntity identity changing at fromPos (null<->non-null or different instance).
 */
@Mixin(value = EnergyNet.class, remap = false)
public abstract class EnergyNetMixin {

    @Shadow
    private Map<BlockPos, List<EnergyRoutePath>> NET_DATA;

    /**
     * @author henry
     * @reason Replace global invalidation with local invalidation for noisy neighbor updates, while still
     *         globally invalidating when an endpoint is actually added/removed/replaced so new machines
     *         receive power correctly.
     */
    @Overwrite(remap = false)
    public void onNeighbourUpdate(BlockPos fromPos) {
        if (fromPos == null) return;

        // Always perform local invalidation around the update position.
        NET_DATA.remove(fromPos);
        for (Direction dir : Direction.values()) {
            NET_DATA.remove(fromPos.relative(dir));
        }

        EnergyNet self = (EnergyNet) (Object) this;

        // Invalidate cached endpoint handlers around this update position.
        HandlerCache.invalidateAround(self, fromPos);

        // Correctness fallback: only globally invalidate if the BlockEntity at fromPos actually changed.
        Level level = self.getLevel();
        if (level == null) return;

        BlockEntity be = level.getBlockEntity(fromPos);
        if (EndpointChangeTracker.didBlockEntityChange(self, fromPos, be)) {
            NET_DATA.clear();
            HandlerCache.clear(self);
            EndpointChangeTracker.clear(self);
        }
    }
}
