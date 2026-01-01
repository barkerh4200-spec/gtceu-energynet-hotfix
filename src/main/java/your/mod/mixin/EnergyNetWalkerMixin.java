package your.mod.mixin;

import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNetWalker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Preserve GTCEu's original route building and loss computation (especially for superconductors),
 * while ensuring endpoint discovery uses GTCapabilityHelper.getEnergyContainer(...).
 *
 * Our GTCapabilityHelper mixin extends that helper to wrap Forge Energy storages as IEnergyContainer,
 * so FE neighbors become valid endpoints without rewriting EnergyNetWalker's logic.
 */
@Mixin(value = EnergyNetWalker.class, remap = false)
public abstract class EnergyNetWalkerMixin {

    @Unique private static boolean LOGGED = false;

    @Redirect(
            method = "checkNeighbour",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/gregtechceu/gtceu/api/capability/GTCapabilityHelper;getEnergyContainer(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Lcom/gregtechceu/gtceu/api/capability/IEnergyContainer;"
            )
    )
    private IEnergyContainer gtceuEnergyNetHotfix$redirectGetEnergyContainer(Level level, BlockPos pos, Direction side) {
        if (!LOGGED) {
            LOGGED = true;
            org.apache.logging.log4j.LogManager.getLogger("GTCEuEnergyNetHotfix")
                    .info("Mixin active: EnergyNetWalkerMixin (capability redirect; preserves loss computation)");
        }
        return GTCapabilityHelper.getEnergyContainer(level, pos, side);
    }
}
