package gg.nano.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a server-described screen with real vanilla widgets.
 *
 * <p>The mod is a dumb renderer on purpose: it draws whatever the server sends and reports
 * clicks back. Every rule about what a player may see or change stays server-side, so a
 * modified client cannot grant itself anything.
 *
 * <p>Row count is derived from the window height rather than fixed, because a fixed count
 * overlapped the navigation on shorter windows.
 */
public final class NanoScreen extends Screen {

    /**
     * How far the world behind the menu is dimmed.
     *
     * <p>Was 0xC0101010 - near-black at three-quarters opacity, which makes the game behind
     * it effectively gone. That is right for a pause menu and wrong for these: they are opened
     * mid-game, often while something is happening, and a player should be able to see they
     * are being shot at without closing the market first.
     *
     * <p>0x66 is a little under half opacity. It costs no readability, because the panels are
     * drawn opaque on top of this - only the gaps around them get lighter. The two hex digits
     * at the front are the only thing to change to taste.
     */
    private static final int WORLD_DIM = 0x66141414;


    private record Row(String action, String label, String state, String tooltip,
                       String colour, String icon) {
    }

    /** One line of a prose panel. Blank text is a deliberate spacer. */
    private record TextLine(String colour, String text) {
    }

    /**
     * An entry that reads as text on its own panel rather than as a button.
     *
     * <p>A notification is something that happened, not something to press. Drawing a list of
     * them as buttons makes the whole screen look actionable when only the small delete
     * control is.
     */
    private record Card(String action, String colour, String icon, String text, String when) {
    }

    private final List<Card> cards = new ArrayList<>();
    private final List<int[]> cardRects = new ArrayList<>();
    private int cardScroll;

    private static final int CARD_HEIGHT = 28;
    private static final int CARD_GAP = 3;

    private boolean cardMode() {
        return !cards.isEmpty();
    }

    /**
     * Total width of the action buttons that fit on one line starting at this index.
     *
     * <p>Measured before anything is placed so each line can be centred on its own, which is
     * what stops a wrapped strip looking ragged.
     */
    private int lineWidthFrom(int start) {
        int usable = Math.min(contentWidth(), this.width - 8);
        int total = 0;
        for (int i = start; i < actions.size(); i++) {
            int next = actionButtonWidth(actions.get(i));
            if (total > 0 && total + 2 + next > usable) {
                break;
            }
            total += (total > 0 ? 2 : 0) + next;
        }
        return total;
    }

    /** Action buttons are 18 tall, so an icon-only one is 24 wide to sit at 4:3. */
    private static final int ICON_BUTTON_WIDTH = 24;

    /**
     * How wide one action button wants to be: 4:3 when it is just an icon, otherwise its
     * label plus room for the icon beside it.
     */
    private int actionButtonWidth(Row action) {
        if (action.label().isEmpty()) {
            return ICON_BUTTON_WIDTH;
        }
        return Math.max(70, this.font.width(action.label()) + 34);
    }

    /**
     * An icon queued for drawing at a fixed spot, resolved once during init.
     *
     * <p>{@code sprite} is set for GUI sprites such as potion effect symbols, which are
     * textures rather than items and need a different draw call.
     */
    /**
     * @param centred true when the icon is meant to sit centred in its control. Grid tiles
     *                deliberately put the icon above the label instead, so they are excluded
     *                from the centring check rather than reported as broken.
     */
    private record Icon(ItemStack stack, net.minecraft.resources.Identifier sprite,
                        int x, int y, float scale, boolean centred) {
    }

    /**
     * Card panels as {x, y, w, h}.
     *
     * <p>A card is a drawn rectangle, not a widget, so the layout check has no way to see it
     * and reported every card icon as floating in nothing. Exposing the rectangles lets it
     * treat a card as the icon's container, which is what it looks like on screen.
     */
    public List<int[]> cardRects() {
        return List.copyOf(cardRects);
    }

    /** Icon boxes as {x, y, size}, for the layout check to measure against the widgets. */
    public List<int[]> centredIconBoxes() {
        List<int[]> out = new ArrayList<>();
        for (Icon icon : icons) {
            if (icon.centred()) {
                out.add(new int[]{icon.x(), icon.y(), Math.round(16 * icon.scale())});
            }
        }
        return out;
    }

    /** Item icons render at 16px natively; this shrinks them to sit inside an 18px row. */
    private static final float ICON_SCALE = 0.72f;

    /** Grid tiles have room for a full-size icon, so they do not shrink it. */
    private static final float GRID_ICON_SCALE = 1.0f;

    /** Columns when the server asks for a grid; 0 means the normal one-per-line list. */
    private int gridColumns;
    private static final int GRID_GAP = 4;

    private static final int ROW_HEIGHT = 22;
    private static final int LIST_WIDTH = 300;
    /** Wider frame for the two-column screens, where a narrow one would truncate rows. */
    private static final int WIDE_WIDTH = 440;

    /** Columns for a two-column list; 0 means the normal one-entry-per-line list. */
    private int listColumns;
    /** Scroll position of a column list, counted in visual lines rather than pages. */
    private int rowScroll;

    private boolean columnMode() {
        return listColumns > 1;
    }

    /**
     * How wide the content area is.
     *
     * <p>The two-column screens need more room than a single column ever did - splitting 300
     * pixels in half leaves each entry too narrow for a name and a value. Everything above
     * the list is measured from this too, so the search box and tabs stay flush with the
     * frame instead of floating inside it.
     */
    private int contentWidth() {
        int wanted = columnMode() ? WIDE_WIDTH : LIST_WIDTH;
        return Math.min(wanted, Math.max(120, this.width - 40));
    }
    /** Line pitch inside a prose panel - font height plus a little air. */
    private static final int LINE_HEIGHT = 11;

    /**
     * Prose content. When present the screen is a readable panel rather than a list: text
     * that is meant to be read straight through should not be chopped into one button per
     * sentence, which is what the rules screen used to do.
     */
    private final List<TextLine> textLines = new ArrayList<>();
    private final List<Component> wrappedLines = new ArrayList<>();
    private int textScroll;

    private final List<Row> tabs = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    /** Side actions - small buttons in a left column, not entries in the list. */
    private final List<Row> actions = new ArrayList<>();
    /**
     * Categories down the left edge.
     *
     * <p>Tabs across the top work for four short words; a sidebar carries longer category
     * names and leaves the full width for the content beside it.
     */
    private final List<Row> sides = new ArrayList<>();
    private String inputAction;
    private String inputPlaceholder = "";
    private String inputValue = "";
    private EditBox inputBox;

    private String sliderAction;
    private String sliderLabel = "";
    private int sliderValue;
    private int sliderMin;
    private int sliderMax = 1;
    private boolean sliderEnabled = true;
    private String sliderTooltip = "";

    /**
     * A real slider, not a click-to-step button.
     *
     * <p>Sends the value on release rather than on every drag tick - otherwise dragging
     * across the range would fire a packet per pixel.
     */
    private final class ValueSlider extends net.minecraft.client.gui.components.AbstractSliderButton {

        ValueSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(),
                    sliderMax == sliderMin ? 0
                            : (sliderValue - sliderMin) / (double) (sliderMax - sliderMin));
            updateMessage();
        }

        private int current() {
            return sliderMin + (int) Math.round(this.value * (sliderMax - sliderMin));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(sliderLabel + ": ")
                    .append(Component.literal(current() + " blocks")
                            .withStyle(sliderEnabled ? ChatFormatting.GOLD : ChatFormatting.GRAY)));
        }

        @Override
        protected void applyValue() {
            // Nothing here on purpose; the server is told on release.
        }

        @Override
        public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
            boolean handled = super.mouseReleased(event);
            NanoUiClient.send("SLIDER|" + sliderAction + "|" + current());
            return handled;
        }
    }
    private String info = "";
    private String searchValue;
    private String searchPlaceholder = "Search";
    private String sortAction;
    private String sortLabel;

    private final List<Icon> icons = new ArrayList<>();
    private EditBox searchBox;
    private int page;

    /** Horizontal offset, only ever non-zero when a label is wider than the list. */
    private int scrollX;
    private int maxScrollX;
    private int rowWidth = LIST_WIDTH;
    private boolean draggingVertical;
    private boolean draggingHorizontal;

    private int scrollBarX() {
        return this.width / 2 - contentWidth() / 2 + contentWidth() + 4;
    }

    private int listBottom() {
        return this.height - 58;
    }

    private int hBarY() {
        return listBottom() + 2;
    }

    /** Turns a y inside the vertical track into a page number. */
    private void seekVertical(double mouseY) {
        double ratio = Math.max(0, Math.min(1,
                (mouseY - listTop()) / (double) (listBottom() - listTop())));

        if (columnMode()) {
            int target = (int) Math.round(ratio * maxRowScroll());
            if (target != rowScroll) {
                rowScroll = target;
                rebuildWidgets();
            }
            return;
        }
        if (cardMode()) {
            int target = (int) Math.round(ratio * maxCardScroll());
            if (target != cardScroll) {
                cardScroll = target;
                rebuildWidgets();
            }
            return;
        }
        if (panelMode()) {
            int target = (int) Math.round(ratio * maxTextScroll());
            if (target != textScroll) {
                textScroll = target;
                rebuildWidgets();
            }
            return;
        }

        int pages = pageCount();
        if (pages <= 1) {
            return;
        }
        int target = (int) Math.round(ratio * (pages - 1));
        if (target != page) {
            page = target;
            rebuildWidgets();
        }
    }

    private void seekHorizontal(double mouseX) {
        if (maxScrollX <= 0) {
            return;
        }
        int left = this.width / 2 - contentWidth() / 2;
        double ratio = (mouseX - left) / (double) contentWidth();
        scrollX = (int) Math.round(Math.max(0, Math.min(1, ratio)) * maxScrollX);
        rebuildWidgets();
    }

    /**
     * 26.2 removed immediate-mode drawing, so an item cannot be painted from init(). Icons
     * are resolved and positioned there, then submitted during render-state extraction.
     */
    private static ItemStack resolveIcon(String key) {
        if (key == null || key.isEmpty() || key.equals("none") || key.startsWith("effect:")) {
            return ItemStack.EMPTY;
        }
        if (key.startsWith("head:")) {
            return playerHead(key.substring(5));
        }
        try {
            Identifier id = Identifier.parse(key);
            return new ItemStack(BuiltInRegistries.ITEM.getValue(id));
        } catch (RuntimeException ex) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * "head:&lt;uuid&gt;" becomes that player's actual head.
     *
     * <p>Needed so the leaderboard can be a native screen without losing the real skins that
     * make it worth looking at. The profile is left unresolved on purpose - the client looks
     * the skin up itself and fills it in, exactly as it does for a head in a chest.
     */
    private static ItemStack playerHead(String rawUuid) {
        try {
            ItemStack head = new ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD);
            head.set(net.minecraft.core.component.DataComponents.PROFILE,
                    net.minecraft.world.item.component.ResolvableProfile
                            .createUnresolved(java.util.UUID.fromString(rawUuid)));
            return head;
        } catch (RuntimeException ex) {
            return new ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD);
        }
    }

    /**
     * "effect:night_vision" resolves to the potion effect symbol rather than an item, so a
     * setting can show the icon players already associate with it.
     *
     * <p>This is a plain texture, not a GUI sprite - there is nothing under
     * {@code gui/sprites/mob_effect}, so blitSprite finds nothing and silently draws no
     * icon. It has to be blitted from its own 18x18 texture instead.
     */
    private static net.minecraft.resources.Identifier resolveSprite(String key) {
        if (key == null || !key.startsWith("effect:")) {
            return null;
        }
        try {
            return Identifier.fromNamespaceAndPath("minecraft",
                    "textures/mob_effect/" + key.substring(7) + ".png");
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Effect textures are 18x18, unlike the 16x16 items they sit beside. */
    private static final int SPRITE_SOURCE = 18;

    /**
     * Top edge that leaves an icon vertically centred in a control.
     *
     * <p>Worked out from the control's height rather than written in by hand. The offsets
     * used to be fixed numbers copied between call sites, so every icon sat one or two
     * pixels high and each control was wrong by a different amount.
     */
    private static int centredIconY(int top, int controlHeight, float scale) {
        return top + Math.round((controlHeight - Math.round(16 * scale)) / 2f);
    }

    /** Queues whichever kind of icon the key describes. */
    private void addIcon(String key, int x, int y) {
        addIcon(key, x, y, ICON_SCALE, true);
    }

    private void addIcon(String key, int x, int y, float scale, boolean centred) {
        var sprite = resolveSprite(key);
        if (sprite != null) {
            icons.add(new Icon(ItemStack.EMPTY, sprite, x, y, scale, centred));
            return;
        }
        ItemStack stack = resolveIcon(key);
        if (!stack.isEmpty()) {
            icons.add(new Icon(stack, null, x, y, scale, centred));
        }
    }

    private NanoScreen(String title) {
        super(Component.literal(title));
    }

    /** @return the screen that was shown, or null if the payload described none */
    public static NanoScreen open(String payload) {
        String[] lines = payload.split("\n");
        NanoScreen screen = null;

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] p = line.split("\\|", -1);
            switch (p[0]) {
                case "OPEN" -> screen = new NanoScreen(p.length > 2 ? p[2] : "Menu");
                case "TAB" -> {
                    if (screen != null && p.length > 3) {
                        screen.tabs.add(new Row(p[1], p[2], p[3], "", "none",
                                p.length > 4 ? p[4] : ""));
                    }
                }
                case "BTN" -> {
                    if (screen != null && p.length > 4) {
                        screen.rows.add(new Row(p[1], p[2], p[3], p[4],
                                p.length > 5 ? p[5] : "none",
                                p.length > 6 ? p[6] : ""));
                    }
                }
                case "SIDE" -> {
                    if (screen != null && p.length > 3) {
                        screen.sides.add(new Row(p[1], p[2], p[3], "", "none", ""));
                    }
                }
                case "ACT" -> {
                    if (screen != null && p.length > 4) {
                        screen.actions.add(new Row(p[1], p[2], "", p[3], p[4],
                                p.length > 5 ? p[5] : ""));
                    }
                }
                case "INPUT" -> {
                    if (screen != null && p.length > 2) {
                        screen.inputAction = p[1];
                        screen.inputPlaceholder = p[2];
                        screen.inputValue = p.length > 3 ? p[3] : "";
                    }
                }
                case "SLIDER" -> {
                    if (screen != null && p.length > 6) {
                        try {
                            screen.sliderAction = p[1];
                            screen.sliderLabel = p[2];
                            screen.sliderValue = Integer.parseInt(p[3]);
                            screen.sliderMin = Integer.parseInt(p[4]);
                            screen.sliderMax = Integer.parseInt(p[5]);
                            screen.sliderEnabled = "1".equals(p[6]);
                            screen.sliderTooltip = p.length > 7 ? p[7] : "";
                        } catch (NumberFormatException ex) {
                            screen.sliderAction = null;
                        }
                    }
                }
                case "COLUMNS" -> {
                    if (screen != null && p.length > 1) {
                        try {
                            screen.listColumns = Math.max(1, Math.min(4, Integer.parseInt(p[1])));
                        } catch (NumberFormatException ex) {
                            screen.listColumns = 0;
                        }
                    }
                }
                case "GRID" -> {
                    if (screen != null && p.length > 1) {
                        try {
                            screen.gridColumns = Math.max(1, Math.min(9, Integer.parseInt(p[1])));
                        } catch (NumberFormatException ex) {
                            screen.gridColumns = 0;
                        }
                    }
                }
                case "CARD" -> {
                    if (screen != null && p.length > 5) {
                        screen.cards.add(new Card(p[1], p[2], p[3], p[4], p[5]));
                    }
                }
                case "TEXT" -> {
                    if (screen != null && p.length > 2) {
                        screen.textLines.add(new TextLine(p[1], p[2]));
                    }
                }
                case "INFO" -> {
                    if (screen != null && p.length > 1) {
                        screen.info = p[1];
                    }
                }
                case "SEARCH" -> {
                    if (screen != null && p.length > 2) {
                        screen.searchValue = p[1];
                        screen.searchPlaceholder = p[2];
                    }
                }
                case "SORT" -> {
                    if (screen != null && p.length > 2) {
                        screen.sortAction = p[1];
                        screen.sortLabel = p[2];
                    }
                }
                // Not a screen at all: the server describing a new release. Handled before
                // the default so it cannot be silently swallowed the way an unknown tag is.
                case "UPDATE" -> UpdateFlow.offer(p);
                default -> {
                }
            }
        }

        if (screen != null) {
            Minecraft.getInstance().setScreenAndShow(screen);
        }
        return screen;
    }

    private static ChatFormatting colourOf(String name) {
        return switch (name) {
            case "green" -> ChatFormatting.GREEN;
            case "red" -> ChatFormatting.RED;
            case "yellow" -> ChatFormatting.YELLOW;
            case "gold" -> ChatFormatting.GOLD;
            default -> ChatFormatting.WHITE;
        };
    }

    /**
     * "Sort: A to Z" - label stays white, the current value is greyed. Filters are
     * secondary controls, so the value reads as informational rather than as an alert.
     */
    private static Component dimLabelBrightValue(String text) {
        int split = text.indexOf(": ");
        if (split < 0) {
            return Component.literal(text);
        }
        return Component.literal(text.substring(0, split + 2))
                .append(Component.literal(text.substring(split + 2))
                        .withStyle(ChatFormatting.GRAY));
    }

    /** "Night Vision: ON" with only the state coloured, so the eye lands on the value. */
    private Component labelFor(Row row) {
        if (row.state() == null || row.state().isEmpty()) {
            return Component.literal(row.label());
        }
        return Component.literal(row.label() + ": ")
                .append(Component.literal(row.state()).withStyle(colourOf(row.colour())));
    }

    /**
     * Whether there is genuinely room for the action column beside the list.
     *
     * <p>The column used to be clamped to the screen edge when it did not fit, which pushed
     * it straight over the first row instead of relocating it. Clamping hid the problem
     * rather than solving it.
     */
    private boolean sideColumn() {
        return (this.width / 2 - contentWidth() / 2) - 96 >= 4;
    }

    /**
     * Where the list actually begins, recorded by {@link #init()} as it lays widgets out.
     *
     * <p>This used to be recalculated independently, which drifted out of step with the
     * real layout and drew the action row straight over the first entry. One value, set
     * once, by the code that positions things.
     */
    private int listTopY = 82;

    private int listTop() {
        return listTopY;
    }

    private boolean gridMode() {
        return gridColumns > 0;
    }

    /** True when no entry has a label, so tiles can be square and icon-only. */
    private boolean iconOnlyGrid() {
        for (Row row : rows) {
            if (!row.label().isEmpty()) {
                return false;
            }
        }
        return !rows.isEmpty();
    }

    private int tileWidth() {
        return (contentWidth() - (gridColumns - 1) * GRID_GAP) / gridColumns;
    }

    /** Square when icon-only; otherwise tall enough for an icon above a centred label. */
    private int tileHeight() {
        return iconOnlyGrid() ? tileWidth() : 46;
    }

    /**
     * Whatever fits between the controls above and the navigation below.
     *
     * <p>The slider is drawn under the list, so its height has to come out of the budget
     * here. Without that the rows fill the space right down to the navigation and the
     * slider lands on top of the info line - which is exactly what the layout check caught.
     */
    private int rowsPerPage() {
        int available = (this.height - 58) - listTop();
        if (sliderAction != null) {
            available -= 24;
        }
        int pitch = gridMode() ? tileHeight() + GRID_GAP : ROW_HEIGHT;
        return Math.max(1, available / pitch);
    }

    /** Entries on one page - a grid fits a whole row of them per line. */
    private int perPage() {
        return gridMode() ? rowsPerPage() * gridColumns : rowsPerPage();
    }

    private int pageCount() {
        return Math.max(1, (int) Math.ceil(rows.size() / (double) perPage()));
    }

    @Override
    protected void init() {
        int centre = this.width / 2;
        int left = centre - contentWidth() / 2;
        icons.clear();

        // StringWidget has no alignment API in 26.2 and left-aligns inside its bounds, so
        // it is sized to the text and positioned by measurement.
        addRenderableWidget(centeredText(this.title, this.width / 2, 12));

        int y = 30;

        // Categories first, so they sit level with the top of the content beside them.
        if (!sides.isEmpty()) {
            int sideWidth = 86;
            int sideX = left - sideWidth - 6;

            // A narrow window leaves no margin to put them in. Rather than draw them off
            // the left edge, fall back to a row across the top like the action strip does.
            boolean column = sideX >= 2;
            int sideY = y;
            int flowX = left;

            for (Row category : sides) {
                boolean active = "1".equals(category.state());
                int itemWidth = column ? sideWidth
                        : Math.max(60, this.font.width(category.label()) + 16);

                if (!column && flowX + itemWidth > left + contentWidth()) {
                    flowX = left;
                    sideY += 22;
                }

                Button button = Button.builder(
                                Component.literal(category.label())
                                        .withStyle(active ? ChatFormatting.GRAY
                                                : ChatFormatting.WHITE),
                                b -> NanoUiClient.send("ACTION|" + category.action()))
                        .bounds(column ? sideX : flowX, sideY, itemWidth, 20)
                        .build();
                // The category you are already on is not somewhere to go.
                button.active = !active;
                addRenderableWidget(button);

                if (column) {
                    sideY += 22;
                } else {
                    flowX += itemWidth + 2;
                }
            }
            if (!column) {
                y = sideY + 24;
            }
        }

        if (!tabs.isEmpty()) {
            int tabWidth = contentWidth() / tabs.size();
            int x = left;
            for (Row tab : tabs) {
                boolean active = "1".equals(tab.state());
                // The selected tab greys out - it is already disabled, so shouting it in
                // colour just adds noise.
                Button button = Button.builder(
                                Component.literal(tab.label())
                                        .withStyle(active ? ChatFormatting.GRAY : ChatFormatting.WHITE),
                                b -> NanoUiClient.send("ACTION|" + tab.action()))
                        .bounds(x, y, tabWidth - 2, 20)
                        .build();
                button.active = !active;
                addRenderableWidget(button);
                x += tabWidth;
            }
            y += 26;
        }

        if (searchValue != null) {
            searchBox = new EditBox(this.font, left, y, contentWidth() - 82, 20,
                    Component.literal(searchPlaceholder));
            searchBox.setValue(searchValue);
            searchBox.setHint(Component.literal(searchPlaceholder));
            addRenderableWidget(searchBox);

            addRenderableWidget(Button.builder(Component.literal("Search"),
                            b -> NanoUiClient.send("SEARCH|" + searchBox.getValue()))
                    .bounds(left + contentWidth() - 78, y, 78, 20)
                    .build());
            y += 26;
        }

        if (sortAction != null) {
            addRenderableWidget(Button.builder(dimLabelBrightValue(sortLabel),
                            b -> NanoUiClient.send("ACTION|" + sortAction))
                    .bounds(left, y, contentWidth(), 20)
                    .build());
            y += 26;
        }

        // Actions in the flow when they cannot sit beside the list, so the list starts below
        // them rather than underneath them.
        if (!actions.isEmpty()) {
            boolean side = sideColumn();
            int actionY = y;

            // In the flow, each button is only as wide as it needs to be and the whole strip
            // is centred. Splitting the full width between them stretched two words across
            // half the screen each, which made them look like list entries rather than
            // controls sitting above the list.
            int actionX;
            int actionWidth;
            int lineWidth = 0;
            int lineStart = 0;

            if (side) {
                actionX = left - 96;
                actionWidth = 92;
            } else {
                // Wrapped into lines that fit, each centred on its own. Laid out in one
                // row regardless, eight buttons ran off both edges of the screen.
                lineWidth = lineWidthFrom(0);
                actionX = centre - lineWidth / 2;
                actionWidth = 0;
            }

            for (int index = 0; index < actions.size(); index++) {
                Row action = actions.get(index);
                int thisWidth = side ? actionWidth : actionButtonWidth(action);

                // Past the end of this line: start the next one, centred in turn.
                if (!side && index > lineStart
                        && actionX + thisWidth > centre + lineWidth / 2 + 1) {
                    lineStart = index;
                    lineWidth = lineWidthFrom(index);
                    actionX = centre - lineWidth / 2;
                    actionY += 20;
                }

                Button.Builder builder = Button.builder(
                                Component.literal(action.label())
                                        .withStyle(colourOf(action.colour())),
                                b -> NanoUiClient.send("ACTION|" + action.action()))
                        .bounds(actionX, actionY, thisWidth, 18);
                if (!action.tooltip().isEmpty()) {
                    builder.tooltip(Tooltip.create(Component.literal(action.tooltip())));
                }
                addRenderableWidget(builder.build());

                // Icon-only buttons centre their icon; labelled ones keep it at the left.
                int iconX = action.label().isEmpty()
                        ? actionX + (thisWidth - Math.round(16 * ICON_SCALE)) / 2
                        : actionX + 3;
                addIcon(action.icon(), iconX, centredIconY(actionY, 18, ICON_SCALE));

                if (side) {
                    actionY += 20;
                } else {
                    actionX += thisWidth + 2;
                }
            }
            // Only a horizontal action row consumes vertical space, and it may have wrapped.
            if (!side) {
                y = actionY + 22;
            }
        }

        // The prompt is a top control like search and sort, so it claims its space before
        // the list top is fixed. Built after the list, it would have sat on the first row.
        if (inputAction != null) {
            buildInput(left, y);
            y += 26;
        }

        // Both framed layouts draw a border 4px above their top edge; push them clear of
        // whatever control sits above rather than letting the two touch.
        listTopY = columnMode() ? y + 6 : y;

        // Centre a framed panel in whatever room is left, rather than pinning it to the top
        // and leaving the gap underneath. Worked out from the content each time, so adding
        // or removing entries re-centres on its own.
        if (columnMode() || cardMode()) {
            int naturalTop = listTopY;
            int room = listBottom() - naturalTop;
            int pitch = columnMode() ? ROW_HEIGHT : CARD_HEIGHT + CARD_GAP;
            int fits = Math.max(1, (room - 8) / pitch);
            int shown = Math.min(columnMode() ? lineCount() : cards.size(), fits);
            int contentHeight = shown * pitch + 8;
            listTopY = naturalTop + Math.max(0, (room - contentHeight) / 2);
        }

        // Cards replace the list entirely, like the prose panel does.
        if (cardMode()) {
            listTopY = y + 6;
            buildCards(left);
            if (!info.isEmpty()) {
                addRenderableWidget(centeredText(
                        Component.literal(info).withStyle(ChatFormatting.DARK_GRAY),
                        centre, this.height - 46));
            }
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(centre - 50, this.height - 28, 100, 20)
                    .build());
            return;
        }

        // A prose panel replaces the list entirely - the two never share a screen.
        if (panelMode()) {
            // The panel draws a border 4px above its top edge, which lands on whatever sits
            // above it. Push it clear rather than letting the two touch.
            listTopY = y + 6;
            buildTextPanel(left);
            if (!info.isEmpty()) {
                addRenderableWidget(centeredText(
                        Component.literal(info).withStyle(ChatFormatting.DARK_GRAY),
                        centre, this.height - 46));
            }
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(centre - 50, this.height - 28, 100, 20)
                    .build());
            return;
        }

        int perPage = perPage();
        int pages = pageCount();
        if (page >= pages) {
            page = pages - 1;
        }
        if (page < 0) {
            page = 0;
        }

        if (columnMode()) {
            rowWidth = contentWidth();
            maxScrollX = 0;
            scrollX = 0;
            // listTop(), not y: the frame is drawn from listTop and the centring pass moved
            // it. Passing the old y put the rows above their own panel, so the border cut
            // through the first line and left the same gap again at the bottom.
            buildColumns(left, listTop());
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(centre - 50, this.height - 28, 100, 20)
                    .build());
            if (!info.isEmpty()) {
                addRenderableWidget(centeredText(
                        Component.literal(info).withStyle(ChatFormatting.DARK_GRAY),
                        centre, this.height - 46));
            }
            return;
        }

        if (gridMode()) {
            // Tiles are a fixed width, so nothing can overflow sideways and there is
            // nothing for a horizontal bar to scroll.
            rowWidth = contentWidth();
            maxScrollX = 0;
            scrollX = 0;
            buildGrid(left, y, perPage);

            // Move past the tiles so anything below (a slider) does not land on them.
            int shown = Math.max(0, Math.min(perPage, rows.size() - page * perPage));
            int lines = (int) Math.ceil(shown / (double) gridColumns);
            y += lines * (tileHeight() + GRID_GAP);
        } else {
            // A row only scrolls sideways if its own text does not fit; most screens never do.
            int widest = contentWidth();
            for (Row row : rows) {
                widest = Math.max(widest, this.font.width(labelFor(row)) + 40);
            }
            rowWidth = widest;
            maxScrollX = Math.max(0, widest - contentWidth());
            scrollX = Math.min(scrollX, maxScrollX);

            int start = page * perPage;
            for (int i = 0; i < perPage && start + i < rows.size(); i++) {
                Row row = rows.get(start + i);
                Button.Builder builder = Button.builder(labelFor(row),
                                b -> NanoUiClient.send("ACTION|" + row.action()))
                        .bounds(left - scrollX, y, rowWidth, 20);
                if (!row.tooltip().isEmpty()) {
                    builder.tooltip(Tooltip.create(Component.literal(row.tooltip())));
                }
                addRenderableWidget(builder.build());

                addIcon(row.icon(), left - scrollX + 5, centredIconY(y, 20, ICON_SCALE));
                y += ROW_HEIGHT;
            }
        }

        // Below the toggles, not above them: the slider qualifies a setting that appears in
        // the list, so it reads as belonging to it rather than heading the page.
        if (sliderAction != null) {
            ValueSlider slider = new ValueSlider(left, y + 2, contentWidth(), 20);
            slider.active = sliderEnabled;
            if (!sliderTooltip.isEmpty()) {
                slider.setTooltip(Tooltip.create(Component.literal(sliderTooltip)));
            }
            addRenderableWidget(slider);
            y += 24;
        }

        int navY = this.height - 52;

        // No list means nothing to page through. Showing dead Prev/Next on an input or
        // confirm screen just implies content that is not there.
        if (rows.isEmpty()) {
            if (!info.isEmpty()) {
                addRenderableWidget(centeredText(
                        Component.literal(info).withStyle(ChatFormatting.GRAY),
                        centre, navY + 6));
            }
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(centre - 50, this.height - 28, 100, 20)
                    .build());
            return;
        }

        // Paging controls only when there is more than one page. A greyed-out Prev and Next
        // either side of "Page 1 / 1" is three controls saying nothing, and it implies there
        // is more to see when there is not.
        if (pages > 1) {
            Button previous = Button.builder(Component.literal("< Prev"), b -> {
                page--;
                rebuildWidgets();
            }).bounds(left, navY, 70, 20).build();
            previous.active = page > 0;
            addRenderableWidget(previous);

            // Only what fits between the two buttons. The full info line used to run
            // straight through Prev and Next.
            String counter = "Page " + (page + 1) + " / " + pages;
            int gap = contentWidth() - 160;
            Component counterText = Component.literal(counter).withStyle(ChatFormatting.GRAY);
            if (this.font.width(counterText) > gap) {
                counterText = Component.literal(
                                this.font.plainSubstrByWidth(counter, gap - 8) + "...")
                        .withStyle(ChatFormatting.GRAY);
            }
            addRenderableWidget(centeredText(counterText, centre, navY + 6));

            Button next = Button.builder(Component.literal("Next >"), b -> {
                page++;
                rebuildWidgets();
            }).bounds(left + contentWidth() - 70, navY, 70, 20).build();
            next.active = page < pages - 1;
            addRenderableWidget(next);
        }

        // The info line gets its own row above the navigation instead of competing with it.
        if (!info.isEmpty()) {
            addRenderableWidget(centeredText(
                    Component.literal(info).withStyle(ChatFormatting.DARK_GRAY),
                    centre, navY - 12));
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(centre - 50, this.height - 28, 100, 20)
                .build());
    }

    /**
     * The text field and its Confirm button.
     *
     * <p>Built before the "nothing to page through" branch returns. It used to be built after
     * it, which meant a screen whose only content <em>was</em> the prompt - renaming a home,
     * setting a price - drew no field at all.
     */
    private void buildInput(int left, int y) {
        inputBox = new EditBox(this.font, left, y, contentWidth() - 82, 20,
                Component.literal(inputPlaceholder));
        inputBox.setValue(inputValue);
        inputBox.setHint(Component.literal(inputPlaceholder));
        addRenderableWidget(inputBox);
        setInitialFocus(inputBox);

        addRenderableWidget(Button.builder(Component.literal("Confirm"), b -> submitInput())
                .bounds(left + contentWidth() - 78, y, 78, 20)
                .build());
    }

    /**
     * Lays entries out as tiles rather than full-width rows.
     *
     * <p>The icon sits above the label instead of beside it, which is what makes a home or an
     * item recognisable at a glance - in a list the icon is a detail at the far left, in a
     * grid it is the thing you are actually picking.
     */
    private void buildGrid(int left, int top, int perPage) {
        boolean iconOnly = iconOnlyGrid();
        int tileW = tileWidth();
        int tileH = tileHeight();
        int start = page * perPage;

        for (int i = 0; i < perPage && start + i < rows.size(); i++) {
            Row row = rows.get(start + i);
            int column = i % gridColumns;
            int line = i / gridColumns;
            int x = left + column * (tileW + GRID_GAP);
            int y = top + line * (tileH + GRID_GAP);

            // The label goes under the icon, so it is truncated to the tile rather than
            // allowed to run over its neighbour.
            Component label = Component.empty();
            if (!iconOnly) {
                String text = row.label();
                if (this.font.width(text) > tileW - 6) {
                    text = this.font.plainSubstrByWidth(text, tileW - 12) + "..";
                }
                label = Component.literal(text).withStyle(colourOf(row.colour()));
            }

            Button.Builder builder = Button.builder(label,
                            b -> NanoUiClient.send("ACTION|" + row.action()))
                    .bounds(x, y, tileW, tileH);

            // Without a visible label the tooltip is the only way to identify a tile, so
            // fall back to the full label when the server sent no tooltip.
            String tip = !row.tooltip().isEmpty() ? row.tooltip() : row.label();
            if (!tip.isEmpty()) {
                builder.tooltip(Tooltip.create(Component.literal(tip)));
            }
            addRenderableWidget(builder.build());

            int iconX = x + tileW / 2 - 8;
            // Icon-only tiles centre; labelled ones sit the icon above the text on purpose.
            int iconY = iconOnly ? y + tileH / 2 - 8 : y + 5;
            addIcon(row.icon(), iconX, iconY, GRID_ICON_SCALE, iconOnly);
        }
    }

    /** Width of the bar drawn inside the panel, plus the gap before it. */
    private static final int INNER_BAR = 8;

    /**
     * Space kept clear for the inner scroll bar - none when nothing scrolls.
     *
     * <p>Reserving it unconditionally pushed every row a few pixels left of centre on the
     * screens that fit, which is small but visible when two columns are meant to be
     * symmetrical about the middle.
     */
    private int barSpace() {
        if (columnMode()) {
            return maxRowScroll() > 0 ? INNER_BAR : 0;
        }
        if (cardMode()) {
            return maxCardScroll() > 0 ? INNER_BAR : 0;
        }
        return 0;
    }

    /**
     * Bottom of the frame.
     *
     * <p>Hugs the content when there is less of it than there is room for, rather than
     * leaving a tall empty box under the last entry.
     */
    private int frameBottom() {
        if (columnMode()) {
            return Math.min(listBottom(),
                    listTop() + 8 + Math.min(lineCount(), visibleLines()) * ROW_HEIGHT);
        }
        if (cardMode()) {
            return Math.min(listBottom(), listTop() + 8
                    + Math.min(cards.size(), cardsPerPage()) * (CARD_HEIGHT + CARD_GAP));
        }
        return listBottom();
    }

    private int columnWidth() {
        return (contentWidth() - 8 - barSpace() - (listColumns - 1) * GRID_GAP) / listColumns;
    }

    private int visibleLines() {
        return Math.max(1, (listBottom() - listTop() - 8) / ROW_HEIGHT);
    }

    private int lineCount() {
        return (int) Math.ceil(rows.size() / (double) listColumns);
    }

    private int maxRowScroll() {
        return Math.max(0, lineCount() - visibleLines());
    }

    /**
     * Entries in columns inside a framed, scrolling panel.
     *
     * <p>Reading order is left to right then down, which is how a list of ranks or listings
     * is read aloud. Scrolling is by line rather than by page: the panel has a real scroll
     * bar down its inside edge, and a bar that jumped a page at a time would not match it.
     */
    private void buildColumns(int left, int top) {
        rowScroll = Math.max(0, Math.min(rowScroll, maxRowScroll()));

        int colWidth = columnWidth();
        int lines = visibleLines();
        int start = rowScroll * listColumns;

        for (int i = 0; i < lines * listColumns && start + i < rows.size(); i++) {
            Row row = rows.get(start + i);
            int column = i % listColumns;
            int line = i / listColumns;
            int x = left + 4 + column * (colWidth + GRID_GAP);
            int y = top + 4 + line * ROW_HEIGHT;

            Component label = labelFor(row);
            if (this.font.width(label) > colWidth - 24) {
                // Truncate to the column rather than let it run under its neighbour.
                String plain = label.getString();
                label = Component.literal(
                                this.font.plainSubstrByWidth(plain, colWidth - 30) + "..")
                        .withStyle(colourOf(row.colour()));
            }

            Button.Builder builder = Button.builder(label,
                            b -> NanoUiClient.send("ACTION|" + row.action()))
                    .bounds(x, y, colWidth, 20);
            if (!row.tooltip().isEmpty()) {
                builder.tooltip(Tooltip.create(Component.literal(row.tooltip())));
            }
            addRenderableWidget(builder.build());

            addIcon(row.icon(), x + 4, centredIconY(y, 20, ICON_SCALE));
        }
    }

    private int cardsPerPage() {
        return Math.max(1, (listBottom() - listTop() - 8) / (CARD_HEIGHT + CARD_GAP));
    }

    private int maxCardScroll() {
        return Math.max(0, cards.size() - cardsPerPage());
    }

    /**
     * Each entry on its own inset panel inside the outer frame.
     *
     * <p>Text, not a button: the line itself is not clickable, only the small cross is. That
     * keeps "this already happened" and "delete this" visually separate, which a row of
     * full-width buttons cannot do.
     */
    private void buildCards(int left) {
        cardRects.clear();
        cardScroll = Math.max(0, Math.min(cardScroll, maxCardScroll()));

        int innerWidth = contentWidth() - 8 - barSpace();
        int visible = cardsPerPage();

        for (int i = 0; i < visible && cardScroll + i < cards.size(); i++) {
            Card card = cards.get(cardScroll + i);
            int x = left + 4;
            int y = listTop() + 4 + i * (CARD_HEIGHT + CARD_GAP);
            cardRects.add(new int[]{x, y, innerWidth, CARD_HEIGHT});

            addIcon(card.icon(), x + 5, centredIconY(y, CARD_HEIGHT, ICON_SCALE));

            // Leave room for the cross so a long line cannot run underneath it.
            int textWidth = innerWidth - 30 - (card.action().isEmpty() ? 4 : 22);
            String text = card.text();
            if (this.font.width(text) > textWidth) {
                text = this.font.plainSubstrByWidth(text, textWidth - 8) + "..";
            }
            addRenderableWidget(new StringWidget(x + 26, y + 4, textWidth, 10,
                    Component.literal(text).withStyle(colourOf(card.colour())), this.font));
            addRenderableWidget(new StringWidget(x + 26, y + 16, textWidth, 10,
                    Component.literal(card.when()).withStyle(ChatFormatting.DARK_GRAY),
                    this.font));

            if (!card.action().isEmpty()) {
                addRenderableWidget(Button.builder(
                                Component.literal("x").withStyle(ChatFormatting.RED),
                                b -> NanoUiClient.send("ACTION|" + card.action()))
                        .tooltip(Tooltip.create(Component.literal("Delete")))
                        .bounds(x + innerWidth - 20, y + 6, 16, 16)
                        .build());
            }
        }
    }

    /** The frame, drawn behind the widgets for both the prose panel and the column list. */
    private void drawFrame(GuiGraphicsExtractor extractor) {
        int left = this.width / 2 - contentWidth() / 2;
        int bottom = frameBottom();
        extractor.fill(left - 4, listTop() - 4, left + contentWidth() + 4, bottom + 4,
                0xFF565656);
        extractor.fill(left - 3, listTop() - 3, left + contentWidth() + 3, bottom + 3,
                0xFF121212);
    }

    /** Scroll bar down the inside edge of a framed panel. */
    private void drawInnerBar(GuiGraphicsExtractor extractor, int at, int steps,
                              int visible, int total) {
        if (steps <= 0) {
            return;
        }
        int left = this.width / 2 - contentWidth() / 2;
        int x = left + contentWidth() - INNER_BAR;
        int top = listTop() + 2;
        int bottom = frameBottom() - 2;
        int track = bottom - top;
        if (track <= 0) {
            return;
        }

        extractor.fill(x, top, x + 6, bottom, 0xFF000000);

        int thumb = Math.max(12, (int) (track * (visible / (double) Math.max(1, total))));
        int travel = track - thumb;
        int thumbY = top + (int) (travel * (at / (double) steps));
        extractor.fill(x + 1, thumbY, x + 5, thumbY + thumb,
                draggingVertical ? 0xFFFFFFFF : 0xFFC6C6C6);
    }

    private boolean panelMode() {
        return !textLines.isEmpty();
    }

    /** Inner width available to text, inside the panel's padding. */
    private int panelTextWidth() {
        return contentWidth() - 16;
    }

    private int panelVisibleLines() {
        return Math.max(1, (listBottom() - listTop() - 8) / LINE_HEIGHT);
    }

    private int maxTextScroll() {
        return Math.max(0, wrappedLines.size() - panelVisibleLines());
    }

    /**
     * Word wrap against the real font metrics.
     *
     * <p>Done by hand rather than through the font splitter so each wrapped line comes back
     * as a plain string that can be given a colour and handed to a StringWidget, which is
     * how every other piece of text on these screens is drawn.
     */
    private List<String> wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            out.add("");
            return out;
        }

        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            // A single word wider than the panel would overflow forever; break it up.
            while (this.font.width(word) > maxWidth) {
                String head = this.font.plainSubstrByWidth(word, maxWidth);
                if (head.isEmpty()) {
                    break;
                }
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                out.add(head);
                word = word.substring(head.length());
            }

            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() == 0 || this.font.width(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        out.add(line.toString());
        return out;
    }

    private void buildTextPanel(int left) {
        wrappedLines.clear();
        for (TextLine entry : textLines) {
            ChatFormatting style = colourOf(entry.colour());
            for (String piece : wrap(entry.text(), panelTextWidth())) {
                wrappedLines.add(Component.literal(piece).withStyle(style));
            }
        }

        textScroll = Math.max(0, Math.min(textScroll, maxTextScroll()));

        int visible = panelVisibleLines();
        int y = listTop() + 4;
        for (int i = 0; i < visible && textScroll + i < wrappedLines.size(); i++) {
            Component line = wrappedLines.get(textScroll + i);
            addRenderableWidget(new StringWidget(left + 8, y, panelTextWidth(), 10,
                    line, this.font));
            y += LINE_HEIGHT;
        }
    }

    private StringWidget centeredText(Component text, int centreX, int y) {
        int width = this.font.width(text);
        return new StringWidget(centreX - width / 2, y, width, 10, text, this.font);
    }

    private void submitInput() {
        if (inputAction != null && inputBox != null) {
            NanoUiClient.send("INPUT|" + inputAction + "|" + inputBox.getValue());
        }
    }

    /**
     * Enter submits, both Return and the numpad key.
     *
     * <p>Focus decides which field when there are two, but Enter still works when nothing is
     * focused - having typed in the only box on the screen, clicking away should not quietly
     * stop the key from working.
     */
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        if (key != 257 && key != 335) {
            return super.keyPressed(event);
        }

        if (searchBox != null && searchBox.isFocused()) {
            NanoUiClient.send("SEARCH|" + searchBox.getValue());
            return true;
        }
        if (inputBox != null) {
            submitInput();
            return true;
        }
        if (searchBox != null) {
            NanoUiClient.send("SEARCH|" + searchBox.getValue());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                   float partialTick) {
        // Dim the world first, before the widgets are extracted, or the game shows straight
        // through the menu. Vanilla screens get this from renderBackground, which the
        // extraction model no longer calls for us.
        extractor.fill(0, 0, this.width, this.height, WORLD_DIM);

        // Before the widgets, so content lands on top of the panel rather than under it.
        if (panelMode() || columnMode() || cardMode()) {
            drawFrame(extractor);
        }
        // Each card gets its own inset panel, one shade lighter than the frame behind it.
        for (int[] rect : cardRects) {
            extractor.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], 0xFF3A3A3A);
            extractor.fill(rect[0] + 1, rect[1] + 1,
                    rect[0] + rect[2] - 1, rect[1] + rect[3] - 1, 0xFF232323);
        }

        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        for (Icon icon : icons) {
            if (icon.sprite() != null) {
                int size = Math.round(16 * icon.scale());
                extractor.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                        icon.sprite(), icon.x(), icon.y(), 0f, 0f, size, size,
                        SPRITE_SOURCE, SPRITE_SOURCE);
                continue;
            }
            // Items always draw 16x16, so the pose is scaled to fit the space available.
            var pose = extractor.pose();
            pose.pushMatrix();
            pose.translate(icon.x(), icon.y());
            pose.scale(icon.scale(), icon.scale());
            extractor.item(icon.stack(), 0, 0);
            pose.popMatrix();
        }
        if (columnMode()) {
            drawInnerBar(extractor, rowScroll, maxRowScroll(), visibleLines(), lineCount());
        } else if (cardMode()) {
            drawInnerBar(extractor, cardScroll, maxCardScroll(), cardsPerPage(), cards.size());
        } else {
            drawScrollBar(extractor);
            drawHorizontalBar(extractor);
        }
    }

    /**
     * Scroll bar for the paged list. Drawn rather than made a widget because it is purely
     * an indicator - the wheel and the Prev/Next buttons do the actual moving.
     */
    private void drawScrollBar(GuiGraphicsExtractor extractor) {
        // Steps are pages in a list and lines in a panel, but the bar behaves identically.
        int steps = panelMode() ? maxTextScroll() + 1 : pageCount();
        int at = panelMode() ? textScroll : page;
        if (steps <= 1) {
            return;
        }

        int left = this.width / 2 - contentWidth() / 2;
        int x = left + contentWidth() + 4;
        int top = listTop();
        int bottom = this.height - 58;
        int trackHeight = bottom - top;
        if (trackHeight <= 0) {
            return;
        }

        extractor.fill(x, top, x + 6, bottom, 0xFF1A1A1A);

        // Sized by how much of the content is on screen, so it reads as a real scroll bar.
        int thumbHeight = panelMode()
                ? Math.max(12, (int) (trackHeight
                        * (panelVisibleLines() / (double) Math.max(1, wrappedLines.size()))))
                : Math.max(12, trackHeight / steps);
        int travel = trackHeight - thumbHeight;
        int thumbY = top + (int) (travel * (at / (double) (steps - 1)));

        extractor.fill(x + 1, thumbY, x + 5, thumbY + thumbHeight,
                draggingVertical ? 0xFFFFFFFF : 0xFFC6C6C6);
    }

    /** Only drawn when a label genuinely overflows - never on a normal screen. */
    private void drawHorizontalBar(GuiGraphicsExtractor extractor) {
        if (maxScrollX <= 0) {
            return;
        }
        int left = this.width / 2 - contentWidth() / 2;
        int y = hBarY();

        extractor.fill(left, y, left + contentWidth(), y + 6, 0xFF1A1A1A);

        int thumbWidth = Math.max(20, (int) (contentWidth() * (contentWidth() / (double) rowWidth)));
        int travel = contentWidth() - thumbWidth;
        int thumbX = left + (int) (travel * (scrollX / (double) maxScrollX));

        extractor.fill(thumbX, y + 1, thumbX + thumbWidth, y + 5,
                draggingHorizontal ? 0xFFFFFFFF : 0xFFC6C6C6);
    }

    // 26.2 passes a MouseButtonEvent rather than loose coordinates.
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,
                                boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        // The framed layouts put their bar inside the frame, not beside it.
        if (columnMode() || cardMode()) {
            int innerX = this.width / 2 - contentWidth() / 2 + contentWidth() - INNER_BAR;
            int reach = columnMode() ? maxRowScroll() : maxCardScroll();
            if (reach > 0 && mouseX >= innerX && mouseX <= innerX + 6
                    && mouseY >= listTop() && mouseY <= listBottom()) {
                draggingVertical = true;
                seekVertical(mouseY);
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }

        int barX = scrollBarX();
        boolean scrollable = panelMode() ? maxTextScroll() > 0 : pageCount() > 1;
        if (scrollable && mouseX >= barX && mouseX <= barX + 6
                && mouseY >= listTop() && mouseY <= listBottom()) {
            draggingVertical = true;
            seekVertical(mouseY);
            return true;
        }

        int left = this.width / 2 - contentWidth() / 2;
        if (maxScrollX > 0 && mouseY >= hBarY() && mouseY <= hBarY() + 6
                && mouseX >= left && mouseX <= left + contentWidth()) {
            draggingHorizontal = true;
            seekHorizontal(mouseX);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event,
                                double dragX, double dragY) {
        if (draggingVertical) {
            seekVertical(event.y());
            return true;
        }
        if (draggingHorizontal) {
            seekHorizontal(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        draggingVertical = false;
        draggingHorizontal = false;
        return super.mouseReleased(event);
    }

    /** Scroll wheel pages the list, which is what people expect from a long list. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (searchBox != null && searchBox.isFocused()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (columnMode()) {
            int target = Math.max(0,
                    Math.min(maxRowScroll(), rowScroll - (int) Math.signum(scrollY) * 2));
            if (target != rowScroll) {
                rowScroll = target;
                rebuildWidgets();
            }
            return true;
        }
        if (cardMode()) {
            int target = Math.max(0,
                    Math.min(maxCardScroll(), cardScroll - (int) Math.signum(scrollY) * 2));
            if (target != cardScroll) {
                cardScroll = target;
                rebuildWidgets();
            }
            return true;
        }
        if (panelMode()) {
            // Three lines a notch, the same as most text views.
            int target = Math.max(0,
                    Math.min(maxTextScroll(), textScroll - (int) Math.signum(scrollY) * 3));
            if (target != textScroll) {
                textScroll = target;
                rebuildWidgets();
            }
            return true;
        }
        if (scrollY < 0 && page < pageCount() - 1) {
            page++;
            rebuildWidgets();
            return true;
        }
        if (scrollY > 0 && page > 0) {
            page--;
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
