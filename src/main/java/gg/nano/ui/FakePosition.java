package gg.nano.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryPosition;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;

import java.util.List;
import java.util.Random;

/**
 * Replaces the F3 position readout with numbers that mean nothing.
 *
 * <p>Turned on by the Random Coords setting. The server cannot do this - a player's position
 * is the client's own, and moving it for real would move the player - so the swap happens
 * here, in the one place that decides what F3 prints.
 *
 * <p>Driven by a mixin on the position entry. Replacing the entry in the debug screen's map
 * was tried first and does not work: 26.2 exposes that map read-only, and the field behind
 * it is {@code static final}, which reflection cannot reassign.
 *
 * <p>The numbers are re-rolled whenever the player moves, which is what makes them useless
 * to anyone reading over your shoulder: two screenshots a second apart share nothing, so
 * there is no drift to follow back to a base.
 */
public final class FakePosition {

    /**
     * How far out the invented coordinates range.
     *
     * <p>Deliberately most of the way to the world border. Numbers in the low thousands sit
     * in the range a real base might occupy and invite a search; seven-figure ones are
     * obviously nowhere near you and give a reader nothing to work with.
     */
    private static final int RANGE = 25_000_000;

    private static final Random RANDOM = new Random();
    private static volatile boolean enabled;

    private static double lastX = Double.NaN;
    private static double lastY = Double.NaN;
    private static double lastZ = Double.NaN;
    private static double fakeX;
    private static double fakeY;
    private static double fakeZ;

    private FakePosition() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        NanoUiClient.LOGGER.info("Random Coords {}", on ? "on" : "off");
    }

    public static boolean enabled() {
        return enabled;
    }

    /**
     * Draws the invented position.
     *
     * @return true when it took over, so the real entry can be skipped
     */
    public static boolean render(DebugScreenDisplayer displayer) {
        if (!enabled) {
            return false;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }

        reroll(player.getX(), player.getY(), player.getZ());

        int blockX = (int) Math.floor(fakeX);
        int blockY = (int) Math.floor(fakeY);
        int blockZ = (int) Math.floor(fakeZ);

        // Same lines and formats as the real entry, so nothing looks out of place.
        displayer.addToGroup(DebugEntryPosition.GROUP, List.of(
                String.format("XYZ: %.3f / %.5f / %.3f", fakeX, fakeY, fakeZ),
                String.format("Block: %d %d %d", blockX, blockY, blockZ),
                String.format("Chunk: %d %d %d [%d %d in r.%d.%d.mca]",
                        blockX >> 4, blockY >> 4, blockZ >> 4,
                        Math.floorMod(blockX >> 4, 32), Math.floorMod(blockZ >> 4, 32),
                        blockX >> 9, blockZ >> 9),
                String.format("Facing: %s (%s) (%.1f / %.1f)",
                        player.getDirection().getName(),
                        facingText(player.getDirection()),
                        player.getYRot(), player.getXRot())));
        return true;
    }

    /**
     * New numbers on every real movement.
     *
     * <p>Standing still keeps them steady, so the screen is not flickering while nothing is
     * happening. The moment the player actually moves, the readout jumps somewhere else
     * entirely rather than tracking the step that was taken.
     */
    private static void reroll(double x, double y, double z) {
        boolean moved = Double.isNaN(lastX)
                || Math.abs(x - lastX) > 0.01
                || Math.abs(y - lastY) > 0.01
                || Math.abs(z - lastZ) > 0.01;
        if (!moved) {
            return;
        }
        lastX = x;
        lastY = y;
        lastZ = z;

        fakeX = RANDOM.nextInt(-RANGE, RANGE) + RANDOM.nextDouble();
        fakeZ = RANDOM.nextInt(-RANGE, RANGE) + RANDOM.nextDouble();
        // Y stays inside the real world height, so the line still reads as a place.
        fakeY = RANDOM.nextInt(-60, 320) + RANDOM.nextDouble();
    }

    private static String facingText(net.minecraft.core.Direction direction) {
        return switch (direction) {
            case NORTH -> "Towards negative Z";
            case SOUTH -> "Towards positive Z";
            case WEST -> "Towards negative X";
            case EAST -> "Towards positive X";
            default -> "Invalid";
        };
    }
}
