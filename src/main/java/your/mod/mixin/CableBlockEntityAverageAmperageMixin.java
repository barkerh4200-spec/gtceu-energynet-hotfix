package your.mod.mixin;

import com.gregtechceu.gtceu.common.blockentity.CableBlockEntity;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gates getAverageAmperage() behind a 64-block nearby-player check to reduce
 * server-side UI polling cost (Jade/TOP/placeholders) on large networks.
 *
 * This does NOT affect real energy delivery; it's purely for UI readouts.
 */
@Mixin(value = CableBlockEntity.class, remap = false)
public abstract class CableBlockEntityAverageAmperageMixin {

    @Inject(method = "getAverageAmperage", at = @At("HEAD"), cancellable = true)
    private void gtceu_energynet_hotfix$gateAverageAmperage(CallbackInfoReturnable<Double> cir) {
        CableBlockEntity self = (CableBlockEntity)(Object)this;
        if (!(self.getLevel() instanceof ServerLevel level)) {
            return;
        }

        // If no players are nearby, return 0 to avoid expensive polling on remote cable backbones.
        if (!level.hasNearbyAlivePlayer(
                self.getBlockPos().getX() + 0.5D,
                self.getBlockPos().getY() + 0.5D,
                self.getBlockPos().getZ() + 0.5D,
                64.0D
        )) {
            cir.setReturnValue(0.0D);
        }
    }
}
