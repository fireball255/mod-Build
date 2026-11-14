package com.example.instakill;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.level.Level;

/**
 * 外观完全等同原版钻石剑的「测试剑」。
 *
 * 攻击模式（Shift + 右键循环切换，模式保存在剑自身 NBT 中）：
 *   1. SET_HEALTH — 只调 setHealth(0)，不走 die()，无掉落/无死亡消息。
 *   2. DIE        — 只调 die()，完整死亡流程（掉落、战利品、击杀统计）。
 *   3. REMOVE     — 只调 remove(RemovalReason.KILLED)，直接移出世界，无掉落。
 *   4. NBT        — 调 NBTkill()：复制实体 NBT → 清空血量/背包/状态 → 强行覆写。
 *
 * 范围攻击：普通右键（不按 Shift）对"渲染范围"内所有实体执行当前模式。
 * 内存泄露清理：被处理实体用 WeakReference 跟踪，主类每 20 tick 清理失效引用。
 */
public class InstantKillSword extends SwordItem {

    /** 模式 NBT 键（int，对应 KillMode.ordinal） */
    public static final String MODE_KEY = "instantKillMode";

    /** 范围攻击最大半径（格） */
    private static final double MAX_AOE_RANGE = 64.0D;

    /** 弱引用跟踪被处理过的实体，供定期清理 */
    private static final List<WeakReference<Entity>> TRACKED =
            Collections.synchronizedList(new ArrayList<>());

    /** 四种攻击模式 */
    public enum KillMode {
        SET_HEALTH("改血量 = 0"),
        DIE("die()"),
        REMOVE("remove()"),
        NBT("NBTkill()");

        private final String label;

        KillMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 循环切到下一个模式 */
        public KillMode next() {
            KillMode[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }
    }

    public InstantKillSword(Tier tier, int attackDamageBonus, float attackSpeedModifier, Properties properties) {
        super(tier, attackDamageBonus, attackSpeedModifier, properties);
    }

    /**
     * 无限耐久：覆写为不可损坏。
     * ItemStack.hurtAndBreak 会先检查 isDamageableItem()，返回 false 后
     * 攻击/使用等任何路径都不会消耗耐久，也不会出现耐久条。
     */
    @Override
    public boolean isDamageable(ItemStack stack) {
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* 模式读写                                                            */
    /* ------------------------------------------------------------------ */

    private static KillMode getMode(ItemStack stack) {
        KillMode[] values = KillMode.values();
        return values[Math.floorMod(stack.getOrCreateTag().getInt(MODE_KEY), values.length)];
    }

    private static void setMode(ItemStack stack, KillMode mode) {
        stack.getOrCreateTag().putInt(MODE_KEY, mode.ordinal());
    }

    /* ------------------------------------------------------------------ */
    /* 弱引用跟踪与清理                                                     */
    /* ------------------------------------------------------------------ */

    private static void track(Entity entity) {
        TRACKED.add(new WeakReference<>(entity));
    }

    /** 清理失效引用：被 GC 回收或已从世界移除的条目直接丢弃（主类每 20 tick 调用） */
    public static void cleanupStaleReferences() {
        synchronized (TRACKED) {
            TRACKED.removeIf(ref -> {
                Entity entity = ref.get();
                return entity == null || entity.isRemoved();
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /* 右键：Shift 切模式 / 普通右键范围攻击                                 */
    /* ------------------------------------------------------------------ */

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // Shift + 右键：循环切换模式
        if (player.isSecondaryUseActive()) {
            if (!level.isClientSide) {
                KillMode next = getMode(stack).next();
                setMode(stack, next);
                player.displayClientMessage(
                        Component.literal("测试剑：模式 → " + next.label()),
                        true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        // 普通右键：对渲染范围内所有实体执行当前模式
        if (!level.isClientSide) {
            int affected = applyModeToArea(level, player, stack);
            player.getCooldowns().addCooldown(this, 40); // 2 秒冷却
            player.displayClientMessage(
                    Component.literal("测试剑：" + getMode(stack).label() + " → 影响 " + affected + " 个实体"),
                    true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** 对"渲染范围"内所有实体执行当前模式（服务端视距近似，封顶） */
    private static int applyModeToArea(Level level, Player player, ItemStack stack) {
        double range = MAX_AOE_RANGE;
        if (level instanceof ServerLevel serverLevel) {
            range = Math.min(
                    serverLevel.getServer().getPlayerList().getViewDistance() * 16.0D,
                    MAX_AOE_RANGE);
        }

        List<LivingEntity> targets = level.getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(range),
                e -> e != player && e.isAlive() && !e.isRemoved());

        for (LivingEntity target : targets) {
            applyMode(stack, target, player);
            track(target);
        }
        return targets.size();
    }

    /* ------------------------------------------------------------------ */
    /* 统一执行模式                                                         */
    /* ------------------------------------------------------------------ */

    private static void applyMode(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        switch (getMode(stack)) {
            case REMOVE -> target.remove(Entity.RemovalReason.KILLED);
            case NBT -> NBTkill(target);
            case DIE -> {
                if (attacker instanceof Player player) {
                    target.die(target.damageSources().playerAttack(player));
                } else {
                    target.die(target.damageSources().generic());
                }
            }
            default -> target.setHealth(0.0F);
        }
    }

    /* ------------------------------------------------------------------ */
    /* NBTkill：复制 NBT → 清零血量/背包/状态 → 强行覆写                      */
    /* ------------------------------------------------------------------ */

    /**
     * 对目标执行"NBT 清除"：
     *   1. 复制实体当前 NBT；
     *   2. 把 Health / 吸收值清 0；
     *   3. 清空背包（Inventory / Items / ArmorItems / HandItems 及坐骑装备等）；
     *   4. 清空所有状态（药水效果、着火、空气、无敌/死亡计时、姿势、发光、
     *      载具/乘客、队伍标签等）；
     *   5. 用 load() 强行覆写到实体本身；
     *   6. 运行时兜底：load() 只覆盖 NBT 中"存在"的键，因此对效果、背包、
     *      着火等再做一次运行时清理，确保真正清空。
     */
    public static void NBTkill(LivingEntity target) {
        // 1) 复制实体 NBT（saveWithoutId 不含 id / UUID）
        CompoundTag nbt = new CompoundTag();
        target.saveWithoutId(nbt);

        // 2) 血量清零
        nbt.putFloat("Health", 0.0F);
        nbt.putFloat("AbsorptionAmount", 0.0F);

        // 3) 清空背包（不同实体使用的字段不同，统一写成空列表）
        nbt.put("Inventory", new ListTag());
        nbt.put("Items", new ListTag());
        nbt.put("ArmorItems", new ListTag());
        nbt.put("HandItems", new ListTag());
        nbt.remove("Item");
        nbt.remove("ItemStack");
        nbt.remove("Saddle");
        nbt.remove("DecorItem");
        nbt.remove("body_armor_item");
        nbt.remove("ArmorItem");

        // 4) 清空所有状态
        nbt.put("ActiveEffects", new ListTag());
        nbt.put("Attributes", new ListTag());
        nbt.put("Passengers", new ListTag());
        nbt.put("Tags", new ListTag());
        nbt.putShort("Fire", (short) 0);
        nbt.putShort("Air", (short) 300);
        nbt.putShort("HurtTime", (short) 0);
        nbt.putInt("HurtByTimestamp", 0);
        nbt.putShort("DeathTime", (short) 0);
        nbt.putFloat("FallDistance", 0.0F);
        nbt.putBoolean("FallFlying", false);
        nbt.putBoolean("Glowing", false);
        nbt.putBoolean("Invulnerable", false);
        nbt.putBoolean("NoGravity", false);
        nbt.putBoolean("PersistenceRequired", false);
        nbt.putBoolean("Silent", false);
        nbt.putBoolean("NoAI", false);

        // 5) 强行覆写回实体
        target.load(nbt);

        // 6) 运行时兜底清理
        target.removeAllEffects();
        target.setHealth(0.0F);
        target.setPose(Pose.STANDING);
        target.setDeltaMovement(0.0, 0.0, 0.0);
        target.clearFire();
        target.ejectPassengers();
        target.setAirSupply(target.getMaxAirSupply());

        // 背包兜底
        if (target instanceof Player player) {
            player.getInventory().clearContent();
        } else if (target instanceof Mob mob) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                mob.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
        if (target instanceof Container container) {
            container.clearContent();
        }
    }

    /**
     * 近战命中实体时触发。
     *
     * @return 是否消耗耐久（true 会消耗，与原版剑一致）
     */
    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        applyMode(stack, target, attacker);
        track(target);

        // 交给父类处理击退、设置"最后造成伤害者"等逻辑（也顺便消耗耐久）
        return super.hurtEnemy(stack, target, attacker);
    }
}
