package your.mod.mixin;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.compat.FeCompat;
import net.minecraft.core.Direction;
import net.minecraftforge.energy.IEnergyStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.lang.reflect.Field;

/**
 * Fix high-tier (IV/LuV+) EU -> FE delivery for FE sinks wrapped by GTCEu's built-in
 * EUToFEProvider$GTEnergyWrapper.
 *
 * Key properties:
 * - Does NOT base amperage consumption on maxReceive; amps consumed are derived from FE actually inserted this tick
 *   (matching GTCEu's original EUToFEProvider logic).
 * - Supports "packet splitting" via an internal FE remainder buffer so sinks with maxReceive < one GT packet
 *   still accept energy at high tiers.
 * - Advertises effectively unlimited input voltage so high-tier networks attempt delivery.
 */
@Mixin(value = com.gregtechceu.gtceu.api.capability.compat.EUToFEProvider.GTEnergyWrapper.class, remap = false)
public abstract class EUToFEGTEnergyWrapperMixin implements IEnergyContainer {

    @Unique
    private static final Field GTCEU_HOTFIX$ENERGY_STORAGE_FIELD = gtceuHotfix$findEnergyStorageField();

    @Unique
    private static Field gtceuHotfix$findEnergyStorageField() {
        try {
            Field f = com.gregtechceu.gtceu.api.capability.compat.EUToFEProvider.GTEnergyWrapper.class
                    .getDeclaredField("energyStorage");
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    @Unique
    private IEnergyStorage gtceuHotfix$getStorage() {
        if (GTCEU_HOTFIX$ENERGY_STORAGE_FIELD == null) return null;
        try {
            return (IEnergyStorage) GTCEU_HOTFIX$ENERGY_STORAGE_FIELD.get(this);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Internal FE remainder buffer (in FE units).
     * This is the same idea as GTCEu's original EUToFEProvider: if a sink can only accept part of a GT packet
     * worth of FE, we buffer the remainder so no energy is lost, and amperage consumption remains consistent.
     */
    @Unique
    private long gtceuHotfix$feBuffer;

    @Unique
    private static int gtceuHotfix$satCast(long v) {
        if (v <= 0) return 0;
        if (v >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) v;
    }

    /**
     * Overwrite to match GTCEu's original semantics:
     * - amps consumed are based on FE inserted this tick (plus at most +1 amp for partial-packet remainder buffering),
     *   NOT based on buffer capacity or maxReceive alone.
     * - buffer is used to prevent packet loss on conversion.
     * @author GTCEuEnergyNetHotfix
     * @reason Match GTCEu EU→FE behavior while buffering remainder and charging amps only for FE actually inserted this tick.
     */
    @Overwrite
    public long acceptEnergyFromNetwork(Direction facing, long voltage, long amperage) {
        IEnergyStorage s = gtceuHotfix$getStorage();
        if (s == null || !s.canReceive()) return 0;

        final int ratio = FeCompat.ratio(false);
        final long maxPacketFe = FeCompat.toFeLong(voltage, ratio);
        if (maxPacketFe <= 0 || amperage <= 0 || voltage <= 0) return 0;

        final long maximalValue = maxPacketFe * amperage;

        int receiveFromBuffer = 0;

        // Try to use the internal buffer before consuming a new packet
        if (gtceuHotfix$feBuffer > 0) {
            int can = s.receiveEnergy(gtceuHotfix$satCast(gtceuHotfix$feBuffer), true);
            if (can == 0) return 0;

            // Buffer could provide only part of what the sink can accept this tick; consume part of buffer only
            if (gtceuHotfix$feBuffer > can) {
                int inserted = s.receiveEnergy(can, false);
                gtceuHotfix$feBuffer -= inserted;
                return 0;
            } else {
                // Buffer can be fully consumed; include it in the combined insertion below
                receiveFromBuffer = gtceuHotfix$satCast(gtceuHotfix$feBuffer);
            }
        }

        // Consume buffer remainder + new packet energy in a single insertion
        if (receiveFromBuffer != 0) {
            int consumable = s.receiveEnergy(gtceuHotfix$satCast(maximalValue + (long) receiveFromBuffer), true);
            if (consumable == 0) return 0;

            consumable = s.receiveEnergy(consumable, false);

            // Only able to consume less than our buffered amount
            if ((long) consumable <= (long) receiveFromBuffer) {
                gtceuHotfix$feBuffer = (long) receiveFromBuffer - (long) consumable;
                return 0;
            }

            long newPower = (long) consumable - (long) receiveFromBuffer;

            // Able to consume buffered amount plus an even amount of packets
            if (newPower % maxPacketFe == 0) {
                gtceuHotfix$feBuffer = 0;
                return newPower / maxPacketFe;
            }

            // Able to consume buffered amount plus some remainder inside the last packet
            long ampsToConsume = (newPower / maxPacketFe) + 1;
            gtceuHotfix$feBuffer = (maxPacketFe * ampsToConsume) - newPower;
            return ampsToConsume;
        }

        // No buffer: try to draw up to amperage packets worth of FE
        int consumable = s.receiveEnergy(gtceuHotfix$satCast(maximalValue), true);
        if (consumable == 0) return 0;

        consumable = s.receiveEnergy(consumable, false);

        if ((long) consumable % maxPacketFe == 0) {
            gtceuHotfix$feBuffer = 0;
            return (long) consumable / maxPacketFe;
        }

        long ampsToConsume = ((long) consumable / maxPacketFe) + 1;
        gtceuHotfix$feBuffer = (maxPacketFe * ampsToConsume) - (long) consumable;
        return ampsToConsume;
    }

    /**
     * Advertise essentially unlimited input voltage so IV/LuV+ networks will still attempt delivery.
     * The acceptEnergyFromNetwork implementation buffers FE remainder so no energy is lost even if the sink
     * can't accept a whole high-tier packet in a single tick.
     * @author GTCEuEnergyNetHotfix
     * @reason Allow high-tier networks to attempt delivery; actual insertion is capped by FE throughput and buffered remainder.
     */
    @Overwrite
    public long getInputVoltage() {
        IEnergyStorage s = gtceuHotfix$getStorage();
        if (s == null || !s.canReceive()) return 0;
        return Long.MAX_VALUE;
    }

    /**
     * @author GTCEuEnergyNetHotfix
     * @reason Expose an effectively unbounded amperage to allow the network to offer amps; acceptance is limited per tick by FE insertion.
     */
    @Overwrite
    public long getInputAmperage() {
        IEnergyStorage s = gtceuHotfix$getStorage();
        if (s == null || !s.canReceive()) return 0;
        return Long.MAX_VALUE;
    }

    /**
     * @author GTCEuEnergyNetHotfix
     * @reason This wrapper represents an FE sink, so it is always considered an input on any queried face.
     */
    @Overwrite
    public boolean inputsEnergy(Direction facing) {
        IEnergyStorage s = gtceuHotfix$getStorage();
        return s != null && s.canReceive();
    }

    /**
     * @author GTCEuEnergyNetHotfix
     * @reason This wrapper never outputs EU back into the network.
     */
    @Overwrite
    public boolean outputsEnergy(Direction facing) {
        return false;
    }

    /**
     * @author GTCEuEnergyNetHotfix
     * @reason Support internal buffer adjustments in EU units by translating to FE storage operations with ratio alignment.
     */
    @Overwrite
    public long changeEnergy(long differenceAmount) {
        // Keep original behaviour: this wrapper is a sink adapter; direct delta changes are unsupported here.
        return 0;
    }

    /**
     * @author GTCEuEnergyNetHotfix
     * @reason Report stored energy in EU units based on the wrapped FE storage.
     */
    @Overwrite
    public long getEnergyStored() {
        IEnergyStorage s = gtceuHotfix$getStorage();
        if (s == null) return 0;
        return FeCompat.toEu(s.getEnergyStored(), FeCompat.ratio(false));
    }

    /**
     * @author GTCEuEnergyNetHotfix
     * @reason Report capacity in EU units based on the wrapped FE storage.
     */
    @Overwrite
    public long getEnergyCapacity() {
        IEnergyStorage s = gtceuHotfix$getStorage();
        if (s == null) return 0;
        return FeCompat.toEu(s.getMaxEnergyStored(), FeCompat.ratio(false));
    }

    /**
     * @author GTCEuEnergyNetHotfix
     * @reason Allow high-tier EU packets to be buffered (GTCEu semantics).
     *
     * The wrapped FE sink often cannot accept a full packet worth of FE in a single tick.
     * GTCEu's wrapper is expected to buffer the remainder and drip-feed it over subsequent
     * ticks. If we report "insertable" strictly from FE storage headroom, the energy net
     * treats the endpoint as unable to accept even a single EU packet (e.g. LuV packets)
     * and drops it from the sink list, causing FE blocks (AE2 ME Controller) to stay offline.
     */
    @Overwrite
    public long getEnergyCanBeInserted() {
        IEnergyStorage s = gtceuHotfix$getStorage();
        if (s == null) return 0;
        if (!s.canReceive()) return 0;

        // "Very large" so sink validation (euSpace >= voltage) won't filter out the endpoint.
        // Actual throughput + amperage drain is still governed by acceptEnergyFromNetwork(),
        // which is based on the real amount inserted into FE this tick.
        return Long.MAX_VALUE / 4;
    }
}