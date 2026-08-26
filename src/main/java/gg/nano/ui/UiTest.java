package gg.nano.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.List;

/**
 * Renders every screen layout with sample data and screenshots each one.
 *
 * <p>Exists because layout bugs are invisible from the server side - overlapping widgets
 * compile and log perfectly happily. Running {@code /uitest} writes a PNG per screen so the
 * layouts can actually be looked at instead of reasoned about.
 *
 * <p>The payloads below are deliberately hostile: long labels, many rows to force paging,
 * and every optional control present at once. If a layout survives these it will survive
 * real data.
 */
public final class UiTest {

    private record Case(String name, String payload) {
    }

    private static final List<Case> CASES = List.of(
            new Case("settings", """
                    OPEN|settings|Settings
                    TAB|t1|General|1|minecraft:comparator
                    TAB|t2|Privacy|0|minecraft:shield
                    TAB|t3|Notifications|0|minecraft:bell
                    BTN|a|Night Vision|ON|See in the dark without a torch|green|effect:night_vision
                    BTN|b|Phantom Spawning|OFF|Let phantoms spawn|red|minecraft:phantom_membrane
                    BTN|c|Hostile Mob Spawning|ON|Allow hostile mobs near you|green|minecraft:zombie_head
                    BTN|d|Teleport Requests|Only people I follow|Who may /tpa you|yellow|minecraft:ender_eye
                    BTN|e|Sidebar|ON|Show the sidebar|green|minecraft:item_frame
                    """),

            new Case("help-paged", buildHelp()),

            new Case("market", """
                    OPEN|market|Market
                    COLUMNS|2
                    SEARCH||Search items or sellers
                    SORT|s|Sort: Newest first
                    INFO|3 listing(s), 12.5K MS available
                    ACT|x|Sell an Item|Put an item in and set a price|green|minecraft:emerald
                    ACT|y|My Listings|Manage what you are selling|none|minecraft:chest
                    BTN|b1|64x Diamond Block|1.2M MS|Sold by NanO0h|gold|minecraft:diamond_block
                    BTN|b2|1x Written Book|500 MS|Sold by Steve - Signed by KreekCraft, Title: A Very Long Book Title Indeed|gold|minecraft:written_book
                    BTN|b3|5x Dirt|300 MS|Your own listing|red|minecraft:dirt
                    """),

            new Case("input", """
                    OPEN|input|Set Price
                    INPUT|price_set|Price, e.g. 500 or 2.5k|
                    INFO|You keep 95% after tax
                    ACT|back|Back|Return without changing the price|none|minecraft:arrow
                    """),

            new Case("empty", """
                    OPEN|market|Market
                    SEARCH|nothingmatches|Search items or sellers
                    SORT|s|Sort: Price: high to low
                    INFO|0 listing(s)
                    ACT|x|Sell an Item|Put an item in and set a price|green|minecraft:emerald
                    """),

            // Long enough to overflow the panel, so the scroll bar and the wrapping are
            // both exercised rather than just the happy path of a short page.
            new Case("rules-panel", buildRules()),

            // Five homes in a four-wide grid, so the wrap onto a second line is covered, and
            // one name far too long for a tile to prove it truncates instead of overflowing.
            new Case("homes-grid", """
                    OPEN|homes|Homes
                    GRID|4
                    INFO|5 of 5 slots used
                    ACT|home_set|Set Home Here|Save where you are standing|green|minecraft:red_bed
                    ACT|home_edit|Edit|Rename, re-icon or delete a home|none|minecraft:name_tag
                    BTN|home_tp:base|Base||world  -412, 71, 1183|none|minecraft:oak_door
                    BTN|home_tp:mine|Deepslate Mine||world  -2044, -48, 903|none|minecraft:diamond_pickaxe
                    BTN|home_tp:nether_hub|Nether Hub||world_nether  118, 64, -71|none|minecraft:crying_obsidian
                    BTN|home_tp:farm|Villager Trading Hall||world  310, 68, -204|none|minecraft:emerald
                    BTN|home_tp:x|Averyveryverylongnameindeed||world  0, 64, 0|none|minecraft:beacon
                    """),

            new Case("home-manage", """
                    OPEN|homemanage|Nether Hub
                    INFO|world_nether  118, 64, -71
                    ACT|home_edit|Back|Return to the list|none|minecraft:arrow
                    BTN|home_tp:nether_hub|Teleport||Go there now|green|minecraft:crying_obsidian
                    BTN|home_rename:nether_hub|Rename||Give it a different name|none|minecraft:name_tag
                    BTN|home_icon:nether_hub|Change Icon||Pick any item as its icon|none|minecraft:crying_obsidian
                    BTN|home_delete:nether_hub|Delete||Remove this home|red|minecraft:barrier
                    """),

            new Case("icon-picker", buildIconPicker()),

            new Case("leaderboard-columns", buildLeaderboard()),

            new Case("notifications", buildNotes()),

            // Everything the protocol supports on one screen. If the layout survives this it
            // survives any real payload.
            new Case("kitchen-sink", buildKitchenSink()),

            // ----- Stress cases -----
            //
            // Deliberately past anything the server sends today. The point is to find the
            // edge before a player does: a screen that only breaks at 300 rows still breaks
            // once the market gets busy, and nothing else here would ever catch it.

            new Case("stress-columns-300", buildStress("COLUMNS|2", 300)),
            new Case("stress-grid-300", buildStress("GRID|4", 300)),
            new Case("stress-cards-200", buildStressCards(200)),
            new Case("stress-long-labels", buildLongLabels()),
            new Case("stress-many-controls", buildManyControls()),
            new Case("stress-empty", """
                    OPEN|empty|Nothing At All
                    COLUMNS|2
                    """),
            new Case("stress-one-row", """
                    OPEN|one|Single Entry
                    COLUMNS|2
                    INFO|1 entry
                    BTN|noop|Only Row|1 MS|The only thing here|gold|minecraft:dirt
                    """));

    /** Far more entries than any real screen, to find where the layout gives out. */
    private static String buildStress(String mode, int rows) {
        StringBuilder sb = new StringBuilder("OPEN|stress|Stress " + rows + "\n");
        sb.append(mode).append('\n');
        sb.append("INFO|").append(rows).append(" entries\n");
        sb.append("ACT|hub_back|Back|Return|none|minecraft:arrow\n");
        for (int i = 1; i <= rows; i++) {
            sb.append("BTN|noop|Entry ").append(i).append('|').append(i * 7).append("K MS|")
                    .append("Tooltip for entry ").append(i)
                    .append("|none|minecraft:stone\n");
        }
        return sb.toString();
    }

    private static String buildStressCards(int cards) {
        StringBuilder sb = new StringBuilder("OPEN|stress|Stress Cards\n");
        sb.append("INFO|").append(cards).append(" notifications\n");
        sb.append("ACT|hub_back|Back|Return|none|minecraft:arrow\n");
        for (int i = 1; i <= cards; i++) {
            sb.append("CARD|note_del:").append(i).append("|none|minecraft:paper|")
                    .append("Notification number ").append(i)
                    .append(" with a reasonable amount of text|")
                    .append(i).append("m ago\n");
        }
        return sb.toString();
    }

    /** Nothing here fits. Every label, tooltip and value is longer than its space. */
    private static String buildLongLabels() {
        String huge = "Averylongunbrokenrunofcharacterswithnospacesatallthatcannotwrapanywhere";
        StringBuilder sb = new StringBuilder("OPEN|stress|Long Labels\n");
        sb.append("COLUMNS|2\n");
        sb.append("SEARCH|").append(huge).append('|').append(huge).append('\n');
        sb.append("SORT|noop|Sort: ").append(huge).append('\n');
        sb.append("INFO|").append(huge).append('\n');
        sb.append("ACT|hub_back|").append(huge).append('|').append(huge)
                .append("|none|minecraft:arrow\n");
        for (int i = 1; i <= 12; i++) {
            sb.append("BTN|noop|").append(huge).append(i).append('|').append(huge)
                    .append("|").append(huge).append("|gold|minecraft:stone\n");
        }
        return sb.toString();
    }

    /** Every control type at once, each with far more entries than designed for. */
    private static String buildManyControls() {
        StringBuilder sb = new StringBuilder("OPEN|stress|Many Controls\n");
        for (int i = 1; i <= 8; i++) {
            sb.append("TAB|noop|Tab").append(i).append('|').append(i == 1 ? "1" : "0")
                    .append("|minecraft:paper\n");
        }
        for (int i = 1; i <= 6; i++) {
            sb.append("SIDE|noop|Category ").append(i).append('|')
                    .append(i == 1 ? "1" : "0").append('\n');
        }
        sb.append("SEARCH||Search\n");
        sb.append("SORT|noop|Sort: Something\n");
        for (int i = 1; i <= 8; i++) {
            sb.append("ACT|noop|Action ").append(i).append("|Tooltip|none|minecraft:stone\n");
        }
        sb.append("INFO|everything at once\n");
        for (int i = 1; i <= 30; i++) {
            sb.append("BTN|noop|Row ").append(i).append("|value|tip|none|minecraft:stone\n");
        }
        sb.append("SLIDER|set_radius|Radius|32|8|64|1|Tooltip\n");
        return sb.toString();
    }

    /** More cards than fit, including one line far too long for its panel. */
    private static String buildNotes() {
        StringBuilder sb = new StringBuilder("OPEN|notes|Notifications\n");
        sb.append("ACT|hub_back|Main Menu|Back to the hub|none|minecraft:compass\n");
        sb.append("ACT|note_clear|Clear All|Delete every notification|red|"
                + "minecraft:lava_bucket\n");
        sb.append("INFO|12 notification(s)\n");

        String[][] rows = {
                {"gold", "minecraft:gold_block", "64x Diamond Block sold to Steve for 1.2M MS",
                        "2m ago"},
                {"green", "minecraft:gold_ingot", "Received 5.5K MS from KreekCraft", "14m ago"},
                {"yellow", "minecraft:ender_pearl", "Steve asked to teleport to you", "1h ago"},
                {"red", "minecraft:iron_sword", "KreekCraft claimed the 12K bounty on you",
                        "3h ago"},
                {"none", "minecraft:paper",
                        "A deliberately long notification line that will not fit inside its "
                                + "panel and has to be truncated somewhere sensible", "1d ago"},
        };
        for (int i = 0; i < 12; i++) {
            String[] row = rows[i % rows.length];
            sb.append("CARD|note_del:").append(i + 1).append('|')
                    .append(row[0]).append('|').append(row[1]).append('|')
                    .append(row[2]).append('|').append(row[3]).append('\n');
        }
        return sb.toString();
    }

    /** Two columns, more entries than fit, and one name long enough to need truncating. */
    private static String buildLeaderboard() {
        StringBuilder sb = new StringBuilder("OPEN|top|Top Balances\n");
        sb.append("COLUMNS|2\n");
        sb.append("ACT|top_mode:NET_WORTH|Top Net Worth|Switch the ranking|none|minecraft:chest\n");
        sb.append("ACT|hub_back|Main Menu|Back to the hub|none|minecraft:compass\n");
        sb.append("INFO|Your score: 7.7K\n");
        for (int i = 1; i <= 40; i++) {
            String name = i == 3 ? "APlayerWithAVeryLongNameIndeed" : "Player" + i;
            sb.append("BTN|noop|#").append(i).append(' ').append(name)
                    .append('|').append(1000 - i * 7).append("K MS|")
                    .append(i == 1 ? "This is you" : "Balance: " + (1000 - i * 7) + "K MS")
                    .append('|')
                    .append(switch (i) {
                        case 1 -> "gold";
                        case 2 -> "white";
                        case 3 -> "yellow";
                        default -> "none";
                    })
                    .append("|minecraft:player_head\n");
        }
        return sb.toString();
    }

    private static String buildIconPicker() {
        String[] items = {"diamond_pickaxe", "oak_door", "crying_obsidian", "red_bed",
                "ender_pearl", "emerald", "gold_block", "written_book", "barrier", "name_tag",
                "compass", "paper", "hopper", "comparator", "iron_sword", "player_head"};
        StringBuilder sb = new StringBuilder("OPEN|iconpick|Choose an Icon\n");
        sb.append("GRID|8\n");
        sb.append("SEARCH||Search items\n");
        sb.append("INFO|Showing 90 of 1374 - search to narrow\n");
        sb.append("ACT|home_manage:base|Back|Keep the current icon|none|minecraft:arrow\n");
        for (int i = 0; i < 90; i++) {
            String item = items[i % items.length];
            // Empty label - these are icon-only tiles, named by their tooltip.
            sb.append("BTN|home_seticon:base:").append(item.toUpperCase(java.util.Locale.ROOT))
                    .append("|||").append(item.replace('_', ' '))
                    .append("|none|minecraft:").append(item).append('\n');
        }
        return sb.toString();
    }

    private static String buildKitchenSink() {
        StringBuilder sb = new StringBuilder("OPEN|sink|Everything At Once\n");
        sb.append("TAB|t1|General|1|minecraft:comparator\n");
        sb.append("TAB|t2|Visuals|0|minecraft:painting\n");
        sb.append("TAB|t3|Privacy|0|minecraft:shield\n");
        sb.append("TAB|t4|Notifications|0|minecraft:bell\n");
        sb.append("SEARCH|redstone|Search items or sellers\n");
        sb.append("SORT|s|Sort: Price: high to low\n");
        sb.append("INFO|18 of 1374 shown\n");
        sb.append("ACT|a1|Sell an Item|Put an item in and set a price|green|minecraft:emerald\n");
        sb.append("ACT|a2|My Listings|Manage what you are selling|none|minecraft:chest\n");
        sb.append("ACT|a3|Buy Orders|Someone is already paying|none|minecraft:hopper\n");
        for (int i = 1; i <= 18; i++) {
            sb.append("BTN|k").append(i)
                    .append("|A deliberately long entry name number ").append(i)
                    .append(" that will not fit|999.9K MS|")
                    .append("A tooltip long enough to wrap onto a second line when shown|")
                    .append(i % 3 == 0 ? "red" : i % 3 == 1 ? "green" : "gold")
                    .append("|minecraft:redstone\n");
        }
        sb.append("SLIDER|set_radius|Clear Radius|32|8|64|1|How far mobs are cleared\n");
        return sb.toString();
    }

    private static String buildRules() {
        StringBuilder sb = new StringBuilder("OPEN|rules|Hold SMP Rules\n");
        sb.append("TEXT|none|These are the rules. Anything not listed here is allowed.\n");
        sb.append("TEXT|none|\n");
        sb.append("TEXT|gold|Griefing and raiding are allowed\n");
        sb.append("TEXT|none|Griefing, raiding and stealing are all allowed. Lock up what you "
                + "care about and do not keep everything in one base. Items lost this way are "
                + "not returned by staff.\n");
        sb.append("TEXT|none|\n");
        sb.append("TEXT|gold|Chat is mostly unfiltered\n");
        sb.append("TEXT|none|Swearing, arguing and trash talk are fine. One thing is filtered: "
                + "telling someone to kill themselves, in any spelling.\n");
        sb.append("TEXT|none|\n");
        sb.append("TEXT|gold|Averylongunbrokenwordthatcannotfitonasinglelineandmustbesplit\n");
        sb.append("TEXT|none|\n");
        for (int i = 1; i <= 6; i++) {
            sb.append("TEXT|none|Filler paragraph ").append(i)
                    .append(" so the panel overflows and the scroll bar has something to do.\n");
        }
        sb.append("TEXT|red|Staff decisions are final.\n");
        sb.append("ACT|hub_back|Main Menu|Back to the hub|none|minecraft:compass\n");
        return sb.toString();
    }

    private static String buildHelp() {
        StringBuilder sb = new StringBuilder("OPEN|help|Commands\n");
        sb.append("SEARCH||Search commands\n");
        sb.append("SORT|s|Sort: A to Z\n");
        sb.append("INFO|42 of 42 commands\n");
        for (int i = 1; i <= 42; i++) {
            sb.append("BTN|r").append(i).append("|/command").append(i)
                    .append(" <player> [reason]||What command ").append(i)
                    .append(" does, described at some length|none|minecraft:paper\n");
        }
        return sb.toString();
    }

    private static int index = -1;
    private static int ticks;
    private static int problems;
    private static NanoScreen open;

    /**
     * Findings, written to a file beside the game.
     *
     * <p>The log works if you are sitting at the client, but the person acting on these is
     * usually looking at the server instead. A single file is something they can be pointed
     * at without going digging through client logs for one warning.
     */
    private static final List<String> report = new java.util.ArrayList<>();

    private static void writeReport(Minecraft client) {
        java.nio.file.Path path = client.gameDirectory.toPath().resolve("nanoui-report.txt");
        List<String> out = new java.util.ArrayList<>();
        out.add("NanoUI layout report - " + java.time.LocalDateTime.now());
        out.add("Mod build in use: " + NanoUiClient.BUILT
                + "  (if this is older than the last deploy, the game needs restarting)");
        out.add("Window " + client.getWindow().getGuiScaledWidth() + "x"
                + client.getWindow().getGuiScaledHeight()
                + " at GUI scale " + client.getWindow().getGuiScale());
        out.add(problems == 0
                ? "No layout problems across " + CASES.size() + " screens."
                : problems + " layout problem(s) across " + CASES.size() + " screens.");
        out.add("");
        out.addAll(report);
        out.add("");

        // The screenshot filenames, in case order, so a run can be reviewed in full rather
        // than by whichever couple of files happened to get opened.
        out.add("Screens captured, in order:");
        for (int i = 0; i < CASES.size(); i++) {
            out.add("  " + (i + 1) + ". " + CASES.get(i).name());
        }

        try {
            java.nio.file.Files.write(path, out, java.nio.charset.StandardCharsets.UTF_8);
            NanoUiClient.LOGGER.info("UI test: report written to {}", path);
        } catch (java.io.IOException ex) {
            NanoUiClient.LOGGER.warn("UI test: could not write report: {}", ex.getMessage());
        }
    }

    private UiTest() {
    }

    public static void start() {
        index = 0;
        ticks = 0;
        problems = 0;
        report.clear();
        NanoUiClient.LOGGER.info("UI test: capturing {} screens.", CASES.size());
    }

    /** True while a run is in progress. */
    public static boolean running() {
        return index >= 0;
    }

    public static void tick(Minecraft client) {
        // Nothing runs on its own any more. A capture only happens when the server asks for
        // one, which it does from /nanocore audit ui - it used to fire on every launch and
        // dump a folder of screenshots nobody asked for.
        if (index < 0 || index >= CASES.size()) {
            return;
        }
        // Needs a world. At the title screen there is no level context, so items render as
        // nothing and the capture misrepresents the UI.
        if (client.level == null) {
            return;
        }

        Case current = CASES.get(index);
        if (ticks == 0) {
            // Keep the reference rather than reading it back off Minecraft, whose screen
            // accessor moved in 26.2.
            open = NanoScreen.open(current.payload());
        }
        ticks++;

        // Validate geometry once the screen has laid out, before capturing it.
        if (ticks == 4 && open != null) {
            List<String> found = LayoutCheck.validate(open, current.name(),
                    open.width, open.height);
            problems += found.size();
            report.add((found.isEmpty() ? "OK   " : "FAIL ") + current.name()
                    + "  (" + open.width + "x" + open.height + ")");
            for (String problem : found) {
                report.add("       - " + problem);
            }
        }

        // A few ticks of settle time so widgets are laid out before the capture.
        if (ticks == 6) {
            // The two-argument overload handles the render target itself; Minecraft no
            // longer exposes one in 26.2.
            Screenshot.grab(client, false);
            NanoUiClient.LOGGER.info("UI test: captured {} (screenshot {})",
                    current.name(), index + 1);
        }

        if (ticks > 9) {
            index++;
            ticks = 0;
            if (index >= CASES.size()) {
                index = -1;
                Compat.setScreen(null);
                if (problems == 0) {
                    NanoUiClient.LOGGER.info("UI test: done. No layout problems found.");
                } else {
                    NanoUiClient.LOGGER.warn("UI test: done. {} layout problem(s) found - "
                            + "search the log for 'Layout PROBLEMS'.", problems);
                }
                writeReport(client);
            }
        }
    }
}
