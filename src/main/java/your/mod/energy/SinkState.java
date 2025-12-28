package your.mod.energy;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNet;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyRoutePath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

public final class SinkState {

    public final IEnergyContainer handler;
    public final boolean valid;
    public long remainingAmps;

    private SinkState(IEnergyContainer handler, boolean valid, long remainingAmps) {
        this.handler = handler;
        this.valid = valid;
        this.remainingAmps = remainingAmps;
    }

    public static SinkState compute(
            EnergyNet net,
            EnergyRoutePath path,
            Level level,
            BlockPos endpointPos,
            Direction insertSide,
            long voltage
    ) {
        IEnergyContainer handler = HandlerCache.get(net, level, endpointPos, insertSide, path);
        if (handler == null) {
            return new SinkState(null, false, 0);
        }

        if (!handler.inputsEnergy(insertSide)) {
            return new SinkState(handler, false, 0);
        }

        long maxAmps = handler.getInputAmperage();
        if (maxAmps <= 0) {
            return new SinkState(handler, false, 0);
        }

        if (voltage > 0) {
            long euSpace = handler.getEnergyCanBeInserted();
            long ampsByStorage = euSpace / voltage;
            if (ampsByStorage <= 0) {
                return new SinkState(handler, false, 0);
            }
            maxAmps = Math.min(maxAmps, ampsByStorage);
        }

        return new SinkState(handler, true, maxAmps);
    }
}
