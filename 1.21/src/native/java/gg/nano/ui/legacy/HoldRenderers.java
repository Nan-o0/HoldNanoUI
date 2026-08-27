package gg.nano.ui.legacy;

import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the two kinds of block the model system cannot draw on its own.
 *
 * <p>A chest and a copper golem statue have a blockstate file that names one model and no
 * variants, because in 26.2 neither is drawn by the model system at all - a renderer draws
 * them, reading the direction and the pose off the block state. Registering them as ordinary
 * blocks gets the right shape in the world and a chest that always faces north, which is worse
 * than obvious: it looks almost right.
 *
 * <p>So they get renderers here too, from the same geometry 26.2 uses.
 */
public final class HoldRenderers {

    private static final String STATUE_GEOMETRY = "/holdsmp-statue.json";

    private static BlockEntityType<ChestBlockEntity> chestType;
    private static BlockEntityType<StatueBlockEntity> statueType;

    private static final List<Block> CHESTS = new ArrayList<>();
    private static final List<Block> STATUES = new ArrayList<>();

    private HoldRenderers() {
    }

    static void noteChest(Block block) {
        CHESTS.add(block);
    }

    static void noteStatue(Block block) {
        STATUES.add(block);
    }

    /**
     * The block entity type every copper chest shares.
     *
     * <p>Built on first use rather than up front, because ChestBlock asks for it from inside
     * its own constructor and the type cannot exist until the blocks it covers do.
     */
    public static synchronized BlockEntityType<ChestBlockEntity> chestType() {
        if (chestType == null) {
            chestType = BlockEntityType.Builder
                    .of(ChestBlockEntity::new, CHESTS.toArray(new Block[0]))
                    .build(null);
        }
        return chestType;
    }

    /** Registers the block entity types. Must run while registries are still open. */
    public static void registerTypes() {
        if (!CHESTS.isEmpty()) {
            Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    ResourceLocation.fromNamespaceAndPath(HoldBlocks.NAMESPACE, "chest"),
                    chestType());
        }
        if (!STATUES.isEmpty()) {
            statueType = BlockEntityType.Builder
                    .of(StatueBlockEntity::new, STATUES.toArray(new Block[0]))
                    .build(null);
            Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    ResourceLocation.fromNamespaceAndPath(HoldBlocks.NAMESPACE, "statue"),
                    statueType);
        }
    }

    /** A statue holds nothing of its own; its pose and facing are block state. */
    public static final class StatueBlockEntity extends BlockEntity {
        public StatueBlockEntity(BlockPos pos, BlockState state) {
            super(statueType, pos, state);
        }
    }

    // ----- client -----

    private static final String[] POSES = {"standing", "sitting", "running", "star"};
    private static final ModelLayerLocation[] STATUE_LAYERS = new ModelLayerLocation[4];

    /** Called from the client entry point, after the blocks are registered. */
    public static void registerClient() {
        for (int i = 0; i < POSES.length; i++) {
            final String pose = POSES[i];
            STATUE_LAYERS[i] = new ModelLayerLocation(
                    ResourceLocation.fromNamespaceAndPath(HoldBlocks.NAMESPACE,
                            "copper_golem_statue"), pose);
            EntityModelLayerRegistry.registerModelLayer(STATUE_LAYERS[i],
                    () -> HoldGeometry.layer(STATUE_GEOMETRY, pose));
        }
        if (statueType != null) {
            BlockEntityRendererRegistry.register(statueType, StatueRenderer::new);
        }
    }

    /**
     * Draws a copper golem statue in the pose and direction its block state says.
     *
     * <p>Texture picked from the name, because the eight statues are one shape with four
     * stages of oxidation and a waxed copy of each, and the waxed one looks identical to the
     * unwaxed one at the same stage.
     */
    private static final class StatueRenderer implements BlockEntityRenderer<StatueBlockEntity> {

        private final ModelPart[] byPose = new ModelPart[POSES.length];

        StatueRenderer(BlockEntityRendererProvider.Context context) {
            for (int i = 0; i < POSES.length; i++) {
                byPose[i] = context.bakeLayer(STATUE_LAYERS[i]);
            }
        }

        @Override
        public void render(StatueBlockEntity entity, float partialTick, PoseStack pose,
                           MultiBufferSource buffers, int light, int overlay) {
            BlockState state = entity.getBlockState();
            ModelPart model = byPose[poseIndex(state)];

            pose.pushPose();
            // Block models are drawn from the corner and entity models from the middle of the
            // block, one block up and upside down. This is the same transform every vanilla
            // block entity renderer applies for the same reason.
            pose.translate(0.5F, 1.5F, 0.5F);
            pose.scale(1.0F, -1.0F, -1.0F);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(facing(state)));

            ResourceLocation texture = textureFor(entity);
            VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutout(texture));
            model.render(pose, buffer, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }

        private static int poseIndex(BlockState state) {
            for (var property : state.getProperties()) {
                if (property.getName().equals("pose")) {
                    String value = state.getValue(property).toString()
                            .toLowerCase(java.util.Locale.ROOT);
                    for (int i = 0; i < POSES.length; i++) {
                        if (POSES[i].equals(value)) {
                            return i;
                        }
                    }
                }
            }
            return 0;
        }

        private static float facing(BlockState state) {
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                Direction direction = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                return -direction.toYRot();
            }
            return 0.0F;
        }

        private static ResourceLocation textureFor(StatueBlockEntity entity) {
            String id = BuiltInRegistries.BLOCK.getKey(
                    entity.getBlockState().getBlock()).getPath();
            String stage = "copper_golem";
            if (id.contains("exposed")) {
                stage = "copper_golem_exposed";
            } else if (id.contains("weathered")) {
                stage = "copper_golem_weathered";
            } else if (id.contains("oxidized")) {
                stage = "copper_golem_oxidized";
            }
            return ResourceLocation.fromNamespaceAndPath(HoldBlocks.NAMESPACE,
                    "textures/entity/copper_golem/" + stage + ".png");
        }
    }
}
