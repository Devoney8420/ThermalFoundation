package cofh.thermal.foundation.test;

import cofh.thermal.core.common.config.ThermalCoreConfig;
import net.minecraft.Util;
import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.BasicItemListing;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.event.village.WandererTradesEvent;
import net.neoforged.neoforge.registries.RegistryManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class FoundationWorldLoadTests {
    static void gather(GatherDataEvent event) {
        event.addProvider(new DataProvider() {
            @Override
            public CompletableFuture<?> run(CachedOutput output) {
                // Data generation skips these normal startup steps, which world loading depends on.
                RegistryManager.initDataMaps();
                NeoForge.EVENT_BUS.start();
                var packConfig = new WorldLoader.PackConfig(ServerPacksSource.createVanillaTrustedRepository(),
                        WorldDataConfiguration.DEFAULT, false, true);
                var init = new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.INTEGRATED, 2);
                return WorldLoader.load(init, context -> new WorldLoader.DataLoadOutput<Void>(null, context.datapackDimensions()),
                        (packs, resources, registries, cookie) -> {
                            try (packs) {
                                for (String mob : List.of("basalz", "blizz", "blitz")) {
                                    var key = ResourceKey.create(Registries.LOOT_TABLE,
                                            ResourceLocation.fromNamespaceAndPath("thermal", "entities/" + mob));
                                    require(resources.fullRegistries().getLootTable(key) != LootTable.EMPTY,
                                            "Missing mob loot table " + key.location());
                                }
                                checkFuel();
                                checkTrades(registries.compositeAccess());
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                            return null;
                        }, Util.backgroundExecutor(), Runnable::run);
            }

            @Override
            public String getName() {
                return "Foundation world-load and trade regression tests";
            }
        });
    }

    private static void checkFuel() {
        require(burnTime("bronze_ingot") == 0, "Bronze ingots must return a valid zero burn time");
        require(burnTime("tin_ore") == 0, "Ore block items must return a valid zero burn time");
        require(burnTime("oil_sand") == 2400, "Oil sand must keep its explicit burn time");
        require(burnTime("rubberwood_planks") > 0, "Rubberwood planks must inherit vanilla fuel behavior");
    }

    private static int burnTime(String name) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("thermal", name));
        require(item != Items.AIR, "Missing fuel test item " + name);
        return new ItemStack(item).getBurnTime(null);
    }

    private static void checkTrades(RegistryAccess registries) throws IOException {
        var setting = (ModConfigSpec.BooleanValue) ThermalCoreConfig.enableWandererTrades;
        var offers = VillagerTrades.WANDERING_TRADER_TRADES.get(2);
        require(offers != null && offers.length > 1, "Missing registered rare trades");

        // Use a context-free fallback so this test can exercise offer selection without spawning a trader.
        var rareTrades = new ArrayList<VillagerTrades.ItemListing>();
        rareTrades.add(new BasicItemListing(1, new ItemStack(Items.OAK_SAPLING), 1, 1));
        NeoForge.EVENT_BUS.post(new WandererTradesEvent(new ArrayList<>(), rareTrades, registries));
        require(rareTrades.size() == 2, "Missing registered Foundation trade");
        var foundationTrade = rareTrades.getLast();
        var random = RandomSource.create(14);
        var sapling = BuiltInRegistries.ITEM.get(ResourceLocation.parse("thermal:rubberwood_sapling"));
        var beforeConfigLoad = foundationTrade.getOffer(null, random);
        require(beforeConfigLoad != null && beforeConfigLoad.getResult().is(Items.OAK_SAPLING),
                "The offer preview before config loading must preserve a rare offer");

        Path config = Files.createTempDirectory(Path.of("."), "foundation-server-config-");
        ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.SERVER, config);
        try {
            setting.set(true);
            var enabled = foundationTrade.getOffer(null, random);
            require(enabled != null && enabled.getResult().is(sapling)
                    && enabled.getBaseCostA().is(Items.EMERALD) && enabled.getBaseCostA().getCount() == 8,
                    "Enabled trade must offer the sapling for eight emeralds");
            setting.set(false);
            var disabled = foundationTrade.getOffer(null, random);
            require(disabled != null && disabled.getResult().is(Items.OAK_SAPLING),
                    "Disabling the sapling trade must preserve a rare offer");
        } finally {
            ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
        }
        var afterConfigUnload = foundationTrade.getOffer(null, random);
        require(afterConfigUnload != null && afterConfigUnload.getResult().is(Items.OAK_SAPLING),
                "Unloading the config must restore the fallback trade");
        System.out.println("WorldLoader completed before server config load. Trade settings, config unload, fuel and three elemental loot tables passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
