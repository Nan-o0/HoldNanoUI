package gg.nano.ui.legacy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays the real 26.2 animations on the rebuilt models.
 *
 * <p>1.21 has the same animation classes as 26.2 - a definition is a length, whether it loops,
 * and a set of channels per bone, each a list of keyframes - so these are not reimplemented.
 * The definitions were read out of the 26.2 client, written to a file, and are rebuilt here as
 * the real thing and handed to the game's own player.
 *
 * <p>Which is the difference between a copper golem that walks and a copper golem that slides
 * along with its legs held still.
 */
public final class HoldAnimations {

    private static final String FILE = "/holdsmp-animations.json";

    private static Map<String, AnimationDefinition> loaded;

    private HoldAnimations() {
    }

    private static synchronized Map<String, AnimationDefinition> all() {
        if (loaded != null) {
            return loaded;
        }
        loaded = new HashMap<>();
        try (InputStream in = HoldAnimations.class.getResourceAsStream(FILE)) {
            if (in == null) {
                System.err.println("[Nan0UI] " + FILE + " is missing; mobs will not animate.");
                return loaded;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String name : root.keySet()) {
                try {
                    loaded.put(name, build(root.getAsJsonObject(name)));
                } catch (Exception ex) {
                    System.err.println("[Nan0UI] Bad animation " + name + ": " + ex);
                }
            }
        } catch (Exception ex) {
            System.err.println("[Nan0UI] Could not read " + FILE + ": " + ex);
        }
        return loaded;
    }

    public static AnimationDefinition get(String name) {
        return all().get(name);
    }

    private static AnimationDefinition build(JsonObject described) {
        AnimationDefinition.Builder builder = AnimationDefinition.Builder
                .withLength(described.get("length").getAsFloat());
        if (described.get("looping").getAsBoolean()) {
            builder = builder.looping();
        }
        JsonObject bones = described.getAsJsonObject("bones");
        for (String bone : bones.keySet()) {
            for (var element : bones.getAsJsonArray(bone)) {
                JsonObject channel = element.getAsJsonObject();
                builder = builder.addAnimation(bone, channel(channel));
            }
        }
        return builder.build();
    }

    private static AnimationChannel channel(JsonObject described) {
        String target = described.get("target").getAsString();
        List<Keyframe> frames = new ArrayList<>();
        for (var element : described.getAsJsonArray("frames")) {
            JsonObject frame = element.getAsJsonObject();
            JsonArray v = frame.getAsJsonArray("v");
            float x = v.get(0).getAsFloat();
            float y = v.get(1).getAsFloat();
            float z = v.get(2).getAsFloat();
            // The dump holds the values already in the units each target expects, which is
            // radians for rotation, so they are passed straight through rather than through
            // degreeVec - putting degrees into a radian channel spins a limb about nine times
            // further than it should go.
            frames.add(new Keyframe(frame.get("t").getAsFloat(), new Vector3f(x, y, z),
                    interpolation(frame.get("i").getAsString())));
        }
        return new AnimationChannel(targetOf(target), frames.toArray(new Keyframe[0]));
    }

    private static AnimationChannel.Target targetOf(String name) {
        return switch (name) {
            case "position" -> AnimationChannel.Targets.POSITION;
            case "scale" -> AnimationChannel.Targets.SCALE;
            default -> AnimationChannel.Targets.ROTATION;
        };
    }

    private static AnimationChannel.Interpolation interpolation(String name) {
        return name.equals("catmullrom")
                ? AnimationChannel.Interpolations.CATMULLROM
                : AnimationChannel.Interpolations.LINEAR;
    }

    /**
     * Runs an animation over a model.
     *
     * <p>The game's own player wants a HierarchicalModel rather than a bare part, so the part
     * is wrapped in one. Nothing else about the wrapper is used.
     */
    public static void play(ModelPart root, AnimationDefinition animation, long millis) {
        if (animation == null) {
            return;
        }
        // The animation moves parts from wherever they were left last frame, so they have to
        // go back first. Without this every frame builds on the one before and the model
        // slowly folds in on itself.
        root.getAllParts().forEach(ModelPart::resetPose);
        KeyframeAnimations.animate(new Wrapper(root), animation, millis, 1.0F,
                new Vector3f());
    }

    /** A model that is only a root, because that is all the animation player needs. */
    private static final class Wrapper extends HierarchicalModel<Entity> {

        private final ModelPart root;

        Wrapper(ModelPart root) {
            this.root = root;
        }

        @Override
        public ModelPart root() {
            return root;
        }

        @Override
        public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount,
                              float ageInTicks, float netHeadYaw, float headPitch) {
        }
    }
}
