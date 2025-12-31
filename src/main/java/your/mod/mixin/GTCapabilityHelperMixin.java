package your.mod.mixin;

import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import your.mod.energy.FeEnergyContainerWrapper;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * If GTCEu energy container is absent, fall back to wrapping Forge Energy storage as IEnergyContainer.
 * This makes FE machines valid endpoints for GTCEu cables/wires without requiring cables to expose FE directly.
 *
 * Debugging: logs a small number of successful wraps so we can compare EV vs IV/LuV behaviour.
 */
@Mixin(value = GTCapabilityHelper.class, remap = false)
public abstract class GTCapabilityHelperMixin {

    @Unique private static boolean GTCEU_HOTFIX_LOGGED = false;
    @Unique private static boolean UNSIDED_WRAP_LOGGED = false;
    @Unique private static final AtomicInteger WRAP_LOG_COUNT = new AtomicInteger(0);

    @Inject(
            method = "getEnergyContainer(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Lcom/gregtechceu/gtceu/api/capability/IEnergyContainer;",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void gtceuHotfix$wrapForgeEnergy(Level level, BlockPos pos, Direction side,
                                                   CallbackInfoReturnable<IEnergyContainer> cir) {
        if (cir.getReturnValue() != null) return;

        if (level == null || pos == null) return;
        if (!level.getBlockState(pos).hasBlockEntity()) return;

        var be = level.getBlockEntity(pos);
        if (be == null) return;

        IEnergyStorage fe = be.getCapability(ForgeCapabilities.ENERGY, side).orElse(null);

        // Unsided fallback: some mods only expose FE unsided.
        if (fe == null && side != null) {
            fe = be.getCapability(ForgeCapabilities.ENERGY, null).orElse(null);
            if (fe != null && !UNSIDED_WRAP_LOGGED) {
                UNSIDED_WRAP_LOGGED = true;
}
        }

        if (fe == null) return;
        if (!fe.canReceive()) return;

        if (!GTCEU_HOTFIX_LOGGED) {
            GTCEU_HOTFIX_LOGGED = true;
}

        int n = WRAP_LOG_COUNT.getAndIncrement();
        if (n < 20) {
            int simMax = 0;
            try {
                simMax = fe.receiveEnergy(Integer.MAX_VALUE, true);
            } catch (Throwable ignored) {}
}

        cir.setReturnValue(new FeEnergyContainerWrapper(fe));
    }
}
