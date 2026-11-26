package com.mophars;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.WeakHashMap;

/** Black cube while a dot; otherwise renders a hidden dummy of the morph type with the real mob's renderer. */
@Mod.EventBusSubscriber(modid = Mophars.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class MorpherRenderer extends EntityRenderer<MorpherEntity> {
    static final ResourceLocation TEX = new ResourceLocation(Mophars.ID, "textures/entity/dot.png");
    record Dummy(String id, LivingEntity e) {}
    private final Map<MorpherEntity, Dummy> cache = new WeakHashMap<>();
    private final Map<MorpherEntity, Integer> lastTick = new WeakHashMap<>();

    @SubscribeEvent
    public static void reg(EntityRenderersEvent.RegisterRenderers e) { e.registerEntityRenderer(Mophars.MORPHER.get(), MorpherRenderer::new); }

    public MorpherRenderer(EntityRendererProvider.Context c) { super(c); }

    @Override public ResourceLocation getTextureLocation(MorpherEntity e) { return TEX; }

    private LivingEntity dummy(MorpherEntity e) {
        String id = e.getMorphId();
        if (id.isEmpty()) return null;
        Dummy d = cache.get(e);
        if (d == null || !d.id.equals(id)) {
            EntityType<?> t = MorpherEntity.cur(id);
            LivingEntity n = null;
            try { if (t != null && t.create(Minecraft.getInstance().level) instanceof LivingEntity l) n = l; } catch (Exception ex) { /* fall back to dot */ }
            d = new Dummy(id, n);
            cache.put(e, d);
        }
        return d.e;
    }

    @Override
    public void render(MorpherEntity e, float yaw, float pt, PoseStack ps, MultiBufferSource buf, int light) {
        LivingEntity d = dummy(e);
        if (d != null) {
            d.setPos(e.getX(), e.getY(), e.getZ());
            d.xo = e.xo; d.yo = e.yo; d.zo = e.zo; d.xOld = e.xOld; d.yOld = e.yOld; d.zOld = e.zOld;
            d.setYRot(e.getYRot()); d.yRotO = e.yRotO; d.setXRot(e.getXRot()); d.xRotO = e.xRotO;
            d.yBodyRot = e.yBodyRot; d.yBodyRotO = e.yBodyRotO; d.yHeadRot = e.yHeadRot; d.yHeadRotO = e.yHeadRotO;
            d.tickCount = e.tickCount; d.hurtTime = e.hurtTime; d.hurtDuration = e.hurtDuration; d.deathTime = e.deathTime;
            d.attackAnim = e.attackAnim; d.oAttackAnim = e.oAttackAnim; d.swinging = e.swinging; d.swingTime = e.swingTime;
            d.setPose(e.getPose()); d.setOnGround(e.onGround()); d.setInvisible(e.isInvisible());
            d.setSharedFlagOnFire(e.isOnFire());
            if (d instanceof Mob m) m.setAggressive(e.isAggressive());
            if (!Integer.valueOf(e.tickCount).equals(lastTick.put(e, e.tickCount))) d.walkAnimation.update(e.walkAnimation.speed(), 1f);
            shadowRadius = d.getBbWidth() * 0.5f;
            @SuppressWarnings({"unchecked", "rawtypes"})
            EntityRenderer<LivingEntity> r = (EntityRenderer) entityRenderDispatcher.getRenderer(d);
            r.render(d, yaw, pt, ps, buf, light);
            return;
        }
        shadowRadius = 0.1f;
        VertexConsumer vc = buf.getBuffer(RenderType.entitySolid(TEX));
        ps.pushPose();
        ps.translate(0, 0.15, 0);
        PoseStack.Pose p = ps.last();
        float s = 0.12f;
        for (int a = 0; a < 3; a++) for (int sg = -1; sg <= 1; sg += 2) {   // 6 faces: axis a, sign sg
            float[][] v = new float[4][3];
            int u = (a + 1) % 3, w = (a + 2) % 3;
            int[][] c = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int i = 0; i < 4; i++) { v[i][a] = sg * s; v[i][u] = c[i][0] * s; v[i][w] = c[i][1] * s; }
            float nx = a == 0 ? sg : 0, ny = a == 1 ? sg : 0, nz = a == 2 ? sg : 0;
            for (int i = 0; i < 4; i++) {
                float[] q = v[sg > 0 ? i : 3 - i];
                vc.vertex(p.pose(), q[0], q[1], q[2]).color(255, 255, 255, 255).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(light).normal(p.normal(), nx, ny, nz).endVertex();
            }
        }
        ps.popPose();
    }
}
