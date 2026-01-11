package your.mod.mixin;

import com.gregtechceu.gtceu.common.pipelike.cable.EnergyRoutePath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import your.mod.energy.IRouteSegmentData;

/**
 * Stores per-segment cached route data on GTCEu's {@link EnergyRoutePath}.
 *
 * <p>Populated once at route creation time (in {@code EnergyNetWalkerMixin}) to avoid
 * expensive BE/node-data reads in the hot transfer loop.</p>
 */
@Mixin(value = EnergyRoutePath.class, remap = false)
public abstract class EnergyRoutePathSegmentMixin implements IRouteSegmentData {

    @Unique private long[] gtceuHotfix$posLong;
    @Unique private long[] gtceuHotfix$maxVoltage;
    @Unique private int[] gtceuHotfix$lossPerBlock;

    @Override
    public void gtceuHotfix$setSegmentData(long[] posLong, long[] maxVoltage, int[] lossPerBlock) {
        this.gtceuHotfix$posLong = posLong;
        this.gtceuHotfix$maxVoltage = maxVoltage;
        this.gtceuHotfix$lossPerBlock = lossPerBlock;
    }

    @Override
    public long[] gtceuHotfix$getPosLong() {
        return gtceuHotfix$posLong;
    }

    @Override
    public long[] gtceuHotfix$getMaxVoltage() {
        return gtceuHotfix$maxVoltage;
    }

    @Override
    public int[] gtceuHotfix$getLossPerBlock() {
        return gtceuHotfix$lossPerBlock;
    }
}
