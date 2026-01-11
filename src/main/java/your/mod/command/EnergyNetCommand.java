package your.mod.command;

import com.gregtechceu.gtceu.common.pipelike.cable.EnergyNet;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import your.mod.energy.EnergyNetDebugStats;

import java.util.List;
import java.util.Map;

public final class EnergyNetCommand {

    private EnergyNetCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("energynet")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> execute(ctx.getSource(), false))
                        .then(Commands.literal("all")
                                .executes(ctx -> execute(ctx.getSource(), true)))
        );
    }

    private static int execute(CommandSourceStack src, boolean all) {
        int tracked = EnergyNetDebugStats.trackedNetCount();
        src.sendSuccess(() -> Component.literal("Tracked energynets: " + tracked), false);

        int limit = all ? Integer.MAX_VALUE : 10;
        List<Map.Entry<EnergyNet, EnergyNetDebugStats.NetStats>> top = EnergyNetDebugStats.topByRouteChecks(limit);
        if (top.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No energynet stats yet (wait a few ticks with active networks)."), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal((all ? "All nets" : "Top 10 nets") + " by avg route checks/tick (EWMA):"), false);

        StringBuilder clip = new StringBuilder();
        int i = 0;
        for (Map.Entry<EnergyNet, EnergyNetDebugStats.NetStats> e : top) {
            i++;
            EnergyNetDebugStats.NetStats s = e.getValue();
            String dim = (s.lastDim == null) ? "?" : s.lastDim.location().toString();

            String line =
                    "#" + i +
                    " dim=" + dim +
                    " accept=" + s.curAcceptCalls + "/" + fmt(s.avgAcceptCalls) +
                    " routes=" + s.curRouteChecks + "/" + fmt(s.avgRouteChecks) +
                    " sinks=" + s.curSinkComputes + "/" + fmt(s.avgSinkComputes) +
                    " rebuilds=" + s.curNetRebuilds + "/" + fmt(s.avgNetRebuilds);

            src.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
            clip.append(line).append("\n");
        }
        String clipText = clip.toString();
        if (!clipText.isEmpty()) {
            Component copy = Component.literal("[Click to copy energynet stats]")
                    .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, clipText))
                            .withUnderlined(true));
            src.sendSuccess(() -> copy, false);
        }
        return 1;
    }

    private static String fmt(double v) {
        if (v < 0.05) return "0";
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}