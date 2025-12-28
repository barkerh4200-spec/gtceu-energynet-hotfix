package your.mod.energy;

import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNet;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyRoutePath;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-net, per-tick sink state cache.
 *
 * Values include remaining amps for THIS tick and voltage (storage clamp).
 * Uses HandlerCache for multi-tick handler resolution.
 */
public final class SinkCache {

    private static final Map<EnergyNet, SinkCache> NET_CACHE = new WeakHashMap<>();

    private final Long2ObjectOpenHashMap<SinkState> sinks = new Long2ObjectOpenHashMap<>();
    private final long tick;

    private SinkCache(long tick) {
        this.tick = tick;
    }

    public static SinkCache get(EnergyNet net, long tick) {
        synchronized (NET_CACHE) {
            SinkCache cache = NET_CACHE.get(net);
            if (cache == null || cache.tick != tick) {
                cache = new SinkCache(tick);
                NET_CACHE.put(net, cache);
            }
            return cache;
        }
    }

    public SinkState getOrCompute(
            EnergyNet net,
            EnergyRoutePath path,
            Level level,
            BlockPos endpointPos,
            Direction insertSide,
            long voltage
    ) {
        long posSide = KeyUtil.packPosSide(endpointPos, insertSide);
        long key = KeyUtil.mix(posSide, voltage);

        SinkState s = sinks.get(key);
        if (s != null) return s;

        s = SinkState.compute(net, path, level, endpointPos, insertSide, voltage);
        sinks.put(key, s);
        return s;
    }
}
