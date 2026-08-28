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

    /** Message this handles: {@code MB|entityId|mob} , or {@code MB|entityId|-} to forget one. */
    public static final String PREFIX = "MB";

    private static final String GEOMETRY = "/holdsmp-mobs.json";

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

    /** Stand-in entity id to the mob it is standing in for. */
    private static final Map<Integer, String> WEARING = new ConcurrentHashMap<>();

    private static final Map<String, ModelLayerLocation> LAYERS = new LinkedHashMap<>();

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

        Standin(EntityRendererProvider.Context context) {
            super(context);
            for (var e : LAYERS.entrySet()) {
                try {
                    models.put(e.getKey(), context.bakeLayer(e.getValue()));
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

            ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(
                    HoldBlocks.NAMESPACE, TEXTURES.get(mob));
            VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
            model.render(pose, buffer, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }
}
