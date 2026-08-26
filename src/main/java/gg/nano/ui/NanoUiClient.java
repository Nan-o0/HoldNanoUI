package gg.nano.ui;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint for NanoUI.
 *
 * <p>This mod exists because Java Edition gives a server no way to open a screen with real
 * buttons - only containers. With the mod installed the server can ask the client to draw a
 * native screen instead; without it, the server falls back to its chest menus, so the mod
 * stays strictly optional.
 */
public final class NanoUiClient implements ClientModInitializer {

    public static final String MOD_ID = "nanoui";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** True once a NanoCore server has sent us anything, so the button stays hidden elsewhere. */
    private static volatile boolean connected;

    @Override
    public void onInitializeClient() {
        // 26.2 names these clientboundPlay/serverboundPlay, not playS2C/playC2S.
        PayloadTypeRegistry.clientboundPlay().register(UiPayload.TYPE, UiPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(UiPayload.TYPE, UiPayload.CODEC);

        // Announce ourselves, and say which build. The server could previously tell whether a
        // player had the mod but not which version, so it could never say "yours is out of
        // date" - only "you have it" or "you do not". The version is read from fabric.mod.json
        // rather than written here, so it cannot drift from the actual build.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                ClientPlayNetworking.send(new UiPayload("HELLO|" + modVersion())));

        // Forget what belongs to the server the moment we leave it.
        //
        // Random Coords is deliberately NOT reset here. It is a privacy feature - it exists so
        // a streamer does not leak their base on camera - and a privacy feature has to fail
        // safe. Clearing it on disconnect would switch someone's coordinates back on mid
        // stream, at exactly the moment they stopped paying attention to it. It stays until
        // the player turns it off themselves.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            connected = false;
            PriceBook.clear();
        });

        PriceBook.register();

        ClientPlayNetworking.registerGlobalReceiver(UiPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    connected = true;
                    String data = payload.data();

                    if (data.startsWith("PRICES|")) {
                        PriceBook.load(data);
                        return;
                    }
                    if (data.startsWith("COORDS|")) {
                        // The server owns the setting; the client owns the screen it
                        // affects, so the state has to be pushed across.
                        FakePosition.setEnabled(data.substring(7).trim().equals("1"));
                        return;
                    }
                    if (data.startsWith("CAPTURE")) {
                        // Only ever sent by /nanocore audit ui. Started unconditionally: a
                        // run left half-finished by a disconnect would otherwise leave the
                        // state machine mid-sequence and silently swallow every later
                        // request, which looks exactly like the feature not working.
                        LOGGER.info("Capture requested by the server.");
                        UiTest.start();
                        return;
                    }
                    if (NanoScreen.open(data) == null) {
                        // Anything that is not a screen, a price book or a capture request
                        // is a bug on one side or the other. Say so rather than doing
                        // nothing and leaving no trace.
                        LOGGER.warn("Ignored a payload that described no screen ({} chars): {}",
                                data.length(),
                                data.length() > 40 ? data.substring(0, 40) + "..." : data);
                    }
                }));

        // Client-side layout harness. Renders every screen with sample data and writes a
        // screenshot each, so layout can be inspected rather than guessed at.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(UiTest::tick);

        // A way into the server menu from the pause screen, so it is reachable without
        // remembering a command. Only appears once the server has said hello.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register(
                (client, screen, width, height) -> {
                    if (!(screen instanceof net.minecraft.client.gui.screens.PauseScreen)
                            || !connected) {
                        return;
                    }
                    // Renamed from getButtons in the 26.2 screen API.
                    var widgets = net.fabricmc.fabric.api.client.screen.v1.Screens
                            .getWidgets(screen);

                    var menuButton = net.minecraft.client.gui.components.Button.builder(
                                    net.minecraft.network.chat.Component.literal("Hold SMP Menu"),
                                    button -> {
                                        Compat.setScreen(null);
                                        if (client.player != null) {
                                            client.player.connection.sendCommand("menu");
                                        }
                                    });

                    // Take over the Statistics slot rather than adding a row. Matched by its
                    // translated label so it still works in other languages.
                    String statsLabel = net.minecraft.network.chat.Component
                            .translatable("gui.stats").getString();

                    for (int i = 0; i < widgets.size(); i++) {
                        var widget = widgets.get(i);
                        if (!widget.getMessage().getString().equals(statsLabel)) {
                            continue;
                        }
                        widgets.set(i, menuButton
                                .bounds(widget.getX(), widget.getY(),
                                        widget.getWidth(), widget.getHeight())
                                .build());
                        return;
                    }

                    // Statistics was not found (a mod may have removed it) - fall back to
                    // appending rather than silently doing nothing.
                    widgets.add(menuButton
                            .bounds(width / 2 - 102, height / 4 + 128, 204, 20)
                            .build());
                });

        LOGGER.info("NanoUI ready (built {}).", BUILT);
    }

    /**
     * When this jar was compiled.
     *
     * <p>Printed on startup and written into the layout report. Mods are loaded when the game
     * starts, so a jar copied in while the game is running is not the one in use - and that
     * looks identical to a fix that did not work.
     */
    public static final String BUILT = readBuildStamp();

    private static String readBuildStamp() {
        try (var stream = NanoUiClient.class.getResourceAsStream("/nanoui-build.properties")) {
            if (stream == null) {
                return "unknown";
            }
            var properties = new java.util.Properties();
            properties.load(stream);
            return properties.getProperty("built", "unknown");
        } catch (java.io.IOException ex) {
            return "unknown";
        }
    }

    public static void send(String message) {
        // Every button on every screen funnels through here, which makes it the one place a
        // client side action can be caught without touching the ten call sites that raise
        // them. Anything the server has never heard of is prefixed local: and handled here;
        // everything else goes out as it always did.
        if (message.startsWith("ACTION|local:")
                && UpdateFlow.handleLocal(message.substring("ACTION|".length()))) {
            return;
        }
        ClientPlayNetworking.send(new UiPayload(message));
    }
    
    /** This build's version, straight from the mod metadata so the two can never disagree. */
    private static String modVersion() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("nanoui")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
