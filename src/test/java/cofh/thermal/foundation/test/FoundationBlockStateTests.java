package cofh.thermal.foundation.test;

import cofh.lib.common.block.OreBlockCoFH;
import cofh.thermal.foundation.init.registries.TFndBlocks;
import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.renderer.block.model.BlockModelDefinition;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

@Mod("thermal_foundation_tests")
public final class FoundationBlockStateTests {
    public FoundationBlockStateTests(IEventBus bus) {
        bus.addListener(FoundationBlockStateTests::gather);
        bus.addListener(FoundationWorldLoadTests::gather);
    }

    private static void gather(GatherDataEvent event) {
        event.addProvider(new DataProvider() {
            @Override
            public CompletableFuture<?> run(CachedOutput output) {
                return event.getLookupProvider().thenAccept(registries -> {
                    try {
                        verify(event.getResourceManager(PackType.CLIENT_RESOURCES),
                                event.getResourceManager(PackType.SERVER_DATA), registries.createSerializationContext(JsonOps.INSTANCE));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }

            @Override
            public String getName() {
                return "Thermal Foundation block and block-state tests";
            }
        });
    }

    private static void verify(ResourceManager assets, ResourceManager data, DynamicOps<JsonElement> ops) throws IOException {
        Map<String, Class<? extends Block>> expectedBlocks = new LinkedHashMap<>();
        for (String ore : List.of("apatite", "cinnabar", "niter", "sulfur", "tin", "lead", "silver", "nickel", "ruby", "sapphire")) {
            expectedBlocks.put(ore + "_ore", OreBlockCoFH.class);
            expectedBlocks.put("deepslate_" + ore + "_ore", OreBlockCoFH.class);
        }
        expectedBlocks.put("oil_sand", FallingBlock.class);
        expectedBlocks.put("oil_red_sand", FallingBlock.class);
        for (String metal : List.of("tin", "lead", "silver", "nickel")) {
            expectedBlocks.put("raw_" + metal + "_block", Block.class);
        }
        for (String material : List.of("tin", "lead", "silver", "nickel", "bronze", "electrum", "invar", "constantan", "ruby", "sapphire")) {
            expectedBlocks.put(material + "_block", Block.class);
        }
        for (String pillar : List.of("rubberwood_log", "rubberwood_wood", "stripped_rubberwood_log", "stripped_rubberwood_wood")) {
            expectedBlocks.put(pillar, RotatedPillarBlock.class);
        }
        expectedBlocks.put("rubberwood_leaves", LeavesBlock.class);
        expectedBlocks.put("rubberwood_sapling", SaplingBlock.class);
        expectedBlocks.put("potted_rubberwood_sapling", Block.class);
        for (String wood : List.of("planks", "slab", "stairs", "door", "trapdoor", "button", "pressure_plate", "fence", "fence_gate",
                "sign", "wall_sign", "hanging_sign", "wall_hanging_sign")) {
            expectedBlocks.put("rubberwood_" + wood, Block.class);
        }

        TFndBlocks.setup();
        checkDefaultsAndSetup();

        int states = 0;
        var lootTables = new HashSet<ResourceLocation>();
        for (var entry : expectedBlocks.entrySet()) {
            String name = entry.getKey();
            Block block = block(name);
            require(entry.getValue().isInstance(block), name + " registered as " + block.getClass().getSimpleName());
            states += checkStates(assets, name, block);
            lootTables.add(block.getLootTable().location());
        }
        for (var id : lootTables) {
            var path = id.withPath(name -> "loot_table/" + name + ".json");
            try (var reader = data.getResourceOrThrow(path).openAsReader()) {
                LootTable.DIRECT_CODEC.parse(ops, GsonHelper.parse(reader))
                        .getOrThrow(message -> new AssertionError(path + ": " + message));
            }
        }
        for (String recipe : List.of("rubberwood_chest_boat", "rubberwood_planks", "rubberwood_door",
                "machines/press/press_bronze_ingot_to_plate", "fuels/numismatic/numismatic_bronze_coin")) {
            var path = ResourceLocation.fromNamespaceAndPath("thermal", "recipe/" + recipe + ".json");
            try (var reader = data.getResourceOrThrow(path).openAsReader()) {
                Recipe.CODEC.parse(ops, GsonHelper.parse(reader))
                        .getOrThrow(message -> new AssertionError(path + ": " + message));
            }
        }
        System.out.println("Verified " + expectedBlocks.size() + " Foundation blocks, " + states
                + " registered block states, and " + lootTables.size() + " block loot tables.");
    }

    private static void checkDefaultsAndSetup() {
        require(block("rubberwood_log").defaultBlockState().getValue(BlockStateProperties.AXIS) == Direction.Axis.Y,
                "Rubberwood log must default to the vertical axis");
        require(block("rubberwood_sapling").defaultBlockState().getValue(BlockStateProperties.STAGE) == 0,
                "Rubberwood sapling must start at stage zero");
        require(block("rubberwood_slab").defaultBlockState().getValue(BlockStateProperties.SLAB_TYPE) == SlabType.BOTTOM,
                "Rubberwood slab must default to the bottom half");
        for (String name : List.of("rubberwood_door", "rubberwood_trapdoor", "rubberwood_fence_gate")) {
            var state = block(name).defaultBlockState();
            require(!state.getValue(BlockStateProperties.OPEN) && !state.getValue(BlockStateProperties.POWERED),
                    name + " must default to closed and unpowered");
        }
        require(AxeItem.STRIPPABLES.get(block("rubberwood_log")) == block("stripped_rubberwood_log"),
                "Axe stripping must use the registered rubberwood log");
        require(AxeItem.STRIPPABLES.get(block("rubberwood_wood")) == block("stripped_rubberwood_wood"),
                "Axe stripping must use the registered rubberwood wood");
        for (String name : List.of("rubberwood_sign", "rubberwood_wall_sign")) {
            require(BlockEntityType.SIGN.isValid(block(name).defaultBlockState()), name + " must use the sign block entity");
        }
        for (String name : List.of("rubberwood_hanging_sign", "rubberwood_wall_hanging_sign")) {
            require(BlockEntityType.HANGING_SIGN.isValid(block(name).defaultBlockState()), name + " must use the hanging sign block entity");
        }
    }

    private static int checkStates(ResourceManager resources, String name, Block block) throws IOException {
        var definition = block.getStateDefinition();
        var context = new BlockModelDefinition.Context();
        context.setDefinition(definition);
        var path = ResourceLocation.fromNamespaceAndPath("thermal", "blockstates/" + name + ".json");
        BlockModelDefinition model;
        try (var reader = resources.getResourceOrThrow(path).openAsReader()) {
            model = BlockModelDefinition.fromStream(context, reader);
        }
        var variants = model.getVariants().keySet().stream()
                .map(key -> variantPredicate(definition, key)).toList();
        var multipart = model.isMultiPart() ? model.getMultiPart().getSelectors().stream()
                .map(selector -> selector.getPredicate(definition)).toList() : List.<Predicate<BlockState>>of();
        var states = definition.getPossibleStates();
        for (BlockState state : states) {
            require(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), NbtUtils.writeBlockState(state)) == state,
                    "NBT round trip failed for " + state);
            long matchedVariants = variants.stream().filter(test -> test.test(state)).count();
            require(matchedVariants <= 1 && (matchedVariants == 1 || multipart.stream().anyMatch(test -> test.test(state))),
                    "Expected an unambiguous model for " + state);
        }
        return states.size();
    }

    private static Predicate<BlockState> variantPredicate(StateDefinition<Block, BlockState> definition, String key) {
        Predicate<BlockState> predicate = state -> true;
        if (!key.isEmpty()) {
            for (String pair : key.split(",")) {
                String[] assignment = pair.split("=", 2);
                var property = definition.getProperty(assignment[0]);
                require(assignment.length == 2 && property != null, "Invalid selector " + key + " for " + definition.getOwner());
                var value = property.getValue(assignment[1]).orElseThrow(() -> new AssertionError("Invalid selector value " + pair));
                predicate = predicate.and(state -> state.getValue(property).equals(value));
            }
        }
        return predicate;
    }

    private static Block block(String name) {
        var id = ResourceLocation.fromNamespaceAndPath("thermal", name);
        require(BuiltInRegistries.BLOCK.containsKey(id), "Missing block " + id);
        return BuiltInRegistries.BLOCK.get(id);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
