package gg.nano.ui.legacy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Rebuilds 26.2 entity models from the geometry dumped out of the 26.2 client.
 *
 * <p>Blocks are described by JSON in the jar and copy across as they are. Entity models are
 * not: they are built by Java that only exists in 26.2, so the cubes were read out of that
 * code once and written to a file, and this puts them back together on this side.
 *
 * <p>The numbers are Mojang's, down to the texture coordinates. Nothing here is measured off a
 * screenshot or guessed from a picture, which is the only way a copper golem ends up looking
 * like the copper golem rather than like somebody's memory of one.
 */
public final class HoldGeometry {

    /**
     * Loaded files, keyed by which file. One cache for all of them returned the mob geometry
     * when the statue geometry was asked for, because the first file read won.
     */
    private static final Map<String, Map<String, JsonObject>> FILES = new HashMap<>();

    private HoldGeometry() {
    }

    /** Every named layer in a dump file, loaded once. */
    private static synchronized Map<String, JsonObject> load(String resource) {
        Map<String, JsonObject> cached = FILES.get(resource);
        if (cached != null) {
            return cached;
        }
        Map<String, JsonObject> layers = new HashMap<>();
        FILES.put(resource, layers);
        try (InputStream in = HoldGeometry.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.err.println("[Nan0UI] " + resource + " is missing from the jar.");
                return layers;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String name : root.keySet()) {
                layers.put(name, root.getAsJsonObject(name));
            }
        } catch (Exception ex) {
            System.err.println("[Nan0UI] Could not read " + resource + ": " + ex);
        }
        return layers;
    }

    /**
     * One layer, ready to bake.
     *
     * @param name which layer in the file, for the statue one of the four poses
     */
    public static LayerDefinition layer(String resource, String name) {
        JsonObject entry = load(resource).get(name);
        if (entry == null) {
            // An empty mesh rather than a crash. A statue that renders as nothing is a bug to
            // chase; a client that will not start is somebody's evening.
            System.err.println("[Nan0UI] No geometry named " + name + " in " + resource);
            return LayerDefinition.create(new MeshDefinition(), 64, 64);
        }

        MeshDefinition mesh = new MeshDefinition();
        JsonObject root = entry.getAsJsonObject("root");
        addChildren(mesh.getRoot(), root.getAsJsonObject("children"));

        JsonArray size = entry.getAsJsonArray("texture");
        return LayerDefinition.create(mesh, size.get(0).getAsInt(), size.get(1).getAsInt());
    }

    private static void addChildren(PartDefinition parent, JsonObject children) {
        for (String name : children.keySet()) {
            JsonObject child = children.getAsJsonObject(name);
            PartDefinition added = parent.addOrReplaceChild(
                    name, cubes(child.getAsJsonArray("cubes")), pose(child.getAsJsonArray("pose")));
            addChildren(added, child.getAsJsonObject("children"));
        }
    }

    private static CubeListBuilder cubes(JsonArray from) {
        CubeListBuilder builder = CubeListBuilder.create();
        for (var element : from) {
            JsonObject cube = element.getAsJsonObject();
            JsonArray origin = cube.getAsJsonArray("origin");
            JsonArray size = cube.getAsJsonArray("size");
            JsonArray uv = cube.getAsJsonArray("uv");
            JsonArray grow = cube.getAsJsonArray("grow");

            builder = builder.texOffs(uv.get(0).getAsInt(), uv.get(1).getAsInt());
            if (cube.get("mirror").getAsBoolean()) {
                builder = builder.mirror();
            }
            builder = builder.addBox(
                    origin.get(0).getAsFloat(), origin.get(1).getAsFloat(),
                    origin.get(2).getAsFloat(),
                    size.get(0).getAsFloat(), size.get(1).getAsFloat(),
                    size.get(2).getAsFloat(),
                    new CubeDeformation(grow.get(0).getAsFloat(), grow.get(1).getAsFloat(),
                            grow.get(2).getAsFloat()));
            if (cube.get("mirror").getAsBoolean()) {
                // mirror() latches until it is turned off, so a mirrored cube must not leave
                // every cube after it mirrored as well.
                builder = builder.mirror(false);
            }
        }
        return builder;
    }

    private static PartPose pose(JsonArray p) {
        return PartPose.offsetAndRotation(
                p.get(0).getAsFloat(), p.get(1).getAsFloat(), p.get(2).getAsFloat(),
                p.get(3).getAsFloat(), p.get(4).getAsFloat(), p.get(5).getAsFloat());
    }

    /**
     * Applies the part scales, which have to wait until after the model is built.
     *
     * <p>A part can be scaled as well as moved and turned, and 1.21 has nowhere to put that
     * when the model is described: its PartPose carries a position and a rotation and nothing
     * else. The baked part does have the fields, so they are filled in here.
     *
     * <p>Not cosmetic. The happy ghast is drawn at a quarter size and scaled back up by four
     * at the root, so without this it is a quarter of the size it should be, and nothing else
     * about it looks wrong enough to make you check.
     */
    public static void applyScale(String resource, String name, ModelPart root) {
        JsonObject entry = load(resource).get(name);
        if (entry == null) {
            return;
        }
        scalePart(entry.getAsJsonObject("root"), root);
    }

    private static void scalePart(JsonObject described, ModelPart part) {
        JsonArray pose = described.getAsJsonArray("pose");
        if (pose.size() >= 9) {
            part.xScale = pose.get(6).getAsFloat();
            part.yScale = pose.get(7).getAsFloat();
            part.zScale = pose.get(8).getAsFloat();
        }
        JsonObject children = described.getAsJsonObject("children");
        for (String name : children.keySet()) {
            try {
                scalePart(children.getAsJsonObject(name), part.getChild(name));
            } catch (Exception ignored) {
                // A part named in the dump that is not in the baked model. Nothing to scale.
            }
        }
    }
}
