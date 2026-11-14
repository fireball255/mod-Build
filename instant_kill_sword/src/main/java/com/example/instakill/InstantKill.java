package com.example.instakill;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(InstantKill.MODID)
public class InstantKill {

    public static final String MODID = "instakill";

    /** 物品注册表 */
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    /**
     * 外观等同原版钻石剑的"见谁秒谁"之剑。
     * Tiers.DIAMOND：攻击力 7、基础耐久 1561。
     * 攻击伤害加成 0、攻击速度 -2.4（与原版钻石剑一致）。
     */
    public static final RegistryObject<Item> INSTANT_KILL_SWORD =
            ITEMS.register("instant_kill_sword",
                    () -> new InstantKillSword(Tiers.DIAMOND, 0, -2.4F,
                            new Item.Properties()));

    public InstantKill() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册物品到 Forge 注册表
        ITEMS.register(modEventBus);

        // 把剑放进原版"战斗"创造模式标签页
        modEventBus.addListener(this::addToCreativeTab);

        MinecraftForge.EVENT_BUS.register(this);

        // 每 20 tick 清理一次弱引用缓存，避免被处理过的实体引用长期滞留（内存泄露清理）
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
    }

    /** 简单的内存泄露清理：定期清掉已移除 / 已被回收的实体弱引用 */
    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.getServer().getTickCount() % 20 == 0) {
            InstantKillSword.cleanupStaleReferences();
        }
    }

    /** 添加到"战斗"（Combat）标签页，方便在创造模式里拿 */
    private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(INSTANT_KILL_SWORD);
        }
    }
}
