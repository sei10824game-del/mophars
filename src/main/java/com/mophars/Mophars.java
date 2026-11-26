package com.mophars;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(Mophars.ID)
public class Mophars {
    public static final String ID = "mophars";
    static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ID);
    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);

    public static final RegistryObject<EntityType<MorpherEntity>> MORPHER = ENTITIES.register("morpher",
        () -> EntityType.Builder.of(MorpherEntity::new, MobCategory.MONSTER).sized(0.3f, 0.3f)
            .clientTrackingRange(10).updateInterval(2).build("morpher"));
    public static final RegistryObject<Item> EGG = ITEMS.register("morpher_spawn_egg",
        () -> new ForgeSpawnEggItem(MORPHER, 0x000000, 0x4a4a4a, new Item.Properties()));

    public Mophars() {
        IEventBus b = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(b);
        ITEMS.register(b);
        b.addListener((EntityAttributeCreationEvent e) -> e.put(MORPHER.get(), MorpherEntity.attributes().build()));
        b.addListener((SpawnPlacementRegisterEvent e) -> e.register(MORPHER.get(), SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (t, l, r, p, rnd) -> Mob.checkMobSpawnRules(t, l, r, p, rnd),
            SpawnPlacementRegisterEvent.Operation.REPLACE));
        b.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.SPAWN_EGGS) e.accept(EGG);
        });
        MinecraftForge.EVENT_BUS.register(new MorphEvents());
    }
}
