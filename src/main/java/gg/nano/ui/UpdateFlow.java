package gg.nano.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;

/**
 * Downloads and installs a new version of the mod, in game.
 *
 * <p>Driven entirely by the server, which sends {@code UPDATE|version|url|sha256|size|mode}.
 * The mod never decides on its own that an update exists and never contacts anything unless
 * told to, so there is exactly one place this can be triggered from and it is visible in the
 * server config.
 *
 * <p>Screens here are built as ordinary payloads and handed to {@link NanoScreen}, the same
 * way the server builds every other screen. Writing a new screen class would have meant new
 * rendering code, and rendering is the part that cannot be checked without running the game.
 * Reusing the renderer means the only untested code in this file is the downloading.
 */
public final class UpdateFlow {

    /**
     * What the file is always called on disk, whatever it is called on the release page.
     *
     * <p>This is the entire duplicate protection. Two jars of the same mod in one folder and
     * Fabric refuses to start the game, so an update that writes a new filename each version
     * breaks the client of anybody who does not clean up by hand. One fixed name means an
     * update overwrites the thing it replaces.
     */
    private static final String LOCAL_NAME = "Nan0UI.jar";

    private static volatile boolean running;
    private static volatile boolean installed;

    private static String version = "";
    private static String url = "";
    private static String expectedSha = "";
    private static long expectedSize;

    private UpdateFlow() {
    }

    /** Handles an UPDATE line from the server. */
    public static void offer(String[] p) {
        if (running || installed || p.length < 5) {
            return;
        }
        version = p[1];
        url = p[2];
        expectedSha = p[3].toLowerCase(Locale.ROOT);
        try {
            expectedSize = Long.parseLong(p[4]);
        } catch (NumberFormatException ex) {
            expectedSize = 0;
        }
        boolean auto = p.length > 5 && p[5].equalsIgnoreCase("auto");

        if (url.isBlank() || expectedSha.isBlank()) {
            // Refusing rather than fetching anyway. Without something to check the download
            // against there is no way to tell a good file from a broken or swapped one, and
            // installing whatever arrives is how an update becomes a way in.
            chat("<red>Update available but the server did not send a checksum, so it was "
                    + "not downloaded.");
            return;
        }

        if (auto) {
            start();
            return;
        }
        askFirst();
    }

    /** True when the action was ours and has been dealt with. */
    public static boolean handleLocal(String action) {
        switch (action) {
            case "local:update_go" -> {
                start();
                return true;
            }
            case "local:update_no" -> {
                Minecraft.getInstance().setScreenAndShow(null);
                return true;
            }
            case "local:update_close" -> {
                Minecraft.getInstance().setScreenAndShow(null);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static void askFirst() {
        NanoScreen.open("OPEN|update|Nan0UI " + version + "\n"
                + "INFO|A newer version of the mod is out\n"
                + "BTN|noop|Version " + version + "|" + kb(expectedSize)
                + "|The server is running this one. Yours is older.|green"
                + "|minecraft:paper\n"
                + "ACT|local:update_go|Download it now"
                + "|Saves it into your mods folder for you. You will need to restart"
                + " Minecraft afterwards.|green|minecraft:hopper\n"
                + "ACT|local:update_no|Not now"
                + "|Nothing changes. You will be asked again next time you join.|none"
                + "|minecraft:barrier\n");
    }

    private static void start() {
        if (running) {
            return;
        }
        running = true;
        showProgress(0, 0, 0);

        Thread worker = new Thread(UpdateFlow::download, "Nan0UI-update");
        worker.setDaemon(true);
        worker.start();
    }

    private static void download() {
        Path mods = Minecraft.getInstance().gameDirectory.toPath().resolve("mods");
        Path target = mods.resolve(LOCAL_NAME);
        Path running = runningJar();

        // When the running jar already has the name we want to write, it cannot be written
        // to while the game holds it open. The download goes beside it and the swap happens
        // at shutdown, when nothing has it open any more.
        boolean inPlace = running != null && running.equals(target);
        Path staging = inPlace ? mods.resolve(LOCAL_NAME + ".update") : target;

        try {
            Files.createDirectories(mods);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "Nan0UI")
                    .GET()
                    .build();

            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                fail("The download server answered " + response.statusCode() + ".");
                return;
            }

            Path partial = staging.resolveSibling(staging.getFileName() + ".part");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            long began = System.nanoTime();
            long lastDraw = 0;

            try (InputStream in = response.body();
                 var out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    total += read;

                    // Four times a second. The screen is rebuilt to draw this, so redrawing
                    // it every chunk would spend more time on the progress bar than on the
                    // download.
                    long now = System.nanoTime();
                    if (now - lastDraw > 250_000_000L) {
                        lastDraw = now;
                        long done = total;
                        double seconds = (now - began) / 1_000_000_000.0D;
                        onMain(() -> showProgress(done, expectedSize,
                                seconds > 0 ? done / seconds : 0));
                    }
                }
            }

            String got = hex(digest.digest());
            if (!got.equalsIgnoreCase(expectedSha)) {
                Files.deleteIfExists(partial);
                fail("The file that arrived is not the one the server described, so it was "
                        + "thrown away.");
                return;
            }
            if (expectedSize > 0 && total != expectedSize) {
                Files.deleteIfExists(partial);
                fail("The download stopped early and was thrown away.");
                return;
            }

            Files.move(partial, staging, StandardCopyOption.REPLACE_EXISTING);
            armSwap(running, staging, target, inPlace);

            installed = true;
            onMain(() -> showDone(target));
        } catch (Exception ex) {
            fail(ex.getMessage() == null ? ex.toString() : ex.getMessage());
        } finally {
            UpdateFlow.running = false;
        }
    }

    /**
     * Arranges for exactly one enabled copy to exist the next time the game starts.
     *
     * <p>The running jar cannot be deleted on Windows while the game has it open, so the
     * tidying happens in a shutdown hook. Both branches end the same way: if the swap cannot
     * be completed, the file that was just downloaded is removed. A failed update is a
     * nuisance; two enabled copies of the mod is a client that will not launch at all, and
     * that is not a trade worth making.
     */
    private static void armSwap(Path running, Path staging, Path target, boolean inPlace) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (inPlace) {
                    Files.deleteIfExists(target);
                    Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
                if (running != null && Files.exists(running)) {
                    // Renamed rather than deleted: Windows will rename a file it still has
                    // open far more often than it will delete one, and Fabric skips anything
                    // that is not exactly .jar.
                    Files.move(running,
                            running.resolveSibling(running.getFileName() + ".disabled"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception ex) {
                try {
                    Files.deleteIfExists(inPlace ? staging : target);
                } catch (Exception ignored) {
                    // Nothing further can be done from here, and throwing out of a shutdown
                    // hook would only bury the original problem.
                }
            }
        }, "Nan0UI-update-swap"));
    }

    /** Where the copy of this mod that is currently running lives, or null. */
    private static Path runningJar() {
        try {
            var container = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("nanoui");
            if (container.isEmpty()) {
                return null;
            }
            var paths = container.get().getOrigin().getPaths();
            return paths.isEmpty() ? null : paths.get(0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ----- Screens -----

    private static void showProgress(long done, long total, double bytesPerSecond) {
        int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        NanoScreen.open("OPEN|update|Downloading Nan0UI\n"
                + "INFO|Getting version " + version + "\n"
                + "BTN|noop|" + bar(percent) + "  " + percent + "%"
                + "|" + speed(bytesPerSecond)
                + "|" + kb(done) + " of " + kb(total) + "|green|minecraft:hopper\n"
                + "BTN|noop|Leave this open|Please wait"
                + "|It only takes a moment. The game is not frozen.|gray"
                + "|minecraft:clock\n");
    }

    private static void showDone(Path target) {
        NanoScreen.open("OPEN|update|Nan0UI updated\n"
                + "INFO|Version " + version + " is installed\n"
                + "BTN|noop|Restart Minecraft to finish|Almost done"
                + "|The new version starts working the next time you open the game.|green"
                + "|minecraft:lime_dye\n"
                + "BTN|noop|Saved to your mods folder|" + LOCAL_NAME
                + "|Nothing else to do. The old one is cleaned up on exit.|gray"
                + "|minecraft:paper\n"
                + "ACT|local:update_close|Close|Back to the game.|none|minecraft:barrier\n");
        chat("<green>Nan0UI " + version + " installed. Restart Minecraft to use it.");
    }

    private static void fail(String why) {
        onMain(() -> {
            Minecraft.getInstance().setScreenAndShow(null);
            chat("<red>The update could not be installed. " + why);
            chat("<gray>Nothing was changed. You can keep playing as you are.");
        });
    }

    // ----- Small helpers -----

    private static String bar(int percent) {
        int filled = percent / 5;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            out.append(i < filled ? '■' : '□');
        }
        return out.toString();
    }

    private static String kb(long bytes) {
        if (bytes <= 0) {
            return "?";
        }
        return bytes >= 1024 * 1024
                ? String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
                : String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }

    private static String speed(double bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return "starting";
        }
        return bytesPerSecond >= 1024 * 1024
                ? String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1024.0 / 1024.0)
                : String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static void onMain(Runnable task) {
        Minecraft.getInstance().execute(task);
    }

    private static void chat(String miniMessage) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        // Plain text rather than a formatting library the mod does not ship. The colour tag
        // is stripped and the words carry the meaning on their own.
        String plain = miniMessage.replaceAll("<[^>]+>", "");
        client.player.sendSystemMessage(Component.literal(plain));
    }
}
