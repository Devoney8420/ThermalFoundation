package cofh.thermal.foundation.common.event;

import cofh.thermal.core.common.config.ThermalCoreConfig;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.BasicItemListing;
import net.neoforged.neoforge.event.village.WandererTradesEvent;

import java.util.List;

import static cofh.core.util.helpers.ItemHelper.cloneStack;
import static cofh.lib.util.constants.ModIds.ID_THERMAL_FOUNDATION;
import static cofh.thermal.core.ThermalCore.CONFIG_MANAGER;
import static cofh.thermal.core.ThermalCore.ITEMS;
import static cofh.thermal.foundation.init.registries.TFndIDs.ID_RUBBERWOOD_SAPLING;

@EventBusSubscriber (modid = ID_THERMAL_FOUNDATION)
public class TFndCommonSetupEvents {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void setupWandererTrades(final WandererTradesEvent event) {

        var otherRareTrades = List.copyOf(event.getRareTrades());
        var saplingTrade = new BasicItemListing(cloneStack(Items.EMERALD, 8), cloneStack(ITEMS.get(ID_RUBBERWOOD_SAPLING)), 8, 1, 0.05F);
        // This event can run before NeoForge loads the world's server config.
        event.getRareTrades().add((trader, random) -> {
            if (CONFIG_MANAGER.getServerSpec().isLoaded() && ThermalCoreConfig.enableWandererTrades.get()) {
                return saplingTrade.getOffer(trader, random);
            }
            // The normal trader picks one rare listing, so preserve that slot when the trade is disabled.
            return otherRareTrades.isEmpty() ? null : otherRareTrades.get(random.nextInt(otherRareTrades.size())).getOffer(trader, random);
        });
    }

}
