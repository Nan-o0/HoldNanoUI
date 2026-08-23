package gg.nano.ui.mixin;

import gg.nano.ui.FakePosition;
import net.minecraft.client.gui.components.debug.DebugEntryPosition;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes over the F3 position readout when Random Coords is on.
 *
 * <p>A mixin rather than a registry swap: 26.2 hands out its debug entry map read-only, and
 * the field behind it is {@code static final}, which Java will not let reflection reassign.
 * Injecting into the entry itself is the only way in, and it is the more durable one anyway -
 * it does not care how the entries are stored.
 *
 * <p>Cancels only when the spoof actually drew something. With the setting off the original
 * method runs untouched, so F3 is exactly as Mojang wrote it.
 */
@Mixin(DebugEntryPosition.class)
public class DebugEntryPositionMixin {

    @Inject(method = "display", at = @At("HEAD"), cancellable = true)
    private void nanoui$spoofPosition(DebugScreenDisplayer displayer, Level level,
                                      LevelChunk chunk, LevelChunk serverChunk,
                                      CallbackInfo callback) {
        if (FakePosition.render(displayer)) {
            callback.cancel();
        }
    }
}
