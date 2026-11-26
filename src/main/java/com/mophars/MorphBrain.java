package com.mophars;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/** Shared reinforcement-learning table (tabular Q-learning, shared by every Morpher) + per-mob traits. */
final class MorphBrain {
    static final ResourceLocation DOT = new ResourceLocation(Mophars.ID, "dot");
    static final float ALPHA = 0.2f, GAMMA = 0.8f, EPS = 0.12f;

    record Trait(float hp, float dmg, boolean ranged, boolean water) {
        float power() { return hp + 3 * dmg; }
    }

    /** Ranged attack kinds. ponytail: hand-written table, add modded mobs here if needed. */
    static final Map<String, String> SHOT = Map.of("skeleton", "arrow", "stray", "arrow", "pillager", "arrow",
        "illusioner", "arrow", "piglin", "arrow", "blaze", "small", "ghast", "large");
    static final Set<String> NO_MORPH = Set.of("ender_dragon", "morpher", "player");
    private static final Map<ResourceLocation, Trait> TRAITS = new HashMap<>();

    static AttributeSupplier supplier(EntityType<?> t) {
        return DefaultAttributes.hasSupplier(t) ? DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) t) : null;
    }

    static boolean canMorphInto(EntityType<?> t) {
        ResourceLocation k = ForgeRegistries.ENTITY_TYPES.getKey(t);
        return k != null && t.getCategory() != MobCategory.MISC && supplier(t) != null
            && !(k.getNamespace().equals("minecraft") && NO_MORPH.contains(k.getPath())) && !k.equals(Mophars.MORPHER.getId());
    }

    static Trait trait(ResourceLocation id) {
        return TRAITS.computeIfAbsent(id, k -> {
            EntityType<?> t = ForgeRegistries.ENTITY_TYPES.getValue(k);
            AttributeSupplier s = t == null ? null : supplier(t);
            if (s == null) return new Trait(1, 1, false, false);
            MobCategory c = t.getCategory();
            boolean water = c == MobCategory.WATER_CREATURE || c == MobCategory.WATER_AMBIENT || c == MobCategory.UNDERGROUND_WATER_CREATURE;
            return new Trait((float) s.getBaseValue(Attributes.MAX_HEALTH),
                s.hasAttribute(Attributes.ATTACK_DAMAGE) ? (float) s.getBaseValue(Attributes.ATTACK_DAMAGE) : 0,
                k.getNamespace().equals("minecraft") && SHOT.containsKey(k.getPath()), water);
        });
    }

    static float power(ResourceLocation id) { return id == null || id.equals(DOT) ? 4 : trait(id).power(); }

    static String shot(ResourceLocation id) { return id != null && id.getNamespace().equals("minecraft") ? SHOT.getOrDefault(id.getPath(), "") : ""; }

    static MobEffectInstance hitEffect(ResourceLocation id) {
        if (id == null || !id.getNamespace().equals("minecraft")) return null;
        return switch (id.getPath()) {
            case "cave_spider", "bee" -> new MobEffectInstance(MobEffects.POISON, 140);
            case "husk" -> new MobEffectInstance(MobEffects.HUNGER, 140);
            case "wither_skeleton" -> new MobEffectInstance(MobEffects.WITHER, 200);
            default -> null;
        };
    }

    /** Hand-made prior so a fresh Morpher is not stupid; Q-learning adds the learned residual on top. */
    static float prior(ResourceLocation id, int tgt, int thr, int hpB, ResourceLocation group, boolean water, boolean anyAttack) {
        if (id.equals(DOT)) return anyAttack ? -5 : 0;
        Trait t = trait(id);
        float p = 0;
        if (id.equals(group)) p += (thr > 0 || tgt == 0) ? 3 : 1;      // blend into the herd
        if (tgt > 0) {
            if (t.dmg <= 0 && !t.ranged) p -= 4;                       // harmless form cannot fight
            else p += t.power() * 0.04f;
            p += t.ranged ? (tgt >= 2 ? 1.5f : -0.5f) : (tgt == 1 ? 1f : 0);   // far -> shooter, close -> melee
        } else p += t.hp * 0.01f;
        if (t.water) p += water ? 2 : -2;
        if (hpB == 0) p += t.hp * 0.03f;
        return p;
    }

    // ---------- persistent Q table ----------
    static final class QTable extends SavedData {
        final Map<String, Map<String, Float>> m = new HashMap<>();

        static QTable load(CompoundTag t) {
            QTable q = new QTable();
            for (String s : t.getAllKeys()) {
                Map<String, Float> r = new HashMap<>();
                CompoundTag c = t.getCompound(s);
                for (String a : c.getAllKeys()) r.put(a, c.getFloat(a));
                q.m.put(s, r);
            }
            return q;
        }

        @Override public CompoundTag save(CompoundTag t) {
            m.forEach((s, r) -> { CompoundTag c = new CompoundTag(); r.forEach(c::putFloat); t.put(s, c); });
            return t;
        }

        float get(String s, ResourceLocation a) { return m.getOrDefault(s, Map.of()).getOrDefault(a.toString(), 0f); }

        void update(String s, ResourceLocation a, float r, String s2, Collection<ResourceLocation> next) {
            float best = 0;
            boolean first = true;
            for (ResourceLocation n : next) { float v = get(s2, n); if (first || v > best) { best = v; first = false; } }
            float q = get(s, a);
            m.computeIfAbsent(s, k -> new HashMap<>()).put(a.toString(), q + ALPHA * (r + GAMMA * best - q));
            setDirty();
        }
    }

    private static QTable Q;

    static QTable q(ServerLevel l) {
        if (Q == null) Q = l.getServer().overworld().getDataStorage().computeIfAbsent(QTable::load, QTable::new, "mophars_q");
        return Q;
    }

    static void reset() { Q = null; }
}
