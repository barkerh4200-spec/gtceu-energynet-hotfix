package your.mod.mixin;

import com.gregtechceu.gtceu.common.blockentity.CableBlockEntity;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNet;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNetHandler;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyRoutePath;
import com.gregtechceu.gtceu.utils.GTUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import your.mod.energy.CableAmperageAccumulator;
import your.mod.energy.IRouteSegmentData;
import your.mod.energy.SinkCache;
import your.mod.energy.SinkState;

import java.util.List;

/**
 * EnergyNet delivery hotfix:
 *  - Keeps the 7% TPS architecture (SinkCache + HandlerCache + lazy invalidation) to avoid repeated endpoint probing.
 *  - Restores vanilla GTCEu 7.4.0 semantics for:
 *      * route max-loss filtering
 *      * voltage drop by lossPerBlock (lossy vs lossless cables)
 *      * overvoltage heating and invalidation
 *      * voltage clamping to the weakest surviving cable segment (vanilla behaviour)
 *  - Uses tick-end cable amperage batching WITHOUT any player-proximity gating.
 */
@Mixin(value = EnergyNetHandler.class, remap = false)
public abstract class EnergyNetHandlerMixin {

    @Shadow private EnergyNet net;
    @Shadow private CableBlockEntity cable;

    // Fields exist in GTCEu 7.4.0 EnergyNetHandler; used as vanilla recursion/side guards.
    @Shadow private Direction facing;
    @Shadow private boolean transfer;

    /**
     * @author henry
     * @reason Preserve performance optimizations while matching vanilla loss + overvoltage enforcement.
     */
    @Overwrite(remap = false)
    public long acceptEnergyFromNetwork(Direction side, long voltage, long amperage) {
        // Match GTCEu recursion guard.
        if (transfer) return 0;

        // Match GTCEu side handling.
        if (side == null) {
            if (facing == null) return 0;
            side = facing;
        }

        if (amperage <= 0 || voltage <= 0 || net == null || cable == null) {
            return 0;
        }

        final Level level = net.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            // EnergyNetHandler is server-side in normal operation, but guard anyway.
            return 0;
        }

        // Count producer distribution calls per net (debug/diagnostics).
        your.mod.energy.EnergyNetDebugStats.recordAccept(net, serverLevel);

        // Vanilla semantics: if a cable itself is exposed to a higher voltage than its rating,
        // it should heat up / burn even if there are currently no valid endpoints (routes).
        final long selfMax = cable.getMaxVoltage();
        if (selfMax < voltage) {
            final int tierDiff = GTUtil.getTierByVoltage(voltage) - GTUtil.getTierByVoltage(selfMax);
            if (tierDiff > 0) {
                final int heat = (int) (Math.log((double) tierDiff) * 45.0d + 36.5d);
                cable.applyHeat(heat);
            }
            if (cable.isInValid()) {
                return 0;
            }
            // If it survived, clamp what can proceed through the net to this cable's rating.
            voltage = Math.min(voltage, selfMax);
        }

        final long tick = serverLevel.getGameTime();
        final SinkCache cache = SinkCache.get(net, tick);

        // Hard O(1) early-out once the net has been proven saturated this tick.
        if (cache.isSaturatedThisTick()) {
            return 0;
        }

        // Hard O(1) early-out once the net has been proven idle (no demand) this tick.
        if (cache.isNoDemandThisTick()) {
            return 0;
        }

        final List<EnergyRoutePath> routes = net.getNetData(cable.getPipePos());
        if (routes.isEmpty()) return 0;

        cache.prepareRoutes(routes);

        // prepareRoutes may discover that the current route list is empty / effectively exhausted.
        if (cache.isSaturatedThisTick()) {
            return 0;
        }

        // Hard O(1) early-out once the net has been proven idle (no demand) this tick.
        if (cache.isNoDemandThisTick()) {
            return 0;
        }

        long remaining = amperage;
        long acceptedTotal = 0;

        final int routeCount = routes.size();
        // Prime the probe cursor from the fairness cursor.
        cache.setProbeCursor(cache.getCursor());

        boolean anyAcceptedInProbe = false;

        // Pass 1: try routes that were known-active last tick (ring buffer from SinkCache).
        // This avoids touching hundreds of endpoints when only a small subset is actually consuming.
        final int[] activeRead = cache.getActiveRoutesRead();
        final int activeReadSize = cache.getActiveRoutesReadSize();
        final int activeStart = (activeReadSize > 0) ? (cache.getCursor() % activeReadSize) : 0;
        int activeScanned = 0;
        for (int i2 = 0; i2 < activeReadSize && remaining > 0 && !cache.isSaturatedThisTick(); i2++) {
            int idx = activeRead[(activeStart + i2) % activeReadSize];
            activeScanned++;
            if (idx < 0 || idx >= routeCount) {
                // Route list may have changed size; clamp defensively.
                idx = Math.floorMod(idx, routeCount);
            }
            if (cache.isRouteExhausted(idx)) continue;

            // We are about to actually examine this route.
            your.mod.energy.EnergyNetDebugStats.recordRouteCheck(net, serverLevel);

            final EnergyRoutePath path = routes.get(idx);

            // Vanilla: if the route loses all voltage, skip.
            if (path.getMaxLoss() >= voltage) {
                continue;
            }

            // Vanilla: skip self
            if (cable.getPipePos().equals(path.getTargetPipePos()) && side == path.getTargetFacing()) {
                continue;
            }

            // Vanilla: delivered voltage starts at (source voltage - route max loss)
            long deliveredVoltage = voltage - path.getMaxLoss();

            // Use cached per-segment route data (populated once by EnergyNetWalkerMixin) so we
            // do not touch node-data in the hot path. Only touch the BE when applying heat.
            final CableBlockEntity[] segs = path.getPath();
            final IRouteSegmentData segData = (IRouteSegmentData) path;
            final long[] segPosLong = segData.gtceuHotfix$getPosLong();
            final long[] segMaxV = segData.gtceuHotfix$getMaxVoltage();
            final int[] segLoss = segData.gtceuHotfix$getLossPerBlock();

            boolean invalidPath = false;
            final int segCount = (segs == null) ? 0 : segs.length;
            for (int j = 0; j < segCount; j++) {
                final CableBlockEntity seg = segs[j];
                if (seg == null) continue;

                final long segMax = (segMaxV != null && j < segMaxV.length) ? segMaxV[j] : seg.getMaxVoltage();

                if (segMax < voltage) {
                    final int tierDiff = GTUtil.getTierByVoltage(voltage) - GTUtil.getTierByVoltage(segMax);
                    if (tierDiff > 0) {
                        final int heat = (int) (Math.log((double) tierDiff) * 45.0d + 36.5d);
                        seg.applyHeat(heat);
                    }
                    if (seg.isInValid()) {
                        invalidPath = true;
                        break;
                    }
                }

                // Vanilla clamp: weakest surviving segment caps what the endpoint sees.
                if (segMax < deliveredVoltage) {
                    deliveredVoltage = segMax;
                }
            }
            if (invalidPath || deliveredVoltage <= 0) {
                continue;
            }

            // Endpoint (machine) position and insertion side
            final BlockPos endpointPos = path.getTargetPipePos().relative(path.getTargetFacing());
            final Direction insertSide = path.getTargetFacing().getOpposite();

            // Per-tick sink capacity cache keyed by deliveredVoltage (post-loss + clamp)
            final SinkState sink = cache.getOrCompute(net, path, serverLevel, endpointPos, insertSide, deliveredVoltage);
            if (!sink.valid) {
                cache.exhaustRoute(idx);
                continue;
            }

            final long sendable = sink.computeSendableAmps(deliveredVoltage);
            if (sendable <= 0) {
                // If sink has no remaining budget, mark this route exhausted for the tick.
                if (sink.remainingInputAmps <= 0 || sink.remainingEuSpace <= 0) {
                    cache.exhaustRoute(idx);
                }
                continue;
            }

            // Route is actively accepting this tick (or can accept) -> keep it in the active set.
            cache.markRouteActiveThisTick(idx);

            final long toSend = Math.min(remaining, sendable);
            long accepted;
            transfer = true;
            try {
                accepted = sink.handler.acceptEnergyFromNetwork(insertSide, deliveredVoltage, toSend);
            } finally {
                transfer = false;
            }

            if (accepted > 0) {
                // Vanilla: apply per-segment amperage tracking using traveled voltage (lossPerBlock each step).
                // Record by BlockPos-long and resolve the BE once per tick in CableAmperageAccumulator.
                long voltageTraveled = voltage;
                for (int j = 0; j < segCount; j++) {
                    final int lp = (segLoss != null && j < segLoss.length) ? segLoss[j] : 0;
                    voltageTraveled -= lp;
                    if (voltageTraveled <= 0) break;

                    final long posLong = (segPosLong != null && j < segPosLong.length) ? segPosLong[j]
                            : (segs[j] != null ? segs[j].getBlockPos().asLong() : 0L);
                    if (posLong != 0L) {
                        CableAmperageAccumulator.record(serverLevel, posLong, accepted, voltageTraveled);
                    }
                }

                sink.onAccepted(accepted, deliveredVoltage);
                remaining -= accepted;
                acceptedTotal += accepted;
                anyAcceptedInProbe = true;
                // Move shared cursor forward after a successful route use to improve fairness across producers.
                cache.setCursor((idx + 1) % routeCount);
            } else {
                // If this route's sink has no remaining capacity/amperage budget, mark route as exhausted for this tick.
                if (!sink.valid || sink.remainingInputAmps <= 0 || sink.remainingEuSpace <= 0) {
                    cache.exhaustRoute(idx);
                }
            }
        }
        // Share progress through active routes across producers in the same tick.
        if (activeReadSize > 0) {
            cache.setCursor((activeStart + activeScanned) % activeReadSize);
        }

        // Pass 2: probe/scavenge for additional consumers with a hard per-tick budget.
        // This bounds worst-case "search" work even on nets with 800+ endpoints.
        int safety = 0;
        while (remaining > 0 && !cache.isSaturatedThisTick() && cache.getProbeBudgetRemaining() > 0 && safety < routeCount) {
            final int idx = cache.nextProbeIndex(routeCount);
            safety++;

            if (cache.isRouteExhausted(idx)) {
                continue;
            }
            // Only examine each route once per tick in the probe phase.
            if (!cache.visitRoute(idx)) {
                continue;
            }
            // Pay the probe budget when we actually do work.
            if (!cache.tryConsumeProbeBudget()) {
                break;
            }

            your.mod.energy.EnergyNetDebugStats.recordRouteCheck(net, serverLevel);
            final EnergyRoutePath path = routes.get(idx);

            if (path.getMaxLoss() >= voltage) {
                continue;
            }
            if (cable.getPipePos().equals(path.getTargetPipePos()) && side == path.getTargetFacing()) {
                continue;
            }

            long deliveredVoltage = voltage - path.getMaxLoss();
            final CableBlockEntity[] segs = path.getPath();
            final IRouteSegmentData segData = (IRouteSegmentData) path;
            final long[] segPosLong = segData.gtceuHotfix$getPosLong();
            final long[] segMaxV = segData.gtceuHotfix$getMaxVoltage();
            final int[] segLoss = segData.gtceuHotfix$getLossPerBlock();

            boolean invalidPath = false;
            final int segCount = (segs == null) ? 0 : segs.length;
            for (int i = 0; i < segCount; i++) {
                final CableBlockEntity seg = segs[i];
                if (seg == null) continue;

                final long segMax = (segMaxV != null && i < segMaxV.length) ? segMaxV[i] : seg.getMaxVoltage();
                if (segMax < voltage) {
                    final int tierDiff = GTUtil.getTierByVoltage(voltage) - GTUtil.getTierByVoltage(segMax);
                    if (tierDiff > 0) {
                        final int heat = (int) (Math.log((double) tierDiff) * 45.0d + 36.5d);
                        seg.applyHeat(heat);
                    }
                    if (seg.isInValid()) {
                        invalidPath = true;
                        break;
                    }
                }

                if (segMax < deliveredVoltage) {
                    deliveredVoltage = segMax;
                }
            }
            if (invalidPath || deliveredVoltage <= 0) {
                continue;
            }

            final BlockPos endpointPos = path.getTargetPipePos().relative(path.getTargetFacing());
            final Direction insertSide = path.getTargetFacing().getOpposite();
            final SinkState sink = cache.getOrCompute(net, path, serverLevel, endpointPos, insertSide, deliveredVoltage);
            if (!sink.valid) {
                cache.exhaustRoute(idx);
                continue;
            }

            final long sendable = sink.computeSendableAmps(deliveredVoltage);
            if (sendable <= 0) {
                if (sink.remainingInputAmps <= 0 || sink.remainingEuSpace <= 0) {
                    cache.exhaustRoute(idx);
                }
                continue;
            }

            // Route is active -> keep it for next tick.
            cache.markRouteActiveThisTick(idx);

            final long toSend = Math.min(remaining, sendable);
            long accepted;
            transfer = true;
            try {
                accepted = sink.handler.acceptEnergyFromNetwork(insertSide, deliveredVoltage, toSend);
            } finally {
                transfer = false;
            }

            if (accepted > 0) {
                long voltageTraveled = voltage;
                for (int i = 0; i < segCount; i++) {
                    final int lp = (segLoss != null && i < segLoss.length) ? segLoss[i] : 0;
                    voltageTraveled -= lp;
                    if (voltageTraveled <= 0) break;

                    final long posLong = (segPosLong != null && i < segPosLong.length) ? segPosLong[i]
                            : (segs[i] != null ? segs[i].getBlockPos().asLong() : 0L);
                    if (posLong != 0L) {
                        CableAmperageAccumulator.record(serverLevel, posLong, accepted, voltageTraveled);
                    }
                }

                sink.onAccepted(accepted, deliveredVoltage);
                remaining -= accepted;
                acceptedTotal += accepted;
                anyAcceptedInProbe = true;
                cache.setCursor((idx + 1) % routeCount);
            } else {
                if (!sink.valid || sink.remainingInputAmps <= 0 || sink.remainingEuSpace <= 0) {
                    cache.exhaustRoute(idx);
                }
            }
        }

        // If we have exhaustively demonstrated that nothing on this net can accept this tick,
        // mark the net as saturated so further producer calls are O(1).
        if (acceptedTotal <= 0 && !anyAcceptedInProbe && cache.allRoutesVisited()) {
            cache.setSaturatedThisTick(true);
            cache.setNoDemandThisTick(true);
        }

        // Match existing behaviour: flux stats are based on source voltage.
        net.addEnergyFluxPerSec(acceptedTotal * voltage);
        return acceptedTotal;
    }
}