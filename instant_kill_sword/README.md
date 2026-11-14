# 测试剑（Forge 1.20.1）

一把外观与原版钻石剑完全一致的模组武器，但命中实体时按所选模式"处理"目标。

## 功能

- 无限耐久，永不损坏
- 外观直接引用原版 `minecraft:item/diamond_sword` 贴图，无新增图片
- 创造模式"战斗（Combat）"标签页可获取

### 四种模式（Shift + 右键循环切换）

| 模式 | 行为 | 结果 |
|---|---|---|
| 改血量 = 0（默认） | 只 `setHealth(0)` | 血量归零，不掉落、无死亡消息 |
| `die()` | 只 `die(...)` | 完整死亡流程：掉落、战利品、击杀统计 |
| `remove()` | `remove(RemovalReason.KILLED)` | 直接移出世界，无掉落 |
| `NBTkill()` | 复制 NBT → 清空血量/背包/状态 → 强行覆写 | 实体被"抹除"数据 |

### 范围攻击

普通右键（不按 Shift）对"渲染范围"内所有实体执行当前模式：

- 半径用服务器视距近似（`视距区块 × 16`），封顶 64 格
- 排除施法者自己；会命中其他玩家，联机 PVP 慎用
- 2 秒冷却

> 右键箱子、工作台等可交互方块会被方块交互吃掉，对着空气右键最稳。

### 内存泄露清理

被处理过的实体用 `WeakReference` 跟踪，主类每 20 tick 清理一次已移除/已回收的引用。

## 构建

需要 JDK 17。

方式一（命令行）：

```bash
gradle build
```

产物：`build/libs/instant_kill_sword-1.0.0.jar`

方式二（GitHub Actions）：仓库已含 `.github/workflows/build.yml`，push 后自动构建，在 Actions → 对应 run → Artifacts 下载 `mod-jar.zip`。

方式三（IDE）：IntelliJ IDEA 打开工程，Gradle 同步后运行 `build` 任务。

把 jar 放进 Forge 1.20.1 的 `mods` 目录即可。
