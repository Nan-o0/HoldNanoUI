import net.minecraft.client.model.geom.builders.LayerDefinition;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dumps 26.2 entity geometry as definitions rather than as baked parts.
 *
 * <p>The first attempt read the baked cubes, which carry corner positions and nothing else.
 * That is enough to see the shape and not enough to draw it: the texture coordinates live in
 * the definition the bake was made from, and without them the model comes out untextured. So
 * this walks the definitions instead, which is what the 1.21 side has to rebuild from anyway.
 */
public class MeshDump2 {

    static final String[] CLASSES = {
        "net.minecraft.client.model.animal.golem.CopperGolemModel",
        "net.minecraft.client.model.monster.creaking.CreakingModel",
        "net.minecraft.client.model.animal.ghast.HappyGhastModel",
        "net.minecraft.client.model.animal.nautilus.NautilusModel",
        "net.minecraft.client.model.monster.slime.SulfurCubeModel",
    };

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder("{\n");
        boolean firstClass = true;
        int layers = 0;

        for (String name : CLASSES) {
            String simple = name.substring(name.lastIndexOf('.') + 1);
            Map<String, LayerDefinition> found = new TreeMap<>();
            Class<?> c = Class.forName(name);
            for (Method m : c.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        || !LayerDefinition.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                for (Object[] call : argumentsFor(m)) {
                    try {
                        found.put(m.getName() + suffix(call),
                                (LayerDefinition) m.invoke(null, call));
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (found.isEmpty()) {
                System.out.printf("  skip %-30s no usable factory%n", simple);
                continue;
            }

            if (!firstClass) {
                out.append(",\n");
            }
            firstClass = false;
            out.append("  \"").append(simple).append("\": {\n");
            boolean firstLayer = true;
            for (var e : found.entrySet()) {
                if (!firstLayer) {
                    out.append(",\n");
                }
                firstLayer = false;
                LayerDefinition layer = e.getValue();
                out.append("    \"").append(e.getKey()).append("\": {\n");
                out.append("      \"texture\": [")
                   .append(texSize(layer, "xTexSize")).append(", ")
                   .append(texSize(layer, "yTexSize")).append("],\n");
                out.append("      \"root\": ").append(part(root(layer), 3)).append("\n    }");
                layers++;
            }
            out.append("\n  }");
            System.out.printf("  ok   %-30s layers=%d%n", simple, found.size());
        }
        out.append("\n}\n");

        Path path = Path.of("mob-geometry.json");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path))) {
            w.print(out);
        }
        System.out.printf("%n%d layers -> %s (%d bytes)%n", layers, path.toAbsolutePath(),
                Files.size(path));
    }

    static Object root(LayerDefinition layer) throws Exception {
        Field mesh = LayerDefinition.class.getDeclaredField("mesh");
        mesh.setAccessible(true);
        Object meshDefinition = mesh.get(layer);
        Method getRoot = meshDefinition.getClass().getMethod("getRoot");
        return getRoot.invoke(meshDefinition);
    }

    static int texSize(LayerDefinition layer, String which) throws Exception {
        Field material = LayerDefinition.class.getDeclaredField("material");
        material.setAccessible(true);
        Object m = material.get(layer);
        Field f = m.getClass().getDeclaredField(which);
        f.setAccessible(true);
        return (int) f.get(m);
    }

    @SuppressWarnings("unchecked")
    static String part(Object partDefinition, int depth) throws Exception {
        Class<?> c = partDefinition.getClass();
        String pad = "  ".repeat(depth + 1);

        Field cubesField = c.getDeclaredField("cubes");
        cubesField.setAccessible(true);
        List<Object> cubes = (List<Object>) cubesField.get(partDefinition);

        Field poseField = c.getDeclaredField("partPose");
        poseField.setAccessible(true);
        Object pose = poseField.get(partDefinition);

        Field childrenField = c.getDeclaredField("children");
        childrenField.setAccessible(true);
        Map<String, Object> children = (Map<String, Object>) childrenField.get(partDefinition);

        StringBuilder b = new StringBuilder("{\n");
        b.append(pad).append("\"pose\": ").append(pose(pose)).append(",\n");

        b.append(pad).append("\"cubes\": [");
        List<String> parts = new ArrayList<>();
        for (Object cube : cubes) {
            parts.add(cube(cube));
        }
        b.append(String.join(", ", parts)).append("],\n");

        b.append(pad).append("\"children\": {");
        List<String> kids = new ArrayList<>();
        for (var e : new TreeMap<>(children).entrySet()) {
            kids.add("\n" + pad + "  \"" + e.getKey() + "\": " + part(e.getValue(), depth + 2));
        }
        b.append(String.join(",", kids));
        if (!kids.isEmpty()) {
            b.append("\n").append(pad);
        }
        b.append("}\n");
        return b.append("  ".repeat(depth)).append("}").toString();
    }

    /** x, y, z, xRot, yRot, zRot. */
    static String pose(Object pose) throws Exception {
        List<String> v = new ArrayList<>();
        for (String name : new String[]{"x", "y", "z", "xRot", "yRot", "zRot"}) {
            Field f = pose.getClass().getDeclaredField(name);
            f.setAccessible(true);
            v.add(num((float) f.get(pose)));
        }
        return "[" + String.join(", ", v) + "]";
    }

    /** origin xyz, size xyz, uv, grow, mirror, texture scale. */
    static String cube(Object cube) throws Exception {
        Class<?> c = cube.getClass();
        Object origin = get(c, cube, "origin");
        Object dimensions = get(c, cube, "dimensions");
        Object texCoord = get(c, cube, "texCoord");
        Object texScale = get(c, cube, "texScale");
        Object grow = get(c, cube, "grow");
        boolean mirror = (boolean) get(c, cube, "mirror");

        return "{\"origin\": " + vec(origin) + ", \"size\": " + vec(dimensions)
                + ", \"uv\": " + uv(texCoord) + ", \"texScale\": " + uv(texScale)
                + ", \"grow\": " + growth(grow) + ", \"mirror\": " + mirror + "}";
    }

    static Object get(Class<?> c, Object on, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(on);
    }

    /** joml Vector3fc, read through its accessors rather than its fields. */
    static String vec(Object v) throws Exception {
        List<String> out = new ArrayList<>();
        for (String name : new String[]{"x", "y", "z"}) {
            out.add(num((float) v.getClass().getMethod(name).invoke(v)));
        }
        return "[" + String.join(", ", out) + "]";
    }

    static String uv(Object pair) throws Exception {
        float u = (float) pair.getClass().getMethod("u").invoke(pair);
        float v = (float) pair.getClass().getMethod("v").invoke(pair);
        return "[" + num(u) + ", " + num(v) + "]";
    }

    static String growth(Object deformation) throws Exception {
        List<String> out = new ArrayList<>();
        for (String name : new String[]{"growX", "growY", "growZ"}) {
            Field f = deformation.getClass().getDeclaredField(name);
            f.setAccessible(true);
            out.add(num((float) f.get(deformation)));
        }
        return "[" + String.join(", ", out) + "]";
    }

    static String num(float value) {
        return value == (long) value ? String.valueOf((long) value) : String.valueOf(value);
    }

    static List<Object[]> argumentsFor(Method m) {
        Class<?>[] types = m.getParameterTypes();
        if (types.length == 0) {
            return List.<Object[]>of(new Object[0]);
        }
        List<Object[]> calls = new ArrayList<>();
        List<List<Object>> per = new ArrayList<>();
        for (Class<?> type : types) {
            if (type == boolean.class) {
                per.add(List.of(Boolean.FALSE, Boolean.TRUE));
            } else if (type.getSimpleName().equals("CubeDeformation")) {
                try {
                    Field none = type.getField("NONE");
                    per.add(List.of(none.get(null)));
                } catch (Throwable ex) {
                    return calls;
                }
            } else {
                return calls;
            }
        }
        expand(per, 0, new Object[types.length], calls);
        return calls;
    }

    static void expand(List<List<Object>> options, int at, Object[] current,
                       List<Object[]> into) {
        if (at == options.size()) {
            into.add(current.clone());
            return;
        }
        for (Object value : options.get(at)) {
            current[at] = value;
            expand(options, at + 1, current, into);
        }
    }

    static String suffix(Object[] call) {
        StringBuilder b = new StringBuilder();
        for (Object value : call) {
            if (value instanceof Boolean flag) {
                b.append(flag ? "_true" : "_false");
            }
        }
        return b.toString();
    }
}
