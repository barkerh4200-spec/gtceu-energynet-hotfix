package your.mod.energy;

import com.gregtechceu.gtceu.common.blockentity.CableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * Tick-batched cable amperage accounting.
 *
 * Goals:
 *  - Keep GTCEu safety correct (heat/burn) even if we reduce tooltip-visible updates.
 *  - Reduce per-transfer/per-segment work by batching to once per tick per cable segment.
 *  - Avoid remote-client stutter by skipping tooltip-visible counter updates when no players are nearby.
 *
 * Implementation:
 *  - During energy transfer, record (sumAmps, maxVoltage) per cable segment for the current tick.
 *  - At END of server tick, flush each segment:
 *      * If players nearby: call CableBlockEntity.incrementAmperage(sumAmps, maxVoltage) once.
 *      * If no players nearby: apply heat based on sumAmps vs maxAmperage (safety) but do not touch tooltip counters.
 */
@Mod.EventBusSubscriber(modid = "gtceuenergynethotfix", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CableAmperageAccumulator {

    private CableAmperageAccumulator() {}

    /** Per-level map: cablePos.asLong -> aggregated amps/voltage for the current tick. */
    private static final Map<ServerLevel, Map<Long, Entry>> PER_LEVEL = new HashMap<>();

    /** Radius within which tooltip-visible updates are worth doing. */
    private static final double PLAYER_RADIUS = 64.0;

    private static final class Entry {
        final long posLong;
        long sumAmps;
        long maxVoltage;

        Entry(long posLong, long amps, long voltage) {
            this.posLong = posLong;
            this.sumAmps = amps;
            this.maxVoltage = voltage;
        }

        void add(long amps, long voltage) {
            this.sumAmps += amps;
            if (voltage > this.maxVoltage) this.maxVoltage = voltage;
        }
    }

    public static void record(Level level, CableBlockEntity cable, long amps, long voltage) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (amps <= 0 || voltage <= 0) return;
        if (cable == null || cable.isRemoved() || cable.isInValid()) return;

        long key = cable.getBlockPos().asLong();
        Map<Long, Entry> map = PER_LEVEL.computeIfAbsent(serverLevel, l -> new HashMap<>());
        Entry e = map.get(key);
        if (e == null) {
            map.put(key, new Entry(key, amps, voltage));
        } else {
            e.add(amps, voltage);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        for (Map.Entry<ServerLevel, Map<Long, Entry>> lvlEntry : PER_LEVEL.entrySet()) {
            ServerLevel level = lvlEntry.getKey();
            Map<Long, Entry> entries = lvlEntry.getValue();
            if (entries.isEmpty()) continue;

            for (Entry e : entries.values()) {
                BlockPos pos = BlockPos.of(e.posLong);
                var be = level.getBlockEntity(pos);
                if (!(be instanceof CableBlockEntity cable)) continue;
                if (cable.isRemoved() || cable.isInValid()) continue;

                boolean playerNear = level.hasNearbyAlivePlayer(
                        pos.getX() + 0.5,
                        pos.getY() + 0.5,
                        pos.getZ() + 0.5,
                        PLAYER_RADIUS
                );

                if (playerNear) {
                    // One update per tick per cable: keeps GTCEu tooltip semantics stable.
                    cable.incrementAmperage(e.sumAmps, e.maxVoltage);
                } else {
                    // Safety-only path: preserve heating behavior without touching tooltip counters.
                    long dif = e.sumAmps - cable.getMaxAmperage();
                    if (dif > 0) {
                        cable.applyHeat((int) Math.min(Integer.MAX_VALUE, dif * 40L));
                    }
                }
            }

            entries.clear();
        }
    }
}
