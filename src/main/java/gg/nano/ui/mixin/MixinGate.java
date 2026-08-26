package gg.nano.ui.mixin;

import gg.nano.ui.Bootstrap;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Decides which mixins apply, so the 26.x ones stay out of the way on 1.21.
 *
 * <p>Random Coords hooks the debug screen, and that screen was rebuilt in 26.1. On 1.21 the
 * class it targets is not there. Mixin treats a missing target as a fatal error rather than
 * something to skip, so leaving the mixin listed in the config would stop the game from
 * starting for every 1.21 player.
 *
 * <p>The mixin is therefore not listed in the config at all. It is added here, at runtime,
 * only when the version is one that has something to hook. A mixin that is never named is
 * never loaded, never parsed and never given the chance to fail.
 */
public final class MixinGate implements IMixinConfigPlugin {

    /**
     * Added only on 26.x.
     *
     * <p>Named as a plain string rather than a class reference on purpose. Writing
     * {@code DebugEntryPositionMixin.class} here would load the mixin class to read its name,
     * on every version, which is the exact thing being avoided.
     */
    private static final String DEBUG_POSITION = "DebugEntryPositionMixin";

    @Override
    public List<String> getMixins() {
        return Bootstrap.legacy() ? List.of() : List.of(DEBUG_POSITION);
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Belt and braces. getMixins already keeps it out on 1.21, and this makes sure that
        // stays true if the mixin is ever added back to the config by hand.
        return !Bootstrap.legacy();
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }
}
