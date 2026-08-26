package gg.nano.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;

/**
 * The handful of calls whose name changed between 26.1 and 26.2.
 *
 * <p>Compiling against 26.2 bakes in method names that did not exist a version earlier, and a
 * missing method is not a compile error at that point, it is a crash on the player's machine
 * the first time the line runs. Resolving them by name at startup instead means one jar loads
 * on both, and the failure, if there is one, happens once at load where it can be logged
 * rather than halfway through opening a menu.
 *
 * <p>Only the changes that are actually known to have happened are handled. Guessing at others
 * would mean reflection everywhere and a codebase nobody can read, for versions nobody runs.
 */
public final class Compat {

    /**
     * Setting the current screen.
     *
     * <p>26.2 has {@code Minecraft.setScreenAndShow}. 26.1 had {@code Minecraft.setScreen},
     * which Fabric's own 26.2 notes call out as moved. Both are tried, newest first, and the
     * one that exists is kept.
     */
    private static final Method SET_SCREEN = resolveSetScreen();

    private Compat() {
    }

    private static Method resolveSetScreen() {
        for (String name : new String[]{"setScreenAndShow", "setScreen"}) {
            try {
                return Minecraft.class.getMethod(name, Screen.class);
            } catch (NoSuchMethodException ignored) {
                // Try the next one. A version that has neither is a version this mod cannot
                // run on at all, and that is reported below rather than guessed around.
            }
        }
        return null;
    }

    /** Shows a screen, or closes the current one when given null. */
    public static void setScreen(Screen screen) {
        Minecraft client = Minecraft.getInstance();
        if (SET_SCREEN == null) {
            // Nothing sensible left to do. Better to say so once than to throw on every menu.
            System.err.println("[Nan0UI] No way to set the screen on this Minecraft version. "
                    + "Menus will not open. Update the mod.");
            return;
        }
        try {
            SET_SCREEN.invoke(client, screen);
        } catch (ReflectiveOperationException ex) {
            System.err.println("[Nan0UI] Could not set the screen: " + ex.getCause());
        }
    }

    /** True when the screen call was found, for the version check on join. */
    public static boolean usable() {
        return SET_SCREEN != null;
    }
}
