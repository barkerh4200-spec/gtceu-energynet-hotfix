package your.mod.command;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "gtceuenergynethotfix", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommandRegistration {

    private CommandRegistration() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        EnergyNetCommand.register(event.getDispatcher());
    }
}
