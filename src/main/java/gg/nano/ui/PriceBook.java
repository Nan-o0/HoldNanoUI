package gg.nano.ui;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Shows what an item sells for, right in its tooltip.
 *
 * <p>The server cannot do this itself: writing the price into the item's lore would change
 * the item, and the sell system deliberately rejects anything with lore so that named or
 * decorated gear cannot be dumped at base price. Annotating client-side keeps the item
 * untouched.
 */
public final class PriceBook {

    private static final Map<String, Double> PRICES = new HashMap<>();

    /**
     * Drops the price table on disconnect.
     *
     * <p>Prices come from one server and mean nothing on another. Left in place they would
     * annotate items with a value from a world the player is no longer in, which is worse than
     * showing no price at all.
     */
    public static void clear() {
        PRICES.clear();
    }
    private static String symbol = "MS";

    private PriceBook() {
    }

    /** Parses the PRICES payload: a header line, then {@code namespace:id=price} per line. */
    public static void load(String payload) {
        String[] lines = payload.split("\n");
        PRICES.clear();

        for (String line : lines) {
            if (line.startsWith("PRICES|")) {
                symbol = line.substring(7).trim();
                continue;
            }
            int split = line.indexOf('=');
            if (split <= 0) {
                continue;
            }
            try {
                PRICES.put(line.substring(0, split).trim(),
                        Double.parseDouble(line.substring(split + 1).trim()));
            } catch (NumberFormatException ignored) {
                // Skip a malformed row rather than dropping the whole book.
            }
        }
        NanoUiClient.LOGGER.info("Loaded {} item prices.", PRICES.size());
    }

    private static String compact(double value) {
        String[] suffix = {"", "K", "M", "B", "T"};
        int tier = 0;
        double v = value;
        while (v >= 1000 && tier < suffix.length - 1) {
            v /= 1000;
            tier++;
        }
        String body = v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
        return body + suffix[tier];
    }

    /**
     * True for an item the server drew as part of a menu.
     *
     * <p>The tooltip callback fires for every item anywhere, menu buttons included - which is
     * how the Sell button, being an emerald, ended up advertising its own sell price. The
     * server tags everything it places in a menu so those can be skipped; a real emerald in
     * a player's inventory carries no tag and still gets annotated.
     *
     * <p>Bukkit stores plugin data inside {@code custom_data} under PublicBukkitValues, and
     * that component is synced to the client, so the tag is readable here.
     */
    private static boolean isMenuItem(net.minecraft.world.item.ItemStack stack) {
        var custom = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (custom == null) {
            return false;
        }
        return custom.copyTag().getCompoundOrEmpty("PublicBukkitValues").contains("nanocore:ui");
    }

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (stack.isEmpty() || PRICES.isEmpty() || isMenuItem(stack)) {
                return;
            }
            Double unit = PRICES.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            if (unit == null) {
                return;
            }

            lines.add(Component.literal("Sells for ")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(compact(unit) + " " + symbol)
                            .withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(" each").withStyle(ChatFormatting.DARK_GRAY)));

            if (stack.getCount() > 1) {
                lines.add(Component.literal("This stack: ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(compact(unit * stack.getCount()) + " " + symbol)
                                .withStyle(ChatFormatting.GOLD)));
            }
        });
    }
}
