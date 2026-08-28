import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dumps the 26.2 mob animations so the 1.21 build can play the real ones.
 *
 * <p>1.21 has the same animation classes, near enough: a definition is a length, whether it
 * loops, and a set of channels per bone, each a list of keyframes. The one difference is that
 * a 26.2 keyframe carries a value on each side of the frame where 1.21 carries one, so the
 * post value is taken - that is the one a frame animates towards, and for every frame that is
 * not a deliberate jump the two are the same anyway.
 *
 * <p>Which means these are Mojang's animations, not an impression of them.
 */
public class AnimDump {

    static final String[] CLASSES = {
        "net.minecraft.client.animation.definitions.CopperGolemAnimation",
        "net.minecraft.client.animation.definitions.CreakingAnimation",
        "net.minecraft.client.animation.definitions.NautilusAnimation",
    };

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder("{\n");
        boolean first = true;
        int total = 0;

        for (String name : CLASSES) {
            Class<?> c = Class.forName(name);
            for (Field f : c.getFields()) {
                if (!f.getType().getSimpleName().equals("AnimationDefinition")) {
                    continue;
                }
                Object def = f.get(null);
                if (!first) {
                    out.append(",\n");
                }
                first = false;
                out.append("  \"").append(f.getName().toLowerCase(java.util.Locale.ROOT))
                   .append("\": ").append(definition(def));
                total++;
            }
        }
        out.append("\n}\n");

        Path path = Path.of("mob-animations.json");
        Files.writeString(path, out.toString());
        System.out.printf("%d animations -> %s (%d bytes)%n", total, path.toAbsolutePath(),
                Files.size(path));
    }

    @SuppressWarnings("unchecked")
    static String definition(Object def) throws Exception {
        Class<?> c = def.getClass();
        float length = (float) get(c, def, "lengthInSeconds");
        boolean looping = (boolean) get(c, def, "looping");
        Map<String, List<Object>> bones =
                (Map<String, List<Object>>) get(c, def, "boneAnimations");

        StringBuilder b = new StringBuilder("{\n");
        b.append("    \"length\": ").append(num(length)).append(",\n");
        b.append("    \"looping\": ").append(looping).append(",\n");
        b.append("    \"bones\": {");

        List<String> parts = new ArrayList<>();
        for (var e : new TreeMap<>(bones).entrySet()) {
            List<String> channels = new ArrayList<>();
            for (Object channel : e.getValue()) {
                channels.add(channel(channel));
            }
            parts.add("\n      \"" + e.getKey() + "\": [" + String.join(", ", channels) + "]");
        }
        b.append(String.join(",", parts));
        if (!parts.isEmpty()) {
            b.append("\n    ");
        }
        return b.append("}\n  }").toString();
    }

    static String channel(Object channel) throws Exception {
        Class<?> c = channel.getClass();
        Object target = get(c, channel, "target");
        Object[] frames = (Object[]) get(c, channel, "keyframes");

        List<String> out = new ArrayList<>();
        for (Object frame : frames) {
            out.add(keyframe(frame));
        }
        return "{\"target\": \"" + targetName(target) + "\", \"frames\": ["
                + String.join(", ", out) + "]}";
    }

    static String keyframe(Object frame) throws Exception {
        Class<?> c = frame.getClass();
        float t = (float) get(c, frame, "timestamp");
        // 26.2 splits the value either side of the frame; 1.21 has one. The post value is the
        // one the frame moves towards.
        Object v = has(c, "postTarget") ? get(c, frame, "postTarget") : get(c, frame, "target");
        Object interp = get(c, frame, "interpolation");
        float x = (float) v.getClass().getMethod("x").invoke(v);
        float y = (float) v.getClass().getMethod("y").invoke(v);
        float z = (float) v.getClass().getMethod("z").invoke(v);
        return "{\"t\": " + num(t) + ", \"v\": [" + num(x) + ", " + num(y) + ", " + num(z)
                + "], \"i\": \"" + interpName(interp) + "\"}";
    }

    /** Identifies a target or interpolation by comparing against the named constants. */
    static String targetName(Object target) throws Exception {
        Class<?> targets = Class.forName("net.minecraft.client.animation.AnimationChannel$Targets");
        for (Field f : targets.getFields()) {
            if (f.get(null) == target) {
                return f.getName().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "rotation";
    }

    static String interpName(Object interp) throws Exception {
        Class<?> ints =
                Class.forName("net.minecraft.client.animation.AnimationChannel$Interpolations");
        for (Field f : ints.getFields()) {
            if (f.get(null) == interp) {
                return f.getName().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "linear";
    }

    static boolean has(Class<?> c, String name) {
        try {
            c.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException ex) {
            return false;
        }
    }

    static Object get(Class<?> c, Object on, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(on);
    }

    static String num(float value) {
        return value == (long) value ? String.valueOf((long) value) : String.valueOf(value);
    }
}
