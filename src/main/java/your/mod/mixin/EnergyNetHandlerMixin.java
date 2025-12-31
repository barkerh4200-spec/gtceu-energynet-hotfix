package your.mod.mixin;

import com.gregtechceu.gtceu.common.blockentity.CableBlockEntity;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNet;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNetHandler;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyRoutePath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import your.mod.energy.SinkCache;
import your.mod.energy.SinkState;

import java.util.List;

@Mixin(value = EnergyNetHandler.class, remap = false)
public abstract class EnergyNetHandlerMixin {


    @org.spongepowered.asm.mixin.Unique private static boolean HOTFIX_LOG_ENERGYNET_HANDLER = false;
    @Shadow private EnergyNet net;
    @Shadow private CableBlockEntity cable;

    @Unique
    private static boolean GTCEU_ENERGYNET_HOTFIX_LOGGED = false;

    /**
     * @author henry
     * @reason Stop repeated per-machine sink probing across the same EnergyNet by caching endpoint handlers (multi-tick)
     *         and per-tick sink capacity state.
     */
    @Overwrite(remap = false)
    public long acceptEnergyFromNetwork(Direction side, long voltage, long amperage) {
        if (!HOTFIX_LOG_ENERGYNET_HANDLER) {
            HOTFIX_LOG_ENERGYNET_HANDLER = true;
            org.apache.logging.log4j.LogManager.getLogger("GTCEuEnergyNetHotfix").info("Mixin active: EnergyNetHandlerMixin (endpoint caching + delivery)");
        }

        if (!GTCEU_ENERGYNET_HOTFIX_LOGGED) {
            GTCEU_ENERGYNET_HOTFIX_LOGGED = true;
            org.apache.logging.log4j.LogManager.getLogger("GTCEuEnergyNetHotfix").info("[GTCEuEnergyNetHotfix] EnergyNetHandler.acceptEnergyFromNetwork OVERWRITE ACTIVE");
        }

        if (amperage <= 0 || net == null || cable == null) {
            return 0;
        }

        List<EnergyRoutePath> routes = net.getNetData(cable.getPipePos());
        if (routes.isEmpty()) {
            return 0;
        }

        Level level = net.getLevel();
        long tick = level.getGameTime();
        SinkCache cache = SinkCache.get(net, tick);

        long remaining = amperage;
        long acceptedTotal = 0;

        for (EnergyRoutePath path : routes) {
            if (remaining <= 0) break;

            // Preserve original "skip self" behavior
            if (cable.getPipePos().equals(path.getTargetPipePos()) && side == path.getTargetFacing()) {
                continue;
            }

            // Endpoint (machine) position and insertion side
            BlockPos endpointPos = path.getTargetPipePos().relative(path.getTargetFacing());
            Direction insertSide = path.getTargetFacing().getOpposite();

            SinkState sink = cache.getOrCompute(net, path, level, endpointPos, insertSide, voltage);
            if (!sink.valid || sink.remainingAmps <= 0) continue;

            long toSend = Math.min(remaining, sink.remainingAmps);

            long accepted = sink.handler.acceptEnergyFromNetwork(
                    insertSide,
                    voltage,
                    toSend
            );

            if (accepted > 0) {
                // Preserve GTCEu cable statistics (Jade/WTHIT/TOP live amperage) and over-amp heat behavior.
                //
                // Server-side mitigation for remote-client stutter:
                // - Batch per-segment accounting once per tick (instead of per-transfer).
                // - Skip tooltip-visible counter updates when no players are nearby, while keeping heat/burn safety correct.
                long voltageTraveled = voltage;
                for (com.gregtechceu.gtceu.common.blockentity.CableBlockEntity seg : path.getPath()) {
                    voltageTraveled -= seg.getNodeData().getLossPerBlock();
                    if (voltageTraveled <= 0) break;
                    your.mod.energy.CableAmperageAccumulator.record(level, seg, accepted, voltageTraveled);
                }
                sink.remainingAmps -= accepted;
                remaining -= accepted;
                acceptedTotal += accepted;
            }
        }

        net.addEnergyFluxPerSec(acceptedTotal * voltage);
        return acceptedTotal;
    }
}
