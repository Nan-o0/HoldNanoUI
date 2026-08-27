package gg.nano.ui;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The one entry point, for every Minecraft version this mod supports.
 *
 * <p>There are two complete copies of the mod in this jar and this decides which one runs.
 * That sounds wasteful until you look at why: Minecraft ships unobfuscated from 26.1, and
 * everything before that is obfuscated. A mod built for 1.21 refers to {@code class_310}
 * where a mod built for 26.2 refers to {@code Minecraft}, and those are not two spellings of
 * one name, they are two different names that exist in two different places. No single
 * compiled class can carry both.
 *
 * <p>So the code is compiled twice, into {@code gg.nano.ui} for 26.x and
 * {@code gg.nano.ui.legacy} for 1.21, and only one of them is ever loaded. The other sits in
 * the jar untouched. The JVM loads classes when they are first used and not before, so the
 * names that do not exist on this version are never looked up, and a name that is never
 * looked up cannot fail.
 *
 * <p>That laziness is what this class exists to protect. Nothing here may mention a Minecraft
 * type - not in a field, a parameter, a return type or a cast - because any of those would be
 * resolved when this class loads, which happens on every version. The implementation is
 * reached by name through {@link Class#forName} and handled only as the Fabric interface it
 * implements, and Fabric's own classes are the same everywhere.
 */
public final class Bootstrap implements ClientModInitializer {

    private static final String MODERN = "gg.nano.ui.NanoUiClient";
    private static final String LEGACY = "gg.nano.ui.legacy.NanoUiClient";

    /** Registers the blocks 26.2 has and older versions do not. 1.21 only. */
    private static final String BLOCKS = "gg.nano.ui.legacy.HoldBlocks";

    @Override
    public void onInitializeClient() {
        // Before anything else, and only on the old versions. Registries are frozen once the
        // game has finished starting, so a block not added by now can never be added, and the
        // 26.2 content would fall back to whatever the server substituted.
        if (legacy()) {
            try {
                Class.forName(BLOCKS).getMethod("register").invoke(null);
            } catch (Throwable ex) {
                // Not fatal. Without this the new blocks look wrong, which is how it was
                // before any of this existed; the menus are the reason people install the mod
                // and they should still work.
                System.err.println("[Nan0UI] Could not register the 26.2 blocks: " + ex);
            }
        }

        String target = legacy() ? LEGACY : MODERN;
        try {
            Object impl = Class.forName(target).getDeclaredConstructor().newInstance();
            ((ClientModInitializer) impl).onInitializeClient();
        } catch (Throwable ex) {
            // Loud, because there is no partial success here. If this fails the player has a
            // mod that loaded and does nothing, and the only symptom is chest menus where
            // they expected screens - which looks exactly like not having installed it.
            System.err.println("[Nan0UI] Could not start " + target + " on Minecraft "
                    + minecraftVersion() + ". The mod is installed but will do nothing.");
            ex.printStackTrace();
        }
    }

    /**
     * Whether this is one of the obfuscated versions, meaning 1.21.
     *
     * <p>Read from the loader rather than from Minecraft, because asking Minecraft would mean
     * touching a Minecraft class, which is the one thing this class must not do.
     *
     * <p>The test is the leading {@code 1.}, which is exactly the split that matters: Mojang
     * went from 1.21 to 26.1, and unobfuscated builds start at 26.1. So every version that
     * begins {@code 1.} is obfuscated and every version that does not is not. It also stays
     * correct on versions that did not exist when this was written, which a list of version
     * numbers would not.
     */
    public static boolean legacy() {
        return minecraftVersion().startsWith("1.");
    }

    private static String minecraftVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
    }
}
