package your.mod.mixin;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.forge.GTCapability;
import com.gregtechceu.gtceu.api.data.chemical.material.properties.WireProperties;
import com.gregtechceu.gtceu.common.blockentity.CableBlockEntity;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyRoutePath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import your.mod.energy.FeEnergyContainerWrapper;
import your.mod.energy.IRouteSegmentData;

/**
 * Surgical hook: keep GTCEu's EnergyNetWalker logic intact (including loss computation),
 * but allow FE-only endpoints (ForgeCapabilities.ENERGY / IEnergyStorage) to be treated as EU sinks
 * by returning a wrapped IEnergyContainer when GT's energy container capability is absent.
 */
@Mixin(value = com.gregtechceu.gtceu.common.pipelike.cable.EnergyNetWalker.class, remap = false)
public abstract class EnergyNetWalkerMixin {

    /**
     * Cache per-segment route data once at route creation time so EnergyNetHandler's hot path
     * does not need to touch CableBlockEntity/node-data to compute max voltage and loss per block.
     */
    @Redirect(
        method = "checkNeighbour",
        at = @At(
            value = "NEW",
            target = "com/gregtechceu/gtceu/common/pipelike/cable/EnergyRoutePath"
        )
    )
    private EnergyRoutePath gtceuHotfix$newEnergyRoutePath(BlockPos targetPipePos, Direction targetFacing,
                                                          CableBlockEntity[] path, int distance, long maxLoss) {
        final EnergyRoutePath route = new EnergyRoutePath(targetPipePos, targetFacing, path, distance, maxLoss);

        final int n = (path == null) ? 0 : path.length;
        final long[] posLong = new long[n];
        final long[] maxV = new long[n];
        final int[] loss = new int[n];

        for (int i = 0; i < n; i++) {
            final CableBlockEntity cable = path[i];
            if (cable == null) continue;

            posLong[i] = cable.getBlockPos().asLong();

            // WireProperties holds both voltage rating and loss per block.
            final WireProperties props = (WireProperties) cable.getNodeData();
            if (props != null) {
                maxV[i] = props.getVoltage();
                loss[i] = props.getLossPerBlock();
            }
        }

        ((IRouteSegmentData) route).gtceuHotfix$setSegmentData(posLong, maxV, loss);
        return route;
    }

    @Redirect(
        method = "checkNeighbour",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;getCapability(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/core/Direction;)Lnet/minecraftforge/common/util/LazyOptional;"
        )
    )
    private LazyOptional<?> gtceuEutofe_redirectEnergyContainerCapability(BlockEntity be, Capability<?> cap, Direction side) {
        // Preserve original behavior for all other capabilities.
        LazyOptional<?> original = be.getCapability(cap, side);

        // Only intervene when GTCEu is probing for the GT energy container and it is absent.
        if (cap == GTCapability.CAPABILITY_ENERGY_CONTAINER && (original == null || !original.isPresent())) {
            // Sided FE first
            IEnergyStorage storage = be.getCapability(ForgeCapabilities.ENERGY, side).orElse(null);

            // Unsided fallback if sided is absent (some mods expose FE unsided only)
            if (storage == null) {
                storage = be.getCapability(ForgeCapabilities.ENERGY, null).orElse(null);
            }

            if (storage != null) {
                IEnergyContainer wrapped = new FeEnergyContainerWrapper(storage);
                return LazyOptional.of(() -> wrapped);
            }
        }

        return original;
    }
}
