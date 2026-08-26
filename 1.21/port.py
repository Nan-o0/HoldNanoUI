"""Regenerates the 1.21 source from the 26.2 source.

There is one copy of this mod, written against 26.2, and this rewrites the calls Mojang
renamed on the way back to 1.21. A script rather than a second copy of the code, so a fix
made once lands in both builds; a fork would drift within a week.

Run from this folder after changing anything in ../NanoUI/src.
"""
import io
import os
import re
import shutil

# Where the 26.2 source lives, which depends on how you got here. In the repository this
# generator sits in a folder beside that source; in the working tree the two are separate
# projects side by side. Both layouts are real and in daily use, so both are tried rather
# than one of them being declared the correct one.
def find_source():
    for candidate in ("../src/main", "../NanoUI/src/main"):
        if os.path.isdir(os.path.join(candidate, "java")):
            return candidate
    raise SystemExit("Cannot find the 26.2 source. Run this from the folder holding port.py.")


SRC = find_source()
DST = "src/main"

# The ported copy goes in its own package so both copies can sit in one jar. They cannot
# share one: this copy refers to Minecraft by its obfuscated-era names and the other by the
# real ones, and a single class cannot hold both. Same class names, different package, and
# only the package matching the running version is ever loaded.
LEGACY_PACKAGE = "gg.nano.ui.legacy"

NL = chr(10)

# No 1.21 equivalent at all. FakePosition and its mixin drive Random Coords, which hides your
# position on the debug screen, and that screen was rebuilt in 26.1 - the classes it hooks are
# simply not there. Dropped rather than faked. UiTest is the screenshot audit harness, which
# is a development tool players never touch and not worth porting.
SKIP = {
    "gg/nano/ui/FakePosition.java",
    "gg/nano/ui/mixin/DebugEntryPositionMixin.java",
    "gg/nano/ui/UiTest.java",
    # These two are version independent and there must be exactly one of each. Bootstrap
    # picks between the two copies of the mod, so a second copy of it inside the copy it
    # picks would be circular; MixinGate is asked which mixins apply before either copy is
    # loaded. Both mention no Minecraft type at all, which is what lets them be shared.
    "gg/nano/ui/Bootstrap.java",
    "gg/nano/ui/mixin/MixinGate.java",
}

RULES = [
    # Own package. Everything in this mod sits in one package, so same-package references
    # need no rewriting and moving the declaration is enough.
    (r"^package gg\.nano\.ui;", "package " + LEGACY_PACKAGE + ";"),
    (r"gg\.nano\.ui\.mixin", LEGACY_PACKAGE + ".mixin"),

    # ResourceLocation was renamed Identifier in 26.x.
    (r"net\.minecraft\.resources\.Identifier", "net.minecraft.resources.ResourceLocation"),
    (r"\bIdentifier\b", "ResourceLocation"),

    # GuiGraphics became GuiGraphicsExtractor when GUI drawing moved to the render state
    # model, and the entry point went from render to extractRenderState with it.
    (r"net\.minecraft\.client\.gui\.GuiGraphicsExtractor",
     "net.minecraft.client.gui.GuiGraphics"),
    (r"\bGuiGraphicsExtractor\b", "GuiGraphics"),
    (r"\bextractRenderState\(", "render("),
    (r"\bextractor\b", "graphics"),

    # Input moved from loose coordinates to event objects. Signatures first, then the bodies
    # that read fields off the event.
    (r"public boolean mouseClicked\(net\.minecraft\.client\.input\.MouseButtonEvent event,"
     r"\s*\n\s*boolean doubleClick\)",
     "public boolean mouseClicked(double mouseX, double mouseY, int button)"),
    (r"public boolean mouseDragged\(net\.minecraft\.client\.input\.MouseButtonEvent event,"
     r"\s*\n\s*double dragX, double dragY\)",
     "public boolean mouseDragged(double mouseX, double mouseY, int button,"
     + NL + "                                double dragX, double dragY)"),
    (r"public boolean mouseReleased\(net\.minecraft\.client\.input\.MouseButtonEvent event\)",
     "public boolean mouseReleased(double mouseX, double mouseY, int button)"),
    (r"public boolean keyPressed\(net\.minecraft\.client\.input\.KeyEvent event\)",
     "public boolean keyPressed(int keyCode, int scanCode, int modifiers)"),

    (r"super\.mouseClicked\(event, doubleClick\)",
     "super.mouseClicked(mouseX, mouseY, button)"),
    (r"super\.mouseDragged\(event[^)]*\)",
     "super.mouseDragged(mouseX, mouseY, button, dragX, dragY)"),
    (r"super\.mouseReleased\(event\)", "super.mouseReleased(mouseX, mouseY, button)"),
    (r"super\.keyPressed\(event\)", "super.keyPressed(keyCode, scanCode, modifiers)"),

    # Redundant once the same values arrive as parameters.
    (r"\s*double mouseX = event\.x\(\);", ""),
    (r"\s*double mouseY = event\.y\(\);", ""),
    (r"int key = event\.key\(\);", "int key = keyCode;"),
    (r"event\.x\(\)", "mouseX"),
    (r"event\.y\(\)", "mouseY"),
    (r"event\.key\(\)", "keyCode"),

    # Registry lookup was getValue in 26.x and plain get before it.
    (r"BuiltInRegistries\.ITEM\.getValue\(", "BuiltInRegistries.ITEM.get("),

    # ResolvableProfile gained a static factory in 26.x. 1.21 has the record constructor only.
    # Matched from the fully qualified name so the "new" can be put back with it. Rewriting
    # only the method call left a bare type name being invoked, which reads to the compiler
    # as a package that does not exist rather than as a missing keyword.
    (r"net\.minecraft\.world\.item\.component\.ResolvableProfile\s*\n?\s*"
     r"\.createUnresolved\(java\.util\.UUID\.fromString\(rawUuid\)\)",
     "new net.minecraft.world.item.component.ResolvableProfile(" + NL
     + "                            java.util.Optional.empty()," + NL
     + "                            java.util.Optional.of("
     + "java.util.UUID.fromString(rawUuid))," + NL
     + "                            new com.mojang.authlib.properties.PropertyMap())"),

    # Sprite drawing lost the render pipeline argument going backwards. blitSprite is the
    # 1.21 spelling and takes width and height rather than UV bounds.
    (r"graphics\.blit\(net\.minecraft\.client\.renderer\.RenderPipelines\.GUI_TEXTURED,"
     r"\s*\n\s*icon\.sprite\(\), icon\.x\(\), icon\.y\(\), 0f, 0f, size, size,"
     r"\s*\n\s*SPRITE_SOURCE, SPRITE_SOURCE\)",
     "graphics.blitSprite(icon.sprite(), icon.x(), icon.y(), size, size)"),

    # PoseStack used the matrix naming in 26.x, and both calls want a Z argument here.
    (r"pose\.pushMatrix\(\)", "pose.pushPose()"),
    (r"pose\.popMatrix\(\)", "pose.popPose()"),
    (r"pose\.translate\(icon\.x\(\), icon\.y\(\)\)",
     "pose.translate((float) icon.x(), (float) icon.y(), 0f)"),
    (r"pose\.scale\(icon\.scale\(\), icon\.scale\(\)\)",
     "pose.scale(icon.scale(), icon.scale(), 1f)"),
    (r"graphics\.item\(", "graphics.renderItem("),

    # NBT lost the or-empty accessors going backwards.
    (r"\.getCompoundOrEmpty\(", ".getCompound("),

    # Fabric API renamed both of these after 1.21.
    (r"PayloadTypeRegistry\.clientboundPlay\(\)", "PayloadTypeRegistry.playS2C()"),
    (r"PayloadTypeRegistry\.serverboundPlay\(\)", "PayloadTypeRegistry.playC2S()"),
    (r"Screens\s*\n?\s*\.getWidgets\(", "Screens.getButtons("),

    # Both of these belong to files that are not ported, so the lines calling them go too.
    (r"\s*FakePosition\.setEnabled\([^;]*\);",
     NL + "                        // Random Coords needs the 26.1 debug screen, so it is"
     + NL + "                        // not available on 1.21. Ignored rather than crashing."),
    # The whole statement, not just the line naming UiTest. Cutting only the register
    # call left the ClientTickEvents lookup dangling with no semicolon after it.
    (r"[^\n]*ClientTickEvents[^;]*UiTest::tick[)];\s*", ""),
    (r"[^\n]*UiTest\.[a-zA-Z]+\([^;]*\);", ""),
]


def main():
    shutil.rmtree(DST + "/java", ignore_errors=True)
    changed = []

    for root, _, files in os.walk(SRC + "/java"):
        for name in files:
            full = os.path.join(root, name).replace("\\", "/")
            rel = full[len(SRC + "/java/"):]
            if rel in SKIP:
                continue
            text = io.open(full, encoding="utf-8").read()
            before = text
            for pattern, replacement in RULES:
                text = re.sub(pattern, replacement, text, flags=re.MULTILINE)
            out = os.path.join(DST + "/java", rel)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            io.open(out, "w", encoding="utf-8", newline=NL).write(text)
            if text != before:
                changed.append(rel)

    print("ported " + str(len(changed)) + " file(s):")
    for rel in changed:
        print("  " + rel)
    print("skipped " + str(len(SKIP)) + ": " + ", ".join(sorted(SKIP)))


main()
