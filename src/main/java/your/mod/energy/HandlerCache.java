package your.mod.energy;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNet;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyRoutePath;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.WeakHashMap;

/**
 * Multi-tick cache of endpoint -> resolved IEnergyContainer handler.
 *
 * Goal: avoid repeated capability resolution (Level#getBlockEntity, getCapability, LazyOptional.resolve)
 * on every tick / every producer when distributing energy across the same EnergyNet.
 *
 * Correctness:
 * - Cache is scoped to a specific EnergyNet instance (GTCEu replaces nets on rebuild).
 * - Entries are invalidated locally on neighbor updates (see EnergyNetMixin).
 * - Entries are also validated by checking the current BlockEntity identity before use.
 */
public final class HandlerCache {

    private HandlerCache() {}

    private static final WeakHashMap<EnergyNet, PerNet> PER_NET = new WeakHashMap<>();

    private static final class Cached {
        final BlockEntity be;
        final IEnergyContainer handler; // may be null

        Cached(BlockEntity be, IEnergyContainer handler) {
            this.be = be;
            this.handler = handler;
        }
    }

    private static final class PerNet {
        final Long2ObjectOpenHashMap<Cached> map = new Long2ObjectOpenHashMap<>();
    }

    private static PerNet perNet(EnergyNet net) {
        synchronized (PER_NET) {
            return PER_NET.computeIfAbsent(net, n -> new PerNet());
        }
    }

    /**
     * Resolve (and cache) the handler for a given endpoint.
     *
     * @param net EnergyNet instance (cache scope)
     * @param level Level
     * @param endpointPos block position of the machine/endpoint
     * @param insertSide side from which energy is inserted into the endpoint
     * @param path route path (used as resolution source; avoids signature drift)
     */
    public static IEnergyContainer get(EnergyNet net, Level level, BlockPos endpointPos, Direction insertSide, EnergyRoutePath path) {
        long key = KeyUtil.packPosSide(endpointPos, insertSide);
        PerNet pn = perNet(net);

        Cached cached = pn.map.get(key);

        BlockEntity currentBe = level.getBlockEntity(endpointPos);
        if (cached != null && cached.be == currentBe) {
            return cached.handler;
        }

        // Refresh: resolve via route path (internally does the capability lookup).
        IEnergyContainer handler = path.getHandler(level);

        pn.map.put(key, new Cached(currentBe, handler));
        return handler;
    }

    /**
     * Local invalidation around a position (called from EnergyNetMixin).
     * Removes cached handlers for the given position and its 6 neighbors, for all sides.
     */
    public static void invalidateAround(EnergyNet net, BlockPos fromPos) {
        if (net == null || fromPos == null) return;
        PerNet pn;
        synchronized (PER_NET) {
            pn = PER_NET.get(net);
        }
        if (pn == null) return;

        // fromPos and its neighbors
        invalidatePosAllSides(pn, fromPos);
        for (Direction d : Direction.values()) {
            invalidatePosAllSides(pn, fromPos.relative(d));
        }
    }

    private static void invalidatePosAllSides(PerNet pn, BlockPos pos) {
        long base = pos.asLong() << 3;
        for (int s = 0; s < 6; s++) {
            pn.map.remove(base | (long)s);
        }
    }

    /** Full clear for a given net (optional utility). */
    public static void clear(EnergyNet net) {
        if (net == null) return;
        synchronized (PER_NET) {
            PER_NET.remove(net);
        }
    }
}
