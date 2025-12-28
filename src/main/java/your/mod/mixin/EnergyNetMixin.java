package your.mod.mixin;

import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNet;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyRoutePath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import your.mod.energy.HandlerCache;

import java.util.List;
import java.util.Map;

/**
 * Local invalidation for EnergyNet route cache.
 *
 * GTCEu clears the entire NET_DATA map on any neighbor update, which can cause full network walks
 * to happen repeatedly during normal ticking if neighbor updates are frequent.
 *
 * This overwrite replaces global invalidation (NET_DATA.clear()) with a small O(1) set of removals:
 * the updated position and its 6 adjacent positions. This preserves correctness for endpoint attach/detach
 * while preventing repeated full-cache nukes.
 */
@Mixin(value = EnergyNet.class, remap = false)
public abstract class EnergyNetMixin {

    @Shadow
    private Map<BlockPos, List<EnergyRoutePath>> NET_DATA;

    /**
     * @author henry
     * @reason Replace global invalidation with local invalidation (O(1) removals) to prevent repeated full net walks.
     */
    @Overwrite(remap = false)
    public void onNeighbourUpdate(BlockPos fromPos) {
        if (fromPos == null) return;

        // Invalidate any cached route lists that could be affected by a change at fromPos.
        // Route cache keys are pipe positions; neighbor updates are local, so invalidating
        // fromPos and its direct neighbors covers cable endpoint changes and adjacent machines.
        NET_DATA.remove(fromPos);
        for (Direction dir : Direction.values()) {
            NET_DATA.remove(fromPos.relative(dir));
        }

        // Also invalidate cached endpoint handlers around this update position.
        HandlerCache.invalidateAround((EnergyNet)(Object)this, fromPos);
    }
}
