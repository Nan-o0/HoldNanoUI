package gg.nano.ui.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Shows a screen, on the versions where that is simply a method call.
 *
 * <p>The 26.x copy of this looks the call up by name at startup, because it moved from
 * {@code setScreen} to {@code setScreenAndShow} between 26.1 and 26.2 and one jar has to work
 * on both. That approach cannot work here and fails quietly, which is worse than failing
 * loudly: 1.21 is obfuscated, so at runtime the method is not called {@code setScreen}, it is
 * called something like {@code a}. Asking for it by name found nothing, every menu decided
 * there was no way to open a screen, and the mod loaded perfectly and did nothing.
 *
 * <p>Nothing is looked up here. The call is written out, Loom remaps it with everything else,
 * and it lands on whatever the method is really called in the build being run.
 */
public final class Compat {

    private Compat() {
    }

    /** Shows a screen, or closes the current one when given null. */
    public static void setScreen(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }

    /** Always true on this side. The call is compiled in, so there is nothing to be missing. */
    public static boolean usable() {
        return true;
    }
}
