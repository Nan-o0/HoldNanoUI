package gg.nano.ui.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Puts the real 26.2 blocks into the world, where the server says they are.
 *
 * <p>The server cannot send a 1.21 client a block that does not exist in 1.21. What it can do
 * is send a lookalike and, separately, say what is really there. {@link HoldBlocks} registered
 * the real ones; this reads the message and swaps them in.
 *
 * <p>The message is one per chunk: a palette of distinct states, then a position per block
 * pointing at one. A chunk of shelves is hundreds of blocks and a handful of states between
 * them, so the palette is most of the saving.
 */
public final class HoldWire {

    /** Message this handles. Everything else on the channel belongs to the menus. */
    public static final String PREFIX = "LB";

    private HoldWire() {
    }

    /**
     * Handles one message. Returns true when it was ours.
     *
     * <p>Called on the network thread, so the actual world change is handed to the main
     * thread. Touching the world from anywhere else is how a client ends up with half a chunk
     * rendered and a crash a second later.
     */
    public static boolean handle(String payload) {
        if (payload == null || !payload.startsWith(PREFIX + "|")) {
            return false;
        }
        // Limit of -1 so trailing empty fields survive: a break message has an empty palette
        // and dropping it would shift the positions into the palette slot.
        String[] parts = payload.split("\\|", -1);
        if (parts.length < 5) {
            return true;
        }

        final int chunkX;
        final int chunkZ;
        try {
            chunkX = Integer.parseInt(parts[1]);
            chunkZ = Integer.parseInt(parts[2]);
        } catch (NumberFormatException ex) {
            return true;
        }

        String[] palette = parts[3].isEmpty() ? new String[0] : parts[3].split(";");
        String[] entries = parts[4].isEmpty() ? new String[0] : parts[4].split(";");
        if (entries.length == 0) {
            return true;
        }

        List<BlockState> states = new ArrayList<>(palette.length);
        for (String one : palette) {
            BlockState parsed = parse(one);
            if (parsed == null && !one.isEmpty()) {
                unknown++;
            }
            states.add(parsed);
        }
        messages++;
        // The first one confirms the whole path works, and then rarely enough to be ignorable.
        if (messages == 1 || messages % 200 == 0) {
            report();
        }

        Minecraft client = Minecraft.getInstance();
        client.execute(() -> apply(client, chunkX, chunkZ, states, entries));
        return true;
    }

    /** A block whose chunk had not arrived yet. */
    private record Pending(BlockPos pos, BlockState state) {
    }

    private static final List<Pending> pending =
            java.util.Collections.synchronizedList(new ArrayList<>());

    /**
     * Retries the blocks whose chunk was not loaded when their message arrived.
     *
     * <p>Called every client tick. Anything still not loaded after a while is dropped rather
     * than retried forever: a chunk that has not arrived in ten seconds is one the player has
     * walked away from, and the message will come again if they walk back.
     */
    public static void tick() {
        if (pending.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        var level = client.level;
        if (level == null) {
            pending.clear();
            return;
        }
        synchronized (pending) {
            java.util.Iterator<Pending> it = pending.iterator();
            List<Pending> done = new ArrayList<>();
            while (it.hasNext()) {
                Pending one = it.next();
                if (level.isLoaded(one.pos())) {
                    place(level, one.pos(), one.state());
                    done.add(one);
                }
            }
            pending.removeAll(done);
            if (pending.size() > 40000) {
                // Something is wrong and this must not grow without limit.
                pending.clear();
            }
        }
    }

    /**
     * Sets one block on the client only.
     *
     * <p>The same flags vanilla uses when it applies a block update off the network. Zero
     * would change the block without telling anything to redraw, so the old one would stay on
     * screen until something else happened to dirty the section.
     */
    private static void place(net.minecraft.world.level.Level level, BlockPos pos,
                              BlockState state) {
        level.setBlock(pos, state,
                Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE);
    }

    /**
     * Counters, so a player can be asked what their log says instead of being asked to guess.
     *
     * <p>Every failure so far has looked identical from the outside - nothing changes - and
     * has had a different cause each time. Three numbers separate them: no messages at all
     * means the server is not sending, messages with nothing applied means the blocks are not
     * arriving where they are expected, and unknown names mean the two sides disagree about
     * what exists.
     */
    private static int messages;
    private static int applied;
    private static int unknown;
    private static int held;

    private static void report() {
        System.out.println("[Nan0UI] blocks: " + messages + " messages, " + applied
                + " placed, " + held + " waiting for their chunk, " + unknown
                + " names this build does not have.");
    }

    private static void apply(Minecraft client, int chunkX, int chunkZ,
                              List<BlockState> states, String[] entries) {
        var level = client.level;
        if (level == null) {
            // Left the world between the message arriving and this running. Nothing to do,
            // and the chunk will be sent again next time they load it.
            return;
        }

        for (String entry : entries) {
            String[] field = entry.split(",");
            if (field.length < 4) {
                continue;
            }
            try {
                int x = (chunkX << 4) + Integer.parseInt(field[0]);
                int y = Integer.parseInt(field[1]);
                int z = (chunkZ << 4) + Integer.parseInt(field[2]);
                int index = Integer.parseInt(field[3]);

                // -1 means the block is gone and the override with it. The server has already
                // told the client what is really there now, so this just stops overriding.
                if (index < 0) {
                    continue;
                }
                if (index >= states.size()) {
                    continue;
                }
                BlockState state = states.get(index);
                if (state == null) {
                    continue;
                }
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.isLoaded(pos)) {
                    // The message can beat the chunk it describes: the server sends both and
                    // the client processes them in its own time. Dropping the block here
                    // would leave it looking wrong until the chunk was loaded again, which
                    // for somebody standing still is never. Held and retried instead.
                    pending.add(new Pending(pos, state));
                    held++;
                    continue;
                }
                place(level, pos, state);
                applied++;
            } catch (NumberFormatException ignored) {
                // One malformed entry costs that block and nothing else.
            }
        }
    }

    /**
     * Turns {@code minecraft:oak_shelf[facing=north,powered=false]} into a real state.
     *
     * <p>The name is looked up among the blocks registered here rather than in the whole
     * registry: the server names them with the {@code minecraft} namespace because that is
     * what they are on its side, and on this side they are ours.
     */
    static BlockState parse(String description) {
        if (description == null || description.isEmpty()) {
            return null;
        }
        int bracket = description.indexOf('[');
        String name = (bracket < 0 ? description : description.substring(0, bracket)).trim();
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }

        Block block = HoldBlocks.get(name);
        if (block == null) {
            return null;
        }
        BlockState state = block.defaultBlockState();
        if (bracket < 0 || !description.endsWith("]")) {
            return state;
        }

        String inside = description.substring(bracket + 1, description.length() - 1);
        for (String pair : inside.split(",")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            state = with(state, block.getStateDefinition(),
                    pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
        }
        return state;
    }

    /** Sets one property by name, ignoring any this block does not have. */
    private static <T extends Comparable<T>> BlockState with(BlockState state,
                                                             StateDefinition<Block, BlockState> definition,
                                                             String name, String value) {
        Property<?> property = definition.getProperty(name);
        if (property == null) {
            // The server knows a property this build does not. Better a block in its default
            // state than no block, and better than guessing what the value meant.
            return state;
        }
        return set(state, property, value);
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state,
                                                            Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value.toLowerCase(Locale.ROOT));
        return parsed.map(t -> state.setValue(property, t)).orElse(state);
    }
}
