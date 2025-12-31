package your.mod.energy;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.compat.FeCompat;
import net.minecraft.core.Direction;
import net.minecraftforge.energy.IEnergyStorage;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Forge Energy -> GTCEu IEnergyContainer adapter with buffered down-conversion.
 *
 * This class is only used when the EnergyNet actually decides a neighbour is an endpoint and
 * wraps it as an IEnergyContainer via GTCapabilityHelperMixin.
 *
 * Debugging:
 *  - Logs wrapper creation for the first few instances so we can prove wrapping happens on IV/LuV networks.
 */
public final class FeEnergyContainerWrapper implements IEnergyContainer {

    private static final AtomicInteger CREATED_LOG_COUNT = new AtomicInteger(0);
    private static final int MAX_CREATE_LOGS = 20;

    private static boolean ACTIVE_LOGGED = false;
    private static boolean HIGH_VOLT_WARN_LOGGED = false;

    private final IEnergyStorage energyStorage;
    private final int maxReceiveFePerTick;

    /** Buffered energy in EU awaiting conversion to FE. */
    private long euBuffer = 0L;

    /** About 40 ticks (2 seconds) worth of input buffering. */
    private final long maxEuBuffer;

    public FeEnergyContainerWrapper(IEnergyStorage energyStorage) {
        this.energyStorage = energyStorage;

        if (energyStorage.canReceive()) {
            this.maxReceiveFePerTick = Math.max(0, energyStorage.receiveEnergy(Integer.MAX_VALUE, true));
        } else {
            this.maxReceiveFePerTick = 0;
        }

        final int ratio = FeCompat.ratio(false);
        long maxEuPerTick = FeCompat.toEu(this.maxReceiveFePerTick, ratio);
        if (maxEuPerTick <= 0) {
            this.maxEuBuffer = 0L;
        } else {
            this.maxEuBuffer = Math.max(1L, maxEuPerTick * 40L);
        }

        int n = CREATED_LOG_COUNT.getAndIncrement();
        if (n < MAX_CREATE_LOGS) {
}
    }

    @Override
    public long acceptEnergyFromNetwork(Direction facing, long voltage, long amperage) {
        if (!ACTIVE_LOGGED) {
            ACTIVE_LOGGED = true;
}

        if (amperage <= 0 || voltage <= 0) return 0;
        if (!energyStorage.canReceive()) return 0;
        if (maxEuBuffer <= 0) return 0;

        final int ratio = FeCompat.ratio(false);

        // Warn (once) when a single GT packet at this voltage exceeds FE throughput.
        final long packetFe = FeCompat.toFeLong(voltage, ratio);
        if (!HIGH_VOLT_WARN_LOGGED && maxReceiveFePerTick > 0 && packetFe > (long) maxReceiveFePerTick) {
            HIGH_VOLT_WARN_LOGGED = true;
}

        // Drain existing buffer into FE for this tick.
        drainBufferToFe(ratio);

        long space = maxEuBuffer - euBuffer;
        if (space <= 0) return 0;

        long maxAmpsBySpace = space / voltage;
        if (maxAmpsBySpace <= 0) return 0;

        long acceptedAmps = Math.min(amperage, maxAmpsBySpace);
        if (acceptedAmps <= 0) return 0;

        // Accept EU into buffer (EnergyNet removes this EU from the network immediately).
        euBuffer += acceptedAmps * voltage;

        // Drain again after buffering.
        drainBufferToFe(ratio);

        return acceptedAmps;
    }

    private void drainBufferToFe(int ratio) {
        if (euBuffer <= 0) return;
        if (maxReceiveFePerTick <= 0) return;

        long wantFeLong = FeCompat.toFeLong(euBuffer, ratio);
        if (wantFeLong <= 0) return;

        int wantFe = (int) Math.min((long) Integer.MAX_VALUE, Math.min((long) maxReceiveFePerTick, wantFeLong));

        // Align to ratio boundary to avoid conversion loss.
        int aligned = wantFe - (wantFe % ratio);
        if (aligned <= 0) return;

        int sim = energyStorage.receiveEnergy(aligned, true);
        if (sim <= 0) return;

        int simAligned = sim - (sim % ratio);
        if (simAligned <= 0) return;

        int received = energyStorage.receiveEnergy(simAligned, false);
        if (received <= 0) return;

        long deliveredEu = FeCompat.toEu(received, ratio);
        if (deliveredEu > 0) {
            euBuffer = Math.max(0L, euBuffer - deliveredEu);
        }
    }

    @Override
    public boolean inputsEnergy(Direction facing) {
        return energyStorage.canReceive();
    }

    @Override
    public long getInputVoltage() {
        // Buffer can accept any voltage; gating is via buffer capacity.
        return Long.MAX_VALUE;
    }

    @Override
    public long getInputAmperage() {
        return Long.MAX_VALUE;
    }

    @Override
    public long getEnergyCanBeInserted() {
        final int ratio = FeCompat.ratio(false);
        long spaceEuByBuffer = Math.max(0L, maxEuBuffer - euBuffer);

        int maxFe = energyStorage.getMaxEnergyStored();
        int curFe = energyStorage.getEnergyStored();
        int spaceFe = Math.max(0, maxFe - curFe);
        long spaceEuByStorage = FeCompat.toEu(spaceFe, ratio);

        // Conservative to avoid the net trying to dump insane amounts into a slow FE sink.
        return spaceEuByBuffer + Math.min(spaceEuByStorage, spaceEuByBuffer);
    }

    @Override
    public long getEnergyStored() {
        final int ratio = FeCompat.ratio(false);
        long feEu = FeCompat.toEu(energyStorage.getEnergyStored(), ratio);
        return feEu + euBuffer;
    }

    @Override
    public long getEnergyCapacity() {
        final int ratio = FeCompat.ratio(false);
        long feCapEu = FeCompat.toEu(energyStorage.getMaxEnergyStored(), ratio);
        return feCapEu + maxEuBuffer;
    }

    @Override
    public long changeEnergy(long differenceAmount) {
        // Not used in our EnergyNet path.
        return 0;
    }
}
