package gg.nano.ui;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Automated layout validation.
 *
 * <p>Every overlap bug this UI has had was invisible to the server and to the compiler -
 * widgets sitting on top of each other render perfectly happily. This walks the widget tree
 * and reports geometry that a human would call broken: overlapping controls, anything off
 * screen, and zero-sized widgets.
 *
 * <p>Runs as part of the capture sequence, so a regression is reported in the log rather
 * than waiting to be noticed in a screenshot.
 */
public final class LayoutCheck {

    /** Widgets closer than this on an axis are treated as touching, not overlapping. */
    private static final int TOLERANCE = 0;

    private LayoutCheck() {
    }

    public static List<String> validate(Screen screen, String name, int width, int height) {
        List<AbstractWidget> widgets = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget.visible) {
                widgets.add(widget);
            }
        }

        List<String> problems = new ArrayList<>();

        for (AbstractWidget widget : widgets) {
            if (widget.getWidth() <= 0 || widget.getHeight() <= 0) {
                problems.add(describe(widget) + " has zero size");
            }
            if (widget.getX() < 0 || widget.getY() < 0
                    || widget.getX() + widget.getWidth() > width
                    || widget.getY() + widget.getHeight() > height) {
                problems.add(describe(widget) + " is off screen");
            }
        }

        // Icons are drawn, not widgets, so nothing else can see them. An icon a couple of
        // pixels high in its button is invisible to every other check here and easy to miss
        // by eye, but obvious once every row on screen is doing it.
        if (screen instanceof NanoScreen nano) {
            List<int[]> cards = nano.cardRects();
            for (int[] box : nano.centredIconBoxes()) {
                int iconCentre = box[1] + box[2] / 2;

                // A card is drawn rather than built from widgets, so check those first.
                int[] card = cardAround(cards, box);
                if (card != null) {
                    int cardCentre = card[1] + card[3] / 2;
                    if (Math.abs(iconCentre - cardCentre) > 1) {
                        problems.add("icon on card at " + card[0] + "," + card[1]
                                + " is off-centre by " + (iconCentre - cardCentre) + "px");
                    }
                    continue;
                }

                AbstractWidget host = hostOf(widgets, box);
                if (host == null) {
                    problems.add("icon at " + box[0] + "," + box[1] + " sits in no control");
                    continue;
                }
                int hostCentre = host.getY() + host.getHeight() / 2;
                if (Math.abs(iconCentre - hostCentre) > 1) {
                    problems.add("icon in " + describe(host) + " is off-centre by "
                            + (iconCentre - hostCentre) + "px vertically");
                }
            }
        }

        for (int i = 0; i < widgets.size(); i++) {
            for (int j = i + 1; j < widgets.size(); j++) {
                AbstractWidget a = widgets.get(i);
                AbstractWidget b = widgets.get(j);
                if (overlaps(a, b)) {
                    problems.add(describe(a) + " OVERLAPS " + describe(b));
                }
            }
        }

        if (problems.isEmpty()) {
            NanoUiClient.LOGGER.info("Layout OK: {} ({} widgets)", name, widgets.size());
        } else {
            NanoUiClient.LOGGER.warn("Layout PROBLEMS in {}:", name);
            for (String problem : problems) {
                NanoUiClient.LOGGER.warn("  - {}", problem);
            }
        }
        return problems;
    }

    /** The card panel an icon sits on, if any. */
    private static int[] cardAround(List<int[]> cards, int[] box) {
        int cx = box[0] + box[2] / 2;
        int cy = box[1] + box[2] / 2;
        for (int[] card : cards) {
            if (cx >= card[0] && cx <= card[0] + card[2]
                    && cy >= card[1] && cy <= card[1] + card[3]) {
                return card;
            }
        }
        return null;
    }

    /** The control an icon is drawn inside, matched by its centre point. */
    private static AbstractWidget hostOf(List<AbstractWidget> widgets, int[] box) {
        int cx = box[0] + box[2] / 2;
        int cy = box[1] + box[2] / 2;
        for (AbstractWidget widget : widgets) {
            if (cx >= widget.getX() && cx <= widget.getX() + widget.getWidth()
                    && cy >= widget.getY() && cy <= widget.getY() + widget.getHeight()) {
                return widget;
            }
        }
        return null;
    }

    private static boolean overlaps(AbstractWidget a, AbstractWidget b) {
        return a.getX() < b.getX() + b.getWidth() - TOLERANCE
                && b.getX() < a.getX() + a.getWidth() - TOLERANCE
                && a.getY() < b.getY() + b.getHeight() - TOLERANCE
                && b.getY() < a.getY() + a.getHeight() - TOLERANCE;
    }

    private static String describe(AbstractWidget widget) {
        String label = widget.getMessage() == null ? "" : widget.getMessage().getString();
        if (label.isBlank()) {
            label = widget.getClass().getSimpleName();
        }
        return "\"" + label + "\"@" + widget.getX() + "," + widget.getY()
                + " " + widget.getWidth() + "x" + widget.getHeight();
    }
}
