package gg.nano.ui.legacy;

import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MagmaCubeRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.MagmaCube;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Part names an overlay may share with its body. */


/**
 * Draws the mobs 26.2 has and 1.21 does not.
 *
 * <p>A block can be registered and put in the world, and a mob cannot be handled the same way:
 * the server has to send the client an entity, and it can only send one the client's own
 * version knows about. So the server sends a stand-in that already exists, keeps it exactly
 * where the real mob is, and says separately what it really is. This draws the real one over
 * the top.
 *
 * <p>The geometry is Mojang's, read out of the 26.2 client and rebuilt by {@link HoldGeometry}.
 * Nothing here is a magma cube wearing a hat.
 *
 * <h2>What this does not do</h2>
 *
 * <p>The models are posed as they are built and do not animate. A copper golem walks without
 * moving its legs. Animations live in their own classes in 26.2, separately from the geometry,
 * and are not read yet; the shape, the size and the texture are right and the movement is not.
 * Saying so here because it is the kind of thing that looks like a bug later.
 */
public final class HoldMobs {

    private static final String[] NAMES = {"root", "body", "head",
        "left_arm", "right_arm", "left_leg", "right_leg", "cube"};

    /** Message this handles: {@code MB|entityId|mob} , or {@code MB|entityId|-} to forget one. */
    public static final String PREFIX = "MB";

    private static final String GEOMETRY = "/holdsmp-mobs.json";

    /**
     * Which animation each mob walks and stands with.
     *
     * <p>Only the two states a stand-in can tell apart. Everything else a copper golem does -
     * opening a chest, dropping an item - is driven by state the server never sends here, so
     * guessing at it would be worse than leaving it out.
     */
    private static final Map<String, String[]> MOTION = new LinkedHashMap<>();

    static {
        MOTION.put("copper_golem", new String[]{"copper_golem_walk", "copper_golem_idle"});
        MOTION.put("creaking", new String[]{"creaking_walk", null});
        MOTION.put("creaking_transient", new String[]{"creaking_walk", null});
        MOTION.put("nautilus", new String[]{"swimming", "swimming"});
        MOTION.put("zombie_nautilus", new String[]{"swimming", "swimming"});
    }

    /**
     * Mobs with geometry of their own, and the texture each one wears.
     *
     * <p>The rest of the new mobs are not here on purpose: a parched is a skeleton with a
     * different skin and a camel husk is a camel with one, so both already draw correctly
     * through their stand-in and need nothing from this.
     */
    private static final Map<String, String> TEXTURES = new LinkedHashMap<>();

    static {
        TEXTURES.put("copper_golem", "textures/entity/copper_golem/copper_golem.png");
        TEXTURES.put("creaking", "textures/entity/creaking/creaking.png");
        TEXTURES.put("creaking_transient", "textures/entity/creaking/creaking.png");
        TEXTURES.put("happy_ghast", "textures/entity/ghast/happy_ghast.png");
        TEXTURES.put("nautilus", "textures/entity/nautilus/nautilus.png");
        TEXTURES.put("zombie_nautilus", "textures/entity/nautilus/zombie_nautilus.png");
        TEXTURES.put("sulfur_cube", "textures/entity/sulfur_cube/sulfur_cube_outer.png");
    }

    /**
     * The layer drawn over a mob, and the skin it wears.
     *
     * <p>Eyes glow, coral grows on a drowned nautilus, a sulfur cube has a core inside its
     * shell. All of them are always there, which is why they can be drawn without asking the
     * server anything.
     *
     * <p>Armour, saddles and harnesses are not here. They depend on what the mob is wearing,
     * which is state the server does not send, and drawing a saddle on every nautilus would
     * be worse than drawing none. The geometry and the skins are in the jar for when it does.
     */
    private static final Map<String, String> OVERLAY = new LinkedHashMap<>();

    static {
        OVERLAY.put("copper_golem", "textures/entity/copper_golem/copper_golem_eyes.png");
        OVERLAY.put("creaking", "textures/entity/creaking/creaking_eyes.png");
        OVERLAY.put("creaking_transient", "textures/entity/creaking/creaking_eyes.png");
        OVERLAY.put("zombie_nautilus", "textures/entity/nautilus/zombie_nautilus_coral.png");
        OVERLAY.put("sulfur_cube", "textures/entity/sulfur_cube/sulfur_cube_inner.png");
    }

    private static final Map<String, ModelLayerLocation> OVERLAY_LAYERS = new LinkedHashMap<>();

    /** Stand-in entity id to the mob it is standing in for. */
    private static final Map<Integer, String> WEARING = new ConcurrentHashMap<>();

    private static final Map<String, ModelLayerLocation> LAYERS = new LinkedHashMap<>();

    /** Which layer in the geometry file each overlay comes from. */
    private static final Map<String, String> OVERLAY_SUFFIX = new LinkedHashMap<>();

    static {
        OVERLAY_SUFFIX.put("copper_golem", ".eyes");
        OVERLAY_SUFFIX.put("creaking", ".eyes");
        OVERLAY_SUFFIX.put("creaking_transient", ".eyes");
        OVERLAY_SUFFIX.put("zombie_nautilus", ".coral");
        OVERLAY_SUFFIX.put("sulfur_cube", ".inner");
    }

    private HoldMobs() {
    }

    /** Handles one message. Returns true when it was ours. */
    public static boolean handle(String payload) {
        if (payload == null || !payload.startsWith(PREFIX + "|")) {
            return false;
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length < 3) {
            return true;
        }
        try {
            int id = Integer.parseInt(parts[1]);
            String mob = parts[2];
            if (mob.isEmpty() || mob.equals("-")) {
                WEARING.remove(id);
            } else if (TEXTURES.containsKey(mob)) {
                WEARING.put(id, mob);
            }
            // A mob with no geometry of its own is left alone deliberately. Its stand-in is
            // already the right shape wearing the right skin.
        } catch (NumberFormatException ignored) {
        }
        return true;
    }

    /** Called from the client entry point. */
    public static void registerClient() {
        for (String mob : TEXTURES.keySet()) {
            ModelLayerLocation layer = new ModelLayerLocation(
                    ResourceLocation.fromNamespaceAndPath(HoldBlocks.NAMESPACE, mob), "main");
            LAYERS.put(mob, layer);
            EntityModelLayerRegistry.registerModelLayer(layer,
                    () -> HoldGeometry.layer(GEOMETRY, mob));
        }
        for (String mob : OVERLAY.keySet()) {
            ModelLayerLocation layer = new ModelLayerLocation(
                    ResourceLocation.fromNamespaceAndPath(HoldBlocks.NAMESPACE, mob), "overlay");
            OVERLAY_LAYERS.put(mob, layer);
            String named = mob + OVERLAY_SUFFIX.getOrDefault(mob, ".eyes");
            EntityModelLayerRegistry.registerModelLayer(layer,
                    () -> HoldGeometry.layer(GEOMETRY, named));
        }
        // Replaces the stand-in's own renderer. Anything not standing in for something is
        // drawn exactly as it was, by the renderer this extends.
        EntityRendererRegistry.register(EntityType.MAGMA_CUBE, Standin::new);
    }

    /**
     * Draws a stand-in as whatever it is standing in for.
     *
     * <p>Extends the renderer it replaces rather than reimplementing it, so a real magma cube
     * on this server still looks like a magma cube. Only the ones the server has named are
     * drawn as something else.
     */
    private static final class Standin extends MagmaCubeRenderer {

        private final Map<String, ModelPart> models = new LinkedHashMap<>();
        private final Map<String, ModelPart> overlays = new LinkedHashMap<>();

        Standin(EntityRendererProvider.Context context) {
            super(context);
            for (var e : LAYERS.entrySet()) {
                try {
                    ModelPart baked = context.bakeLayer(e.getValue());
                    HoldGeometry.applyScale(GEOMETRY, e.getKey(), baked);
                    models.put(e.getKey(), baked);
                    ModelLayerLocation over = OVERLAY_LAYERS.get(e.getKey());
                    if (over != null) {
                        overlays.put(e.getKey(), context.bakeLayer(over));
                    }
                } catch (Exception ex) {
                    System.err.println("[Nan0UI] No baked model for " + e.getKey() + ": " + ex);
                }
            }
        }

        @Override
        public void render(MagmaCube entity, float yaw, float partialTick, PoseStack pose,
                           MultiBufferSource buffers, int light) {
            String mob = WEARING.get(entity.getId());
            ModelPart model = mob == null ? null : models.get(mob);
            if (model == null) {
                super.render(entity, yaw, partialTick, pose, buffers, light);
                return;
            }

            pose.pushPose();
            // The order vanilla uses, taken from LivingEntityRenderer rather than reasoned
            // about: turn to face, then flip, then drop by 1.501. Doing the move first and
            // the turn last, as this did, leaves the model lying on its face beside itself.
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(
                    180.0F - net.minecraft.util.Mth.rotLerp(partialTick,
                            entity.yBodyRotO, entity.yBodyRot)));
            pose.scale(-1.0F, -1.0F, 1.0F);
            pose.translate(0.0F, -1.501F, 0.0F);

            animate(mob, model, entity, partialTick);

            ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(
                    HoldBlocks.NAMESPACE, TEXTURES.get(mob));
            VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
            model.render(pose, buffer, light, OverlayTexture.NO_OVERLAY);

            // The layer on top: glowing eyes, coral, the core inside a sulfur cube. Drawn with
            // the same pose, so it follows the animation rather than sitting still while the
            // body underneath moves.
            ModelPart over = overlays.get(mob);
            if (over != null) {
                copyPose(model, over);
                ResourceLocation skin = ResourceLocation.fromNamespaceAndPath(
                        HoldBlocks.NAMESPACE, OVERLAY.get(mob));
                over.render(pose, buffers.getBuffer(RenderType.entityTranslucent(skin)),
                        light, OverlayTexture.NO_OVERLAY);
            }
            pose.popPose();
        }

        /** Puts the overlay in the same pose as the body it sits on. */
        private static void copyPose(ModelPart from, ModelPart to) {
            to.copyFrom(from);
            for (var name : NAMES) {
                try {
                    to.getChild(name).copyFrom(from.getChild(name));
                } catch (Exception ignored) {
                    // Not a part both models have.
                }
            }
        }

        /**
         * Walks it or stands it still, from how far it moved since last tick.
         *
         * <p>Measured rather than asked, because the stand-in is teleported to follow the real
         * mob and never walks anywhere itself, so everything the game would normally track
         * about its movement stays at zero.
         */
        private static void animate(String mob, ModelPart model, MagmaCube entity,
                                    float partialTick) {
            String[] states = MOTION.get(mob);
            if (states == null) {
                return;
            }
            double dx = entity.getX() - entity.xo;
            double dz = entity.getZ() - entity.zo;
            boolean moving = dx * dx + dz * dz > 1.0E-6;
            String want = moving ? states[0] : states[1];
            if (want == null) {
                return;
            }
            long millis = (long) ((entity.tickCount + partialTick) * 50.0F);
            HoldAnimations.play(model, HoldAnimations.get(want), millis);
        }
    }
}
