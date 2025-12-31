package your.mod.mixin;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.compat.FeCompat;
import com.gregtechceu.gtceu.api.capability.compat.EUToFEProvider;
import net.minecraft.core.Direction;
import net.minecraftforge.energy.IEnergyStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.lang.reflect.Field;

/**
 * Fix high-tier (IV/LuV+) EU -> FE delivery for FE sinks wrapped by GTCEu's built-in EUToFEProvider$GTEnergyWrapper.
 *
 * Observed: EnergyNetWalker discovers AE2 Controller endpoints using EUToFEProvider$GTEnergyWrapper,
 * not our custom wrapper. On IV/LuV networks, those endpoints receive 0 FE.
 *
 * Root cause (likely): packet-based voltage gating + FE throughput mismatch. EnergyNet may refuse to
 * deliver if getInputVoltage() is too low, and/or acceptEnergyFromNetwork() only counts whole packets.
 *
 * This mixin:
 *  - Treats FE as continuous by buffering EU internally and drip-feeding FE at the sink's throughput.
 *  - Returns accepted amps based on buffered EU (energy conserved).
 *  - Advertises effectively unlimited input voltage so the net will attempt delivery even on high tiers.
 *
 * No per-tick world scanning is introduced; logic only runs when the net calls acceptEnergyFromNetwork().
 */
@Mixin(value = EUToFEProvider.GTEnergyWrapper.class, remap = false)
public abstract class EUToFEGTEnergyWrapperMixin {

    @Unique private static boolean LOG_ACTIVE = false;
    @Unique private static boolean LOG_BUFFERING_WARN = false;

    @Unique private boolean gtceuHotfix$init = false;
    @Unique private IEnergyStorage gtceuHotfix$storage = null;
    @Unique private int gtceuHotfix$maxReceiveFePerTick = 0;
    @Unique private long gtceuHotfix$euBuffer = 0L;
    @Unique private long gtceuHotfix$maxEuBuffer = 0L;

    @Unique
    private IEnergyStorage gtceuHotfix$getStorage() {
        if (gtceuHotfix$storage != null) return gtceuHotfix$storage;

        // Reflectively locate the first IEnergyStorage field on this wrapper.
        // This avoids depending on GTCEu private field names across versions.
        try {
            Class<?> c = this.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (IEnergyStorage.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object v = f.get(this);
                        if (v instanceof IEnergyStorage) {
                            gtceuHotfix$storage = (IEnergyStorage) v;
                            return gtceuHotfix$storage;
                        }
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}

        return null;
    }

    @Unique
    private void gtceuHotfix$ensureInit() {
        if (gtceuHotfix$init) return;
        gtceuHotfix$init = true;

        IEnergyStorage s = gtceuHotfix$getStorage();
        if (s != null && s.canReceive()) {
            try {
                gtceuHotfix$maxReceiveFePerTick = Math.max(0, s.receiveEnergy(Integer.MAX_VALUE, true));
            } catch (Throwable ignored) {
                gtceuHotfix$maxReceiveFePerTick = 0;
            }

            int ratio = FeCompat.ratio(false);
            long maxEuPerTick = FeCompat.toEu(gtceuHotfix$maxReceiveFePerTick, ratio);
            if (maxEuPerTick > 0) {
                gtceuHotfix$maxEuBuffer = Math.max(1L, maxEuPerTick * 40L); // ~2s buffer
            } else {
                gtceuHotfix$maxEuBuffer = 0L;
            }
        }
    }

    @Unique
    private void gtceuHotfix$drainBufferToFe(int ratio) {
        if (gtceuHotfix$euBuffer <= 0) return;
        if (gtceuHotfix$maxReceiveFePerTick <= 0) return;

        IEnergyStorage s = gtceuHotfix$getStorage();
        if (s == null || !s.canReceive()) return;

        long wantFeLong = FeCompat.toFeLong(gtceuHotfix$euBuffer, ratio);
        if (wantFeLong <= 0) return;

        int wantFe = (int) Math.min((long) Integer.MAX_VALUE,
                Math.min((long) gtceuHotfix$maxReceiveFePerTick, wantFeLong));

        int aligned = wantFe - (wantFe % ratio);
        if (aligned <= 0) return;

        int sim = s.receiveEnergy(aligned, true);
        if (sim <= 0) return;

        int simAligned = sim - (sim % ratio);
        if (simAligned <= 0) return;

        int received = s.receiveEnergy(simAligned, false);
        if (received <= 0) return;

        long deliveredEu = FeCompat.toEu(received, ratio);
        if (deliveredEu > 0) {
            gtceuHotfix$euBuffer = Math.max(0L, gtceuHotfix$euBuffer - deliveredEu);
        }
    }

    /**
     * @author hotfix
     * @reason Make IV/LuV+ networks power FE sinks by buffering EU then drip-feeding FE at sink throughput.
     */
    @Overwrite
    public long acceptEnergyFromNetwork(Direction facing, long voltage, long amperage) {
        gtceuHotfix$ensureInit();

        if (!LOG_ACTIVE) {
            LOG_ACTIVE = true;
}

        if (amperage <= 0 || voltage <= 0) return 0;

        IEnergyStorage s = gtceuHotfix$getStorage();
        if (s == null || !s.canReceive()) return 0;

        if (gtceuHotfix$maxEuBuffer <= 0) return 0;

        final int ratio = FeCompat.ratio(false);

        // Log once when a single packet is bigger than sink throughput (the stall condition).
        long packetFe = FeCompat.toFeLong(voltage, ratio);
        if (!LOG_BUFFERING_WARN && gtceuHotfix$maxReceiveFePerTick > 0 && packetFe > (long) gtceuHotfix$maxReceiveFePerTick) {
            LOG_BUFFERING_WARN = true;
}

        // Drain existing buffer first
        gtceuHotfix$drainBufferToFe(ratio);

        // Accept EU into buffer, limited by buffer space
        long space = gtceuHotfix$maxEuBuffer - gtceuHotfix$euBuffer;
        if (space <= 0) return 0;

        long maxAmpsBySpace = space / voltage;
        if (maxAmpsBySpace <= 0) return 0;

        long acceptedAmps = Math.min(amperage, maxAmpsBySpace);
        if (acceptedAmps <= 0) return 0;

        gtceuHotfix$euBuffer += acceptedAmps * voltage;

        // Drain again after buffering
        gtceuHotfix$drainBufferToFe(ratio);

        return acceptedAmps;
    }

    /**
     * @author hotfix
     * @reason FE sinks are not voltage-tiered; allow the net to attempt delivery at any tier (buffering handles throughput).
     */
    @Overwrite
    public long getInputVoltage() {
        return Long.MAX_VALUE;
    }

    /**
     * @author hotfix
     * @reason FE sinks are not amperage-tiered; buffering and FE throughput clamp effective intake.
     */
    @Overwrite
    public long getInputAmperage() {
        return Long.MAX_VALUE;
    }

    /**
     * @author hotfix
     * @reason Delegate to FE storage.
     */
    @Overwrite
    public boolean inputsEnergy(Direction facing) {
        IEnergyStorage s = gtceuHotfix$getStorage();
        return s != null && s.canReceive();
    }

    /**
     * @author hotfix
     * @reason Improve routing heuristics by exposing buffer space (EU) as insertable capacity.
     */
    @Overwrite
    public long getEnergyCanBeInserted() {
        gtceuHotfix$ensureInit();
        if (gtceuHotfix$maxEuBuffer <= 0) return 0;
        return Math.max(0L, gtceuHotfix$maxEuBuffer - gtceuHotfix$euBuffer);
    }
}
