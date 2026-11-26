package com.mophars;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.*;

public class MorpherEntity extends PathfinderMob {
    static final EntityDataAccessor<String> MORPH = SynchedEntityData.defineId(MorpherEntity.class, EntityDataSerializers.STRING);
    static final TicketType<ChunkPos> TICKET = TicketType.create("mophars", Comparator.comparingLong(ChunkPos::toLong), 100);
    static final Attribute[] STATS = {Attributes.MAX_HEALTH, Attributes.ATTACK_DAMAGE, Attributes.MOVEMENT_SPEED, Attributes.ARMOR,
        Attributes.ARMOR_TOUGHNESS, Attributes.ATTACK_KNOCKBACK, Attributes.KNOCKBACK_RESISTANCE};

    /** Morph slots: mob type -> stored health of that form. */
    final LinkedHashMap<ResourceLocation, Float> slots = new LinkedHashMap<>();
    /** Mobs that already turned hostile: they stay hostile even after I change shape. */
    final Set<UUID> hatedBy = new HashSet<>();
    @Nullable ResourceLocation cur;   // null = black dot
    int lastSwitch, summonCd, atkCd, fuse;
    String lastState;
    ResourceLocation lastAct;
    float reward;

    public MorpherEntity(EntityType<? extends PathfinderMob> t, Level l) {
        super(t, l);
        setPersistenceRequired();
    }

    static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 1).add(Attributes.ATTACK_DAMAGE, 1)
            .add(Attributes.MOVEMENT_SPEED, 0.3).add(Attributes.FOLLOW_RANGE, 64).add(Attributes.ARMOR, 0)
            .add(Attributes.ARMOR_TOUGHNESS, 0).add(Attributes.ATTACK_KNOCKBACK, 0);
    }

    @Override protected void defineSynchedData() { super.defineSynchedData(); entityData.define(MORPH, ""); }
    public String getMorphId() { return entityData.get(MORPH); }
    @Override public boolean removeWhenFarAway(double d) { return false; }
    @Override public boolean requiresCustomPersistence() { return true; }

    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> k) {
        if (MORPH.equals(k)) refreshDimensions();
        super.onSyncedDataUpdated(k);
    }

    @Override public EntityDimensions getDimensions(Pose p) {
        EntityType<?> t = cur(getMorphId());
        return t != null ? t.getDimensions() : super.getDimensions(p);
    }

    static EntityType<?> cur(String id) {
        return id.isEmpty() ? null : ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
    }

    @Override public boolean canBreatheUnderwater() { return cur != null && MorphBrain.trait(cur).water(); }
    @Override public boolean fireImmune() { EntityType<?> t = cur(getMorphId()); return t != null ? t.fireImmune() : super.fireImmune(); }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        // run from things that outclass me
        goalSelector.addGoal(1, new AvoidEntityGoal<>(this, Mob.class, 24f, 1.2, 1.5, e ->
            e instanceof Mob m && !(m instanceof MorpherEntity) && m.getTarget() == this
                && MorphBrain.power(ForgeRegistries.ENTITY_TYPES.getKey(m.getType())) > MorphBrain.power(cur) * 1.5f && getHealth() < getMaxHealth() * 0.5f));
        goalSelector.addGoal(2, new FightGoal());
        targetSelector.addGoal(1, new HurtByTargetGoal(this, MorpherEntity.class).setAlertOthers());
    }

    // ---------------- forms ----------------
    void morph(@Nullable ResourceLocation to) {
        if (cur != null && slots.containsKey(cur)) slots.put(cur, getHealth());
        cur = to;
        lastSwitch = tickCount;
        entityData.set(MORPH, to == null ? "" : to.toString());
        AttributeSupplier s = to == null ? null : MorphBrain.supplier(ForgeRegistries.ENTITY_TYPES.getValue(to));
        for (Attribute a : STATS) {
            AttributeInstance i = getAttribute(a);
            if (i == null) continue;
            double v = s == null ? (a == Attributes.MAX_HEALTH || a == Attributes.ATTACK_DAMAGE ? 1 : a == Attributes.MOVEMENT_SPEED ? 0.3 : 0)
                : s.hasAttribute(a) ? s.getBaseValue(a) : a == Attributes.MOVEMENT_SPEED ? 0.25 : 0;
            i.setBaseValue(v);
        }
        getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64);
        setHealth(to == null ? 1 : Math.min(slots.getOrDefault(to, getMaxHealth()), getMaxHealth()));
    }

    void addSlot(ResourceLocation id) {
        if (slots.containsKey(id)) return;
        slots.put(id, MorphBrain.trait(id).hp());
        reward += 5;
    }

    /** Called from LivingDeathEvent. true = death cancelled (form lost / dot revived). */
    boolean onFatal(DamageSource src) {
        if (src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        if (cur != null) {
            slots.remove(cur);
            reward -= 5;
            cur = null;
            morphBest();
        } else if (!slots.isEmpty()) morphBest();   // dot died while holding slots: fall back to a slot
        else return false;
        invulnerableTime = 20;
        return true;
    }

    private void morphBest() {
        ResourceLocation best = null;
        for (ResourceLocation k : slots.keySet()) if (best == null || MorphBrain.power(k) > MorphBrain.power(best)) best = k;
        morph(best);
    }

    boolean hates(Mob m) {
        return isAlive() && (cur == null || hatedBy.contains(m.getUUID()));
    }

    void noteHated(Mob m) {
        if (hatedBy.size() > 256) hatedBy.clear();
        hatedBy.add(m.getUUID());
    }

    // ---------------- ticking / brain ----------------
    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel sl)) return;
        if (tickCount % 60 == 0) {   // keep chunks around me ticking, even if no player is near
            ChunkPos cp = chunkPosition();
            sl.getChunkSource().addRegionTicket(TICKET, cp, 3, cp);
        }
        if (tickCount % 100 == 0) {
            heal(1);
            slots.replaceAll((k, v) -> Math.min(v + 1, MorphBrain.trait(k).hp()));
        }
        if (tickCount % 20 == 0) think(sl);
        if (atkCd > 0) atkCd--;
    }

    private void think(ServerLevel sl) {
        List<LivingEntity> near = sl.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(96, 48, 96), e -> e != this && e.isAlive());
        int thr = 0;
        float allyPower = 0;
        int allies = 0;
        Map<ResourceLocation, Integer> grp = new HashMap<>();
        for (LivingEntity e : near) {
            if (e instanceof MorpherEntity o) {
                allies++;
                if (distanceToSqr(o) < 32 * 32) allyPower += MorphBrain.power(o.cur);
                if (getTarget() != null && o.getTarget() == null && o.distanceToSqr(getTarget()) < 100 * 100) o.setTarget(getTarget()); // teamwork
            } else if (e instanceof Mob m && distanceToSqr(m) < 32 * 32) {
                if (m.getTarget() == this) thr++;
                grp.merge(ForgeRegistries.ENTITY_TYPES.getKey(m.getType()), 1, Integer::sum);
            }
        }
        // --- pick prey: a type I still miss and can beat (together with my allies)
        LivingEntity tg = getTarget();
        if (tg != null && (!tg.isAlive() || distanceToSqr(tg) > 128 * 128)) { setTarget(null); tg = null; }
        if (tg == null) {
            float best = 0;
            for (ResourceLocation k : slots.keySet()) if (MorphBrain.trait(k).dmg() > 0 || MorphBrain.trait(k).ranged()) best = Math.max(best, MorphBrain.power(k));
            float mine = Math.max(4, best) + allyPower;
            double bd = 1e18;
            for (LivingEntity e : near) {
                if (!(e instanceof Mob m) || m instanceof MorpherEntity || !MorphBrain.canMorphInto(m.getType())) continue;
                ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(m.getType());
                if (slots.containsKey(id) || m.hasCustomName() || m.isLeashed() || (m instanceof TamableAnimal t && t.isTame())) continue;
                if (MorphBrain.power(id) > mine * 1.5f + 6) continue;
                double d = distanceToSqr(m);
                if (d < bd) { bd = d; tg = m; }
            }
            if (tg != null) setTarget(tg);
        }
        if (tg == null && getNavigation().isDone() && random.nextInt(3) == 0) {   // roam far to look for prey
            Vec3 p = LandRandomPos.getPos(this, 40, 12);
            if (p != null) getNavigation().moveTo(p.x, p.y, p.z, 1.0);
        }

        // --- reinforcement learning over form choice
        ResourceLocation group = null;
        int gc = 1;
        for (ResourceLocation k : slots.keySet()) if (grp.getOrDefault(k, 0) > gc) { gc = grp.get(k); group = k; }
        double d = tg == null ? 0 : Math.sqrt(distanceToSqr(tg));
        int tgt = tg == null ? 0 : d < 5 ? 1 : d < 16 ? 2 : 3;
        float hr = getHealth() / getMaxHealth();
        int hpB = hr < .35f ? 0 : hr < .7f ? 1 : 2;
        boolean water = isInWater();
        int dim = level().dimension() == Level.OVERWORLD ? 0 : level().dimension() == Level.NETHER ? 1 : level().dimension() == Level.END ? 2 : 3;
        String state = tgt + "|" + Math.min(thr, 2) + "|" + hpB + "|" + (group != null ? 1 : 0) + "|" + (dim * 2 + (water ? 1 : 0));
        boolean anyAttack = false;
        for (ResourceLocation k : slots.keySet()) { var t = MorphBrain.trait(k); anyAttack |= t.dmg() > 0 || t.ranged(); }
        List<ResourceLocation> cands = new ArrayList<>(slots.keySet());
        if (tgt > 0 && !anyAttack && !slots.isEmpty()) cands.add(MorphBrain.DOT);   // no weapon in my slots: fight as the dot
        if (cands.isEmpty()) return;
        var q = MorphBrain.q(sl);
        if (lastState != null && lastAct != null) {
            q.update(lastState, lastAct, reward + (thr == 0 ? 0.1f : -0.1f * thr), state, cands);
            reward = 0;
        }
        ResourceLocation pick = null;
        float bs = -1e9f;
        if (random.nextFloat() < MorphBrain.EPS) pick = cands.get(random.nextInt(cands.size()));
        else for (ResourceLocation c : cands) {
            float s = q.get(state, c) + MorphBrain.prior(c, tgt, thr, hpB, group, water, anyAttack) + (c.equals(cur == null ? MorphBrain.DOT : cur) ? 0.3f : 0);
            if (s > bs) { bs = s; pick = c; }
        }
        ResourceLocation now = cur == null ? MorphBrain.DOT : cur;
        if (!pick.equals(now) && (tickCount - lastSwitch >= 60 || !cands.contains(now))) {
            morph(pick.equals(MorphBrain.DOT) ? null : pick);
            now = pick;
        }
        lastState = state;
        lastAct = now;

        // --- summon: spend a slot to call a morpher already in that form
        summonCd = Math.max(0, summonCd - 20);
        if (summonCd == 0 && slots.size() >= 2 && (thr >= 2 || (tgt > 0 && tgt <= 2 && hr < 0.8f)) && allies < 10) {
            ResourceLocation s = null;
            for (ResourceLocation k : slots.keySet()) if (!k.equals(cur) && (s == null || MorphBrain.power(k) > MorphBrain.power(s))) s = k;
            MorpherEntity c = Mophars.MORPHER.get().create(sl);
            if (s != null && c != null) {
                slots.remove(s);
                c.moveTo(getX() + random.nextInt(3) - 1, getY(), getZ() + random.nextInt(3) - 1, getYRot(), 0);
                c.slots.put(s, MorphBrain.trait(s).hp());
                c.morph(s);
                c.setTarget(tg);
                sl.addFreshEntity(c);
                summonCd = 600;
            }
        }
    }

    // ---------------- combat ----------------
    @Override public boolean doHurtTarget(Entity t) {
        boolean r = super.doHurtTarget(t);
        if (r && t instanceof LivingEntity l && MorphBrain.hitEffect(cur) != null) l.addEffect(MorphBrain.hitEffect(cur), this);
        return r;
    }

    void go(Vec3 to, double speed) {   // far targets: walk toward a waypoint, pathfinder range is limited
        Vec3 d = to.subtract(position());
        if (d.length() > 40) {
            Vec3 w = position().add(d.normalize().scale(32));
            BlockPos b = level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(w));
            to = new Vec3(w.x, b.getY(), w.z);
        }
        getNavigation().moveTo(to.x, to.y, to.z, speed);
    }

    class FightGoal extends Goal {
        FightGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { LivingEntity t = getTarget(); return t != null && t.isAlive(); }
        @Override public void stop() { setAggressive(false); fuse = 0; getNavigation().stop(); }
        @Override public void tick() {
            LivingEntity t = getTarget();
            if (t == null) return;
            setAggressive(true);
            getLookControl().setLookAt(t, 30, 30);
            double d = distanceToSqr(t);
            String shot = MorphBrain.shot(cur);
            boolean creeper = cur != null && cur.getPath().equals("creeper") && cur.getNamespace().equals("minecraft");
            if (!shot.isEmpty()) {
                if (d > 14 * 14 || !hasLineOfSight(t)) go(t.position(), 1.0);
                else if (d < 6 * 6) { Vec3 p = LandRandomPos.getPosAway(MorpherEntity.this, 8, 4, t.position()); if (p != null) getNavigation().moveTo(p.x, p.y, p.z, 1.2); }
                else getNavigation().stop();
                if (atkCd <= 0 && d < 24 * 24 && hasLineOfSight(t)) { shoot(t, shot); atkCd = shot.equals("arrow") ? 40 : 60; }
            } else if (creeper) {
                go(t.position(), 1.1);
                fuse = d < 9 ? fuse + 1 : 0;
                if (fuse > 30) {
                    level().explode(MorpherEntity.this, getX(), getY(), getZ(), 3f, Level.ExplosionInteraction.MOB);
                    kill();
                }
            } else {
                go(t.position(), 1.2);
                double reach = getBbWidth() * 2 * getBbWidth() * 2 + t.getBbWidth();
                if (atkCd <= 0 && d <= reach && getAttributeValue(Attributes.ATTACK_DAMAGE) > 0) {
                    swing(InteractionHand.MAIN_HAND);
                    doHurtTarget(t);
                    atkCd = 20;
                }
            }
        }
    }

    private void shoot(LivingEntity t, String kind) {
        double dx = t.getX() - getX(), dy = t.getY(0.5) - getEyeY(), dz = t.getZ() - getZ();
        Projectile p;
        switch (kind) {
            case "small" -> p = new SmallFireball(level(), this, dx, dy, dz);
            case "large" -> p = new LargeFireball(level(), this, dx, dy, dz, 1);
            default -> {
                Arrow a = new Arrow(level(), this);
                a.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * 0.2, dz, 1.6f, 6f);
                p = a;
            }
        }
        p.setPos(getX(), getEyeY() - 0.1, getZ());
        level().playSound(null, blockPosition(), kind.equals("arrow") ? SoundEvents.SKELETON_SHOOT : SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 1f, 1f);
        swing(InteractionHand.MAIN_HAND);
        level().addFreshEntity(p);
    }

    // ---------------- persistence ----------------
    @Override public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t);
        if (cur != null) slots.put(cur, getHealth());
        ListTag l = new ListTag();
        slots.forEach((k, v) -> { CompoundTag c = new CompoundTag(); c.putString("id", k.toString()); c.putFloat("hp", v); l.add(c); });
        t.put("Slots", l);
        t.putString("Cur", cur == null ? "" : cur.toString());
    }

    @Override public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t);
        slots.clear();
        for (Tag x : t.getList("Slots", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            slots.put(new ResourceLocation(c.getString("id")), c.getFloat("hp"));
        }
        String c = t.getString("Cur");
        cur = c.isEmpty() ? null : new ResourceLocation(c);
        entityData.set(MORPH, c);
    }
}
