package com.mophars;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class MorphEvents {
    /** Every attack-capable mob gets a goal: hunt a morpher that is a dot, or one that already angered it. */
    @SubscribeEvent
    public void join(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide || !(e.getEntity() instanceof Mob m) || m instanceof MorpherEntity) return;
        if (m instanceof Enemy || m.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE))   // anything that can attack
            m.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(m, MorpherEntity.class, 10, false, false,
                le -> le instanceof MorpherEntity x && x.hates(m)));
    }

    @SubscribeEvent
    public void retarget(LivingChangeTargetEvent e) {
        if (e.getNewTarget() instanceof MorpherEntity x && e.getEntity() instanceof Mob m && !(m instanceof MorpherEntity)) x.noteHated(m);
    }

    @SubscribeEvent
    public void attack(LivingAttackEvent e) {   // morphers are allies
        if (e.getEntity() instanceof MorpherEntity && e.getSource().getEntity() instanceof MorpherEntity) e.setCanceled(true);
    }

    @SubscribeEvent
    public void hurt(LivingHurtEvent e) {
        Entity src = e.getSource().getEntity();
        if (src instanceof MorpherEntity m && !(e.getEntity() instanceof MorpherEntity)) {
            m.reward += e.getAmount() * 0.3f;
            if (e.getEntity() instanceof Mob v && (v instanceof Enemy || v.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE))) v.setTarget(m);
        }
        if (e.getEntity() instanceof MorpherEntity m) m.reward -= e.getAmount() * 0.3f;
    }

    @SubscribeEvent
    public void death(LivingDeathEvent e) {
        LivingEntity v = e.getEntity();
        if (v instanceof MorpherEntity m && m.onFatal(e.getSource())) { e.setCanceled(true); return; }
        if (e.getSource().getEntity() instanceof MorpherEntity k && !(v instanceof MorpherEntity) && v instanceof Mob && MorphBrain.canMorphInto(v.getType()))
            k.addSlot(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(v.getType()));
    }

    @SubscribeEvent
    public void stop(ServerStoppedEvent e) { MorphBrain.reset(); }
}
