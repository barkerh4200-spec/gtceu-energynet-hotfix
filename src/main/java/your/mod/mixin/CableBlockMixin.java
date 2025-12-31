package your.mod.mixin;

import com.gregtechceu.gtceu.api.pipenet.IPipeNode;
import com.gregtechceu.gtceu.common.block.CableBlock;
import com.gregtechceu.gtceu.common.pipelike.cable.Insulation;
import com.gregtechceu.gtceu.api.data.chemical.material.properties.WireProperties;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import javax.annotation.Nullable;

@Mixin(value = CableBlock.class, remap = false)
public abstract class CableBlockMixin {

    private static boolean LOGGED = false;
    private static boolean UNSIDED_FALLBACK_LOGGED = false;

    /**
     * Gate FE capability probes behind "face not blocked" so we only check explicitly enabled faces.
     * This runs only during topology/connect checks (placements/updates), not per-tick routing.
     */
    /**
 * @author Henry
 * @reason Allow FE endpoints on output-enabled faces without per-tick scanning
 */
@Overwrite
public boolean canPipeConnectToBlock(IPipeNode<Insulation, WireProperties> selfTile, Direction side,
                                           @Nullable BlockEntity tile) {

        if (!LOGGED) {
            LOGGED = true;
            org.apache.logging.log4j.LogManager.getLogger("GTCEuEnergyNetHotfix")
                    .info("CableBlockMixin active");
        }

        // "output-enabled" == "not blocked"
        if (selfTile.isBlocked(side)) {
            return false;
        }

        if (tile == null) return false;

        // 1) GT energy container capability (fast path)
        var gt = tile.getCapability(com.gregtechceu.gtceu.api.capability.forge.GTCapability.CAPABILITY_ENERGY_CONTAINER,
                side.getOpposite()).resolve().orElse(null);
        if (gt != null) return true;

        // 2) ForgeCapabilities.ENERGY sided
        IEnergyStorage fe = tile.getCapability(ForgeCapabilities.ENERGY, side.getOpposite()).orElse(null);
        if (fe != null && (fe.canReceive() || fe.canExtract())) {
            return true;
        }

        // 3) Unsided fallback only on explicitly enabled faces (we're already gated by isBlocked)
        fe = tile.getCapability(ForgeCapabilities.ENERGY, null).orElse(null);
        if (fe != null && (fe.canReceive() || fe.canExtract())) {
            if (!UNSIDED_FALLBACK_LOGGED) {
                UNSIDED_FALLBACK_LOGGED = true;
                org.apache.logging.log4j.LogManager.getLogger("GTCEuEnergyNetHotfix").info(
                        "CableBlockMixin using UNSIDED FE fallback for neighbor {} (this is expected for some mods)",
                        tile.getClass().getName()
                );
            }
            return true;
        }
        return false;
    }
}
