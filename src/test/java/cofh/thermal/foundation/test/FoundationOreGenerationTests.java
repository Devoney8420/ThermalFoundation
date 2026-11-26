package cofh.thermal.foundation.test;

import com.mojang.serialization.Lifecycle;
import net.minecraft.Util;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static cofh.lib.util.FlagManager.setFlag;
import static cofh.thermal.lib.util.ThermalFlags.FLAG_RESOURCE_APATITE;
import static cofh.thermal.lib.util.ThermalFlags.FLAG_RESOURCE_CINNABAR;

final class FoundationOreGenerationTests {
    private static final long SEED = 8675309L;
    private static final int RADIUS = 4;
    private static final List<String> DEFAULT_ORES = List.of("niter", "sulfur", "lead", "nickel", "silver", "tin");
    private static final List<String> ADDON_ORES = List.of("apatite", "cinnabar");

    static void gather(GatherDataEvent event) {
        event.addProvider(new DataProvider() {
            @Override
            public CompletableFuture<?> run(CachedOutput output) {
                try {
                    verifyOverworld();
                    return CompletableFuture.completedFuture(null);
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
            }

            @Override
            public String getName() {
                return "Foundation fresh Overworld ore generation";
            }
        });
    }

    private static void verifyOverworld() throws Exception {
        var packs = ServerPacksSource.createVanillaTrustedRepository();
        var dataConfig = new WorldLoader.PackConfig(packs, WorldDataConfiguration.DEFAULT, false, true);
        var init = new WorldLoader.InitConfig(dataConfig, Commands.CommandSelection.DEDICATED, 2);
        var gameRules = new GameRules();
        gameRules.getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, null);
        var stem = Util.<WorldStem>blockUntilDone(mainExecutor -> WorldLoader.load(init, context -> {
            var dimensions = context.datapackWorldgen().registryOrThrow(Registries.WORLD_PRESET)
                    .getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions()
                    .bake(new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable()).freeze());
            var settings = new LevelSettings("Foundation ore test", GameType.CREATIVE, false, Difficulty.NORMAL,
                    true, gameRules, context.dataConfiguration());
            return new WorldLoader.DataLoadOutput<>(new PrimaryLevelData(settings, new WorldOptions(SEED, false, false),
                    dimensions.specialWorldProperty(), dimensions.lifecycle()), dimensions.dimensionsRegistryAccess());
        }, WorldStem::new, Util.backgroundExecutor(), mainExecutor)).get();

        Path testRoot = Files.createTempDirectory(Path.of("."), "foundation-ore-world-");
        ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.COMMON, Files.createDirectory(testRoot.resolve("config")));
        var storage = LevelStorageSource.createDefault(testRoot).validateAndCreateAccess("normal-overworld");
        // The GameTestServer factory forces Superflat. Supply the normal world stem to its test server.
        var constructor = GameTestServer.class.getDeclaredConstructor(Thread.class,
                LevelStorageSource.LevelStorageAccess.class, PackRepository.class, WorldStem.class,
                Collection.class, BlockPos.class);
        constructor.setAccessible(true);
        var server = constructor.newInstance(Thread.currentThread(), storage, packs, stem, List.of(), BlockPos.ZERO);
        try (server) {
            if (!server.initServer()) {
                throw new IllegalStateException("Foundation test server failed to initialize");
            }
            try {
                var world = server.overworld();
                var ores = new ArrayList<>(DEFAULT_ORES);
                ores.addAll(ADDON_ORES);
                var defaults = countOres(world, 0, ores);
                System.out.println("Foundation default ore counts in 81 fresh normal Overworld chunks, seed " + SEED + ": " + defaults);
                if (DEFAULT_ORES.stream().anyMatch(ore -> defaults.get("thermal:" + ore + "_ore") == 0
                        || defaults.get("thermal:deepslate_" + ore + "_ore") == 0)) {
                    throw new AssertionError("A default Foundation ore variant did not generate: " + defaults);
                }
                if (ADDON_ORES.stream().anyMatch(ore -> defaults.get("thermal:" + ore + "_ore") != 0
                        || defaults.get("thermal:deepslate_" + ore + "_ore") != 0)) {
                    throw new AssertionError("An addon ore generated with its flag disabled: " + defaults);
                }
                setFlag(FLAG_RESOURCE_APATITE, true);
                setFlag(FLAG_RESOURCE_CINNABAR, true);
                try {
                    var enabled = countOres(world, 24, ADDON_ORES);
                    System.out.println("Foundation ore counts with addon flags enabled in 81 new chunks: " + enabled);
                    if (enabled.containsValue(0)) {
                        throw new AssertionError("An enabled addon ore variant did not generate: " + enabled);
                    }
                } finally {
                    setFlag(FLAG_RESOURCE_APATITE, false);
                    setFlag(FLAG_RESOURCE_CINNABAR, false);
                }
            } finally {
                ServerLifecycleHooks.handleServerStopping(server);
            }
        } finally {
            ServerLifecycleHooks.handleServerStopped(server);
            // Let data generation finish writing its cache while retiring the idle server I/O workers.
            if (Util.ioPool() instanceof ThreadPoolExecutor executor) {
                executor.setKeepAliveTime(1, TimeUnit.SECONDS);
            }
        }
    }

    private static TreeMap<String, Integer> countOres(ServerLevel world, int centerX, List<String> ores) {
        var totals = new TreeMap<String, Integer>();
        for (String ore : ores) {
            totals.put("thermal:" + ore + "_ore", 0);
            totals.put("thermal:deepslate_" + ore + "_ore", 0);
        }
        for (int x = centerX - RADIUS; x <= centerX + RADIUS; ++x) {
            for (int z = -RADIUS; z <= RADIUS; ++z) {
                for (var section : world.getChunk(x, z).getSections()) {
                    section.getStates().count((state, count) -> {
                        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        totals.computeIfPresent(id, (name, total) -> total + count);
                    });
                }
            }
        }
        return totals;
    }
}
