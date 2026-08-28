package gg.nano.ui.legacy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.WoodType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registers the blocks 26.2 has and 1.21 does not, as real blocks.
 *
 * <p>The server-side trick of dressing up an existing block can only go so far. It needs a
 * spare block of the right shape for every new one, it breaks down the moment two of them want
 * the same carrier, and what the player ends up with is a stair pretending to be a shelf. It
 * is the best a vanilla client can do and it is not what this is.
 *
 * <p>With the mod present the client is not vanilla, so the blocks are simply registered.
 * Mojang already wrote the models and the textures, and they are in the jar next to this. What
 * is left is telling the game each block exists and what shape it is, which is what this does.
 *
 * <p>Shapes come from matching a vanilla family rather than being described here. A stair
 * registered as a {@link StairBlock} gets stair collision, stair placement, stair waterlogging
 * and the stair state properties, all correct, none of it written down. Describing 137
 * hitboxes by hand would be 137 chances to get one subtly wrong, and a wrong hitbox is the
 * kind of thing nobody reports and everybody feels.
 */
public final class HoldBlocks {

    public static final String NAMESPACE = "holdsmp";
    private static final String MANIFEST = "/holdsmp-blocks.json";

    /** Every block registered here, by its plain name. Used to resolve what the server sends. */
    private static final Map<String, Block> REGISTERED = new LinkedHashMap<>();

    /** Registration order, which is the order the server refers to them by index. */
    private static final List<String> ORDER = new ArrayList<>();

    private static boolean done;

    private HoldBlocks() {
    }

    /**
     * Registers everything in the manifest. Safe to call twice; the second call does nothing.
     *
     * <p>Has to happen during mod initialisation, because registries are frozen once the game
     * finishes starting and nothing can be added after that.
     */
    public static synchronized void register() {
        if (done) {
            return;
        }
        done = true;

        JsonObject root;
        try (InputStream in = HoldBlocks.class.getResourceAsStream(MANIFEST)) {
            if (in == null) {
                System.err.println("[Nan0UI] " + MANIFEST + " is missing from the jar. "
                        + "The 26.2 blocks will not be registered.");
                return;
            }
            root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ex) {
            System.err.println("[Nan0UI] Could not read " + MANIFEST + ": " + ex);
            return;
        }

        JsonArray blocks = root.getAsJsonArray("blocks");
        int failed = 0;
        for (var element : blocks) {
            JsonObject entry = element.getAsJsonObject();
            String id = entry.get("id").getAsString();
            try {
                Block block = build(entry);
                Registry.register(BuiltInRegistries.BLOCK,
                        ResourceLocation.fromNamespaceAndPath(NAMESPACE, id), block);
                REGISTERED.put(id, block);
                ORDER.add(id);
                String kind = entry.get("kind").getAsString();
                if (kind.equals("chest")) {
                    HoldRenderers.noteChest(block);
                } else if (kind.equals("statue")) {
                    HoldRenderers.noteStatue(block);
                }
            } catch (Throwable ex) {
                // One bad block must not cost the other hundred and thirty six. It will show
                // as whatever the server substituted, which is what happens without the mod.
                failed++;
                System.err.println("[Nan0UI] Could not register " + id + ": " + ex);
            }
        }
        try {
            HoldRenderers.registerTypes();
        } catch (Throwable ex) {
            System.err.println("[Nan0UI] Could not register block entity types: " + ex);
        }
        System.out.println("[Nan0UI] Registered " + REGISTERED.size() + " blocks from 26.2"
                + (failed > 0 ? ", " + failed + " failed" : ""));
    }

    /** The block for a name, or null when it was not registered. */
    public static Block get(String id) {
        return REGISTERED.get(id);
    }

    /** The block at an index in registration order, or null. */
    public static Block byIndex(int index) {
        return index >= 0 && index < ORDER.size() ? REGISTERED.get(ORDER.get(index)) : null;
    }

    public static int count() {
        return REGISTERED.size();
    }

    // ----- building -----

    private static Block build(JsonObject entry) {
        String kind = entry.get("kind").getAsString();
        Map<String, List<String>> properties = readProperties(entry);

        if (kind.equals("statue")) {
            return new StatueBlock(settingsFor(kind), properties);
        }
        // Decided before anything is built, and that ordering is the whole point.
        //
        // This used to construct the vanilla block, look at what state it turned out to have,
        // and drop it if that was not enough. Constructing a Block registers an intrusive
        // holder with the block registry, so the dropped one stayed behind unclaimed and the
        // registry refused to freeze at the end of startup: the game got as far as loading
        // every one of these and then died with "some intrusive holders were not registered".
        // A block that is never built cannot be left behind.
        if (fits(kind, properties.keySet())) {
            Block matched = fromFamily(kind, settingsFor(kind));
            if (matched != null) {
                return matched;
            }
        }
        return new GenericBlock(settingsFor(kind), properties);
    }

    /**
     * The state each vanilla family carries, read off the real classes rather than recalled.
     *
     * <p>Matching by name is right nearly every time, and when it is not the mismatch is
     * quiet: a moss carpet is not a carpet, it climbs walls, and plain carpet has no state at
     * all, so two thirds of it would never have rendered.
     */
    private static final Map<String, java.util.Set<String>> FAMILY_STATE = new HashMap<>();

    static {
        FAMILY_STATE.put("stairs", java.util.Set.of("facing", "half", "shape", "waterlogged"));
        FAMILY_STATE.put("slab", java.util.Set.of("type", "waterlogged"));
        FAMILY_STATE.put("wall",
                java.util.Set.of("east", "north", "south", "up", "waterlogged", "west"));
        FAMILY_STATE.put("fence",
                java.util.Set.of("east", "north", "south", "waterlogged", "west"));
        FAMILY_STATE.put("fence_gate",
                java.util.Set.of("facing", "in_wall", "open", "powered"));
        FAMILY_STATE.put("door",
                java.util.Set.of("facing", "half", "hinge", "open", "powered"));
        FAMILY_STATE.put("trapdoor",
                java.util.Set.of("facing", "half", "open", "powered", "waterlogged"));
        FAMILY_STATE.put("button", java.util.Set.of("face", "facing", "powered"));
        FAMILY_STATE.put("pressure_plate", java.util.Set.of("powered"));
        FAMILY_STATE.put("bars",
                java.util.Set.of("east", "north", "south", "waterlogged", "west"));
        FAMILY_STATE.put("chain", java.util.Set.of("axis", "waterlogged"));
        FAMILY_STATE.put("lantern", java.util.Set.of("hanging", "waterlogged"));
        FAMILY_STATE.put("carpet", java.util.Set.of());
        FAMILY_STATE.put("leaves", java.util.Set.of("distance", "persistent", "waterlogged"));
        FAMILY_STATE.put("pillar", java.util.Set.of("axis"));
        // Chests are the one family taken on trust, because a chest is drawn by a renderer
        // and its blockstate file lists no variants at all - there is nothing to compare.
        FAMILY_STATE.put("chest", java.util.Set.of("facing", "type", "waterlogged"));
    }

    /** Whether the vanilla class for a family carries every property the models need. */
    static boolean fits(String kind, java.util.Set<String> wanted) {
        java.util.Set<String> has = FAMILY_STATE.get(kind);
        return has != null && has.containsAll(wanted);
    }

    private static Block fromFamily(String kind, BlockBehaviour.Properties settings) {
        return switch (kind) {
            case "stairs" -> new OpenStairBlock(Blocks.OAK_PLANKS.defaultBlockState(), settings);
            case "slab" -> new SlabBlock(settings);
            case "wall" -> new WallBlock(settings);
            case "fence" -> new FenceBlock(settings);
            case "fence_gate" -> new FenceGateBlock(WoodType.OAK, settings);
            case "door" -> new DoorBlock(BlockSetType.OAK, settings);
            case "trapdoor" -> new net.minecraft.world.level.block.TrapDoorBlock(
                    BlockSetType.OAK, settings);
            case "button" -> new ButtonBlock(BlockSetType.OAK, 30, settings);
            case "pressure_plate" -> new PressurePlateBlock(BlockSetType.OAK, settings);
            case "bars" -> new OpenBarsBlock(settings);
            case "chain" -> new ChainBlock(settings);
            case "lantern" -> new LanternBlock(settings);
            case "carpet" -> new CarpetBlock(settings);
            case "leaves" -> new LeavesBlock(settings);
            case "pillar" -> new RotatedPillarBlock(settings);
            // The real chest class, so facing, double-chest pairing and waterlogging all
            // behave. It needs a block entity type, which is created alongside in HoldChests.
            case "chest" -> new net.minecraft.world.level.block.ChestBlock(
                    settings, () -> HoldRenderers.chestType());
            // No vanilla equivalent in 1.21, so the caller builds it from the manifest.
            default -> null;
        };
    }

    /**
     * How a block sounds, how long it takes to break, whether you can walk through it.
     *
     * <p>Copied off the nearest vanilla block rather than invented. These are guesses only in
     * the sense that the exact 26.2 values are not readable from here; every one is taken from
     * a block of the same material so nothing feels obviously wrong.
     */
    private static BlockBehaviour.Properties settingsFor(String kind) {
        return switch (kind) {
            case "leaves", "sapling", "carpet" ->
                    BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LEAVES).noOcclusion();
            case "bars", "chain", "lantern", "chest", "statue", "torch", "wall_torch" ->
                    BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BARS).noOcclusion();
            case "sign", "wall_sign", "hanging_sign", "wall_hanging_sign", "shelf" ->
                    BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS).noOcclusion();
            case "stairs", "slab", "wall", "fence", "fence_gate", "door", "trapdoor",
                 "button", "pressure_plate", "pillar", "cube" ->
                    BlockBehaviour.Properties.ofFullCopy(Blocks.STONE);
            default -> BlockBehaviour.Properties.ofFullCopy(Blocks.STONE).noOcclusion();
        };
    }

    private static Map<String, List<String>> readProperties(JsonObject entry) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!entry.has("properties")) {
            return out;
        }
        JsonObject props = entry.getAsJsonObject("properties");
        for (String name : props.keySet()) {
            List<String> values = new ArrayList<>();
            for (var v : props.getAsJsonArray(name)) {
                values.add(v.getAsString());
            }
            out.put(name, values);
        }
        return out;
    }

    // ----- the two vanilla classes whose constructors are not public -----

    private static final class OpenStairBlock extends StairBlock {
        OpenStairBlock(BlockState base, BlockBehaviour.Properties settings) {
            super(base, settings);
        }
    }

    private static final class OpenBarsBlock extends IronBarsBlock {
        OpenBarsBlock(BlockBehaviour.Properties settings) {
            super(settings);
        }
    }

    /**
     * A statue, which is a generic block that also carries a block entity.
     *
     * <p>The block entity holds nothing. Its only job is to make the game call a renderer for
     * this position, because the pose and the direction are block state and the model system
     * has no way to draw them.
     */
    private static final class StatueBlock extends GenericBlock
            implements net.minecraft.world.level.block.EntityBlock {

        StatueBlock(BlockBehaviour.Properties settings, Map<String, List<String>> declared) {
            super(settings, declared);
        }

        @Override
        public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(
                net.minecraft.core.BlockPos pos, BlockState state) {
            return new HoldRenderers.StatueBlockEntity(pos, state);
        }

        @Override
        protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
            // Nothing for the model system to draw; the renderer does all of it.
            return net.minecraft.world.level.block.RenderShape.INVISIBLE;
        }
    }

    /**
     * A block whose state is whatever the manifest said it was.
     *
     * <p>For the shapes 1.21 has no class for - a shelf, a creaking heart, leaf litter. The
     * state has to match what the blockstate file expects or the model loader has nothing to
     * pick from and the block renders as the missing texture, so the properties are rebuilt
     * from the same file the models came from rather than guessed.
     */
    private static class GenericBlock extends Block {

        private final Map<String, List<String>> declared;

        GenericBlock(BlockBehaviour.Properties settings, Map<String, List<String>> declared) {
            // The field is read from createBlockStateDefinition, which the superclass
            // constructor calls before this constructor body runs. Hence the handover.
            super(pass(settings, declared));
            this.declared = declared;
            registerDefaultState(defaults(stateDefinition.any(), declared));
        }

        private static final ThreadLocal<Map<String, List<String>>> PENDING = new ThreadLocal<>();

        private static BlockBehaviour.Properties pass(BlockBehaviour.Properties settings,
                                                      Map<String, List<String>> declared) {
            PENDING.set(declared);
            return settings;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            Map<String, List<String>> from = declared != null ? declared : PENDING.get();
            if (from == null) {
                return;
            }
            for (var e : from.entrySet()) {
                Property<?> property = propertyFor(e.getKey(), e.getValue());
                if (property != null) {
                    b.add(property);
                }
            }
            PENDING.remove();
        }

        private static BlockState defaults(BlockState state, Map<String, List<String>> ignored) {
            return state;
        }
    }

    /**
     * The property object for a name.
     *
     * <p>Vanilla ones are reused by name, because the blockstate file says {@code facing} and
     * means the same facing vanilla means, and reusing it keeps every value spelled the way
     * the file spells it. Names 1.21 has never heard of - {@code side_chain} on a shelf,
     * {@code hydration} on a dried ghast - are built from the values the file actually uses.
     */
    /**
     * Vanilla properties worth trying for a name, best first.
     *
     * <p>A list rather than one entry, because the same name means different things on
     * different blocks. A shelf faces one of four directions and a lightning rod faces one of
     * six, and both call it {@code facing}; picking the four-way one for the rod loses two
     * thirds of its models. The first candidate whose values cover what the file uses wins,
     * so the choice is made by the data rather than by a guess.
     */
    private static final Map<String, List<Property<?>>> VANILLA = new HashMap<>();

    static {
        VANILLA.put("facing", List.of(BlockStateProperties.HORIZONTAL_FACING,
                BlockStateProperties.FACING));
        VANILLA.put("axis", List.of(BlockStateProperties.AXIS));
        VANILLA.put("powered", List.of(BlockStateProperties.POWERED));
        VANILLA.put("waterlogged", List.of(BlockStateProperties.WATERLOGGED));
        VANILLA.put("hanging", List.of(BlockStateProperties.HANGING));
        VANILLA.put("open", List.of(BlockStateProperties.OPEN));
        VANILLA.put("attached", List.of(BlockStateProperties.ATTACHED));
        VANILLA.put("rotation", List.of(BlockStateProperties.ROTATION_16));
        VANILLA.put("bottom", List.of(BlockStateProperties.BOTTOM));
        // Boolean first because most blocks that use these are simple connections; the wall
        // form only wins where the file actually says low or tall.
        VANILLA.put("north", List.of(BlockStateProperties.NORTH, BlockStateProperties.NORTH_WALL));
        VANILLA.put("south", List.of(BlockStateProperties.SOUTH, BlockStateProperties.SOUTH_WALL));
        VANILLA.put("east", List.of(BlockStateProperties.EAST, BlockStateProperties.EAST_WALL));
        VANILLA.put("west", List.of(BlockStateProperties.WEST, BlockStateProperties.WEST_WALL));
        VANILLA.put("up", List.of(BlockStateProperties.UP));
        VANILLA.put("down", List.of(BlockStateProperties.DOWN));
        VANILLA.put("thickness", List.of(BlockStateProperties.DRIPSTONE_THICKNESS));
        VANILLA.put("vertical_direction", List.of(BlockStateProperties.VERTICAL_DIRECTION));
    }

    /** How a shelf meets the shelf beside it. New in 26.x, so it is declared here. */
    public enum SideChain implements net.minecraft.util.StringRepresentable {
        UNCONNECTED, LEFT, CENTER, RIGHT;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Whether a creaking heart is asleep, awake, or pulled out of the ground. */
    public enum CreakingHeartState implements net.minecraft.util.StringRepresentable {
        UPROOTED, DORMANT, AWAKE;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How a copper golem statue is posed. Block state in 26.2, so it is state here too. */
    public enum StatuePose implements net.minecraft.util.StringRepresentable {
        STANDING, SITTING, RUNNING, STAR;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The test block, which is a development tool and included only for completeness. */
    public enum TestBlockMode implements net.minecraft.util.StringRepresentable {
        START, LOG, FAIL, ACCEPT;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Map<String, Property<?>> DECLARED = Map.of(
            "side_chain", EnumProperty.create("side_chain", SideChain.class),
            "creaking_heart_state",
                    EnumProperty.create("creaking_heart_state", CreakingHeartState.class),
            "mode", EnumProperty.create("mode", TestBlockMode.class),
            "pose", EnumProperty.create("pose", StatuePose.class),
            "type", net.minecraft.world.level.block.state.properties.BlockStateProperties
                    .CHEST_TYPE);

    static Property<?> propertyFor(String name, List<String> values) {
        for (Property<?> candidate : VANILLA.getOrDefault(name, List.of())) {
            if (covers(candidate, values)) {
                return candidate;
            }
        }
        Property<?> declared = DECLARED.get(name);
        if (declared != null && covers(declared, values)) {
            return declared;
        }
        if (values.size() == 2 && values.contains("false") && values.contains("true")) {
            return BooleanProperty.create(name);
        }
        if (asInt(values.get(0)) != null) {
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (String value : values) {
                Integer n = asInt(value);
                if (n == null) {
                    return null;
                }
                min = Math.min(min, n);
                max = Math.max(max, n);
            }
            return IntegerProperty.create(name, min, max);
        }
        // A set of words nothing here knows. Java cannot make an enum at runtime, so this is
        // where a genuinely new kind of state stops being reproducible, and it is reported
        // rather than passed over.
        System.err.println("[Nan0UI] No property for " + name + " " + values
                + "; models using it will not resolve.");
        return null;
    }

    /** Whether a property can express every value the blockstate file uses. */
    private static boolean covers(Property<?> property, List<String> values) {
        List<String> known = new ArrayList<>();
        for (Comparable<?> value : property.getPossibleValues()) {
            known.add(value instanceof net.minecraft.util.StringRepresentable s
                    ? s.getSerializedName()
                    : value.toString().toLowerCase(Locale.ROOT));
        }
        return known.containsAll(values);
    }

    private static Integer asInt(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
