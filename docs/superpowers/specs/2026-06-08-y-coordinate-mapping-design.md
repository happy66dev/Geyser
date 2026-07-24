# Y坐标映射层设计文档

## 概述

当Java服务端维度Y范围超出正常范围(-64~320)，需要将Java→Bedrock发报中的Y坐标映射到基岩版允许的范围内(-512~512)，并确保Bedrock→Java的发包反向映射回真实坐标。

**触发条件**: Java维度 `minY` ≤ -64 或 `maxY` ≥ 320 时触发自定义 BedrockDimension。

**坐标映射条件**: Java维度超出 [-512, 512] 时触发 Y 坐标平移映射。

| 条件 | BedrockDimension | 坐标映射 |
|------|-----------------|---------|
| Java范围在 [-64, 320] 内 | 使用标准 OVERWORLD/NETHER/END | 不需要 |
| Java范围在 [-512, 512] 内但超出正常范围 | 自定义维度匹配Java范围 | 不需要 (offset=0) |
| Java范围超出 [-512, 512] 但 span ≤ 1024 | 自定义维度 bedrockMinY=-512, bedrockMaxY=-512+span | 需要: offset = -512 - javaMinY |
| Java span > 1024 | 自定义维度 minY=-512, maxY=512 | 需要: offset同上, 超出1024部分丢弃 |

**核心公式（平移映射）**:
```
bedrockY = javaY + offset
javaY    = bedrockY - offset
offset   = bedrockMinY - javaMinY
```

## 新增组件

### 1. WorldHeightMapper (新类)

文件: `core/src/main/java/org/geysermc/geyser/level/WorldHeightMapper.java`

```java
public class WorldHeightMapper {
    private final boolean needsMapping;
    private final int offset;
    private final int bedrockMinY;
    private final int bedrockMaxY;

    // 工厂方法: 从JavaDimension计算映射参数
    public static WorldHeightMapper create(JavaDimension javaDimension);

    // 正向映射
    public int mapY(int javaY);
    public float mapY(float javaY);
    public double mapY(double javaY);
    public Vector3f mapPosition(Vector3f pos);
    public Vector3i mapPosition(Vector3i pos);

    // 反向映射
    public int inverseMapY(int bedrockY);
    public float inverseMapY(float bedrockYY);
    public double inverseMapY(double bedrockY);
    public Vector3f inverseMapPosition(Vector3f pos);
    public Vector3i inverseMapPosition(Vector3i pos);

    // 创建对应的BedrockDimension
    public BedrockDimension createBedrockDimension(int bedrockId);
}
```

计算逻辑：
1. `javaMinY = dim.minY()`, `javaMaxY = dim.minY() + dim.height()`
2. 若 `javaMinY >= -512 && javaMaxY <= 512` → `needsMapping=false, offset=0`, `bedrockMinY=javaMinY, bedrockMaxY=javaMaxY`（不需要坐标映射，但可能需要自定义维度）
3. `span = javaMaxY - javaMinY`
4. `bedrockMinY = -512`, `bedrockMaxY = Math.min(512, -512 + Math.min(span, 1024))`
5. `offset = bedrockMinY - javaMinY`
6. `needsMapping = true`

### 2. BedrockDimension 扩展

支持通过构造器创建自定义维度实例（当前只有三个static final实例），新增字段 `isCustom` 标识为自定义维度。

### 3. GeyserSession 扩展

新增字段和方法：
```java
private WorldHeightMapper worldHeightMapper;

public WorldHeightMapper worldHeightMapper();
public int mapY(int javaY);
public float mapY(float javaY);
public double mapY(double javaY);
public Vector3f mapPosition(Vector3f pos);
public Vector3i mapPosition(Vector3i pos);
public int inverseMapY(int bedrockY);
public float inverseMapY(float bedrockY);
public double inverseMapY(double bedrockY);
public Vector3f inverseMapPosition(Vector3f pos);
public Vector3i inverseMapPosition(Vector3i pos);
```

## 初始化流程

在 `ChunkUtils.loadDimension()` 中：

```
1. 读取 JavaDimension
2. 调用 WorldHeightMapper.create(javaDimension) 创建映射器
3. session.setWorldHeightMapper(mapper)
4. 如果 Java 范围超出正常范围(-64~320):
   - session.setBedrockDimension(mapper.createBedrockDimension(dim.bedrockId()))
   否则:
   - 保持标准BedrockDimension (由DimensionUtils.switchDimension()设置)
5. ChunkCache 的 minY/heightY 始终使用 Java 原始值
   (ChunkCache存储的是Java维度的section信息，翻译器用它计算Java端的section索引)
```

## 翻译器修改点

### Java→Bedrock (正向映射)

| 文件 | 修改位置 | 修改内容 |
|------|---------|---------|
| JavaSoundTranslator | L40 | `packet.getY()` → `session.mapY(packet.getY())` |
| JavaLevelEventTranslator | L87,143 | `packet.getPosition().getY()` → 对pos整体应用 mapPosition |
| JavaLevelParticlesTranslator | L72,81 | `packet.getY()` → `session.mapY(packet.getY())` |
| JavaExplodeTranslator | L48,52,53 | `packet.getCenter()` → mapPosition整体 |
| JavaAddEntityTranslator | L65 | `packet.getY()` → `session.mapY(packet.getY())` |
| JavaEntityPositionSyncTranslator | L44,51,53 | pos整体 → mapPosition |
| JavaMoveVehicleTranslator | L44,47 | `packet.getPosition()` → mapPosition |
| JavaPlayerPositionTranslator | L56-61,164 | position向量 → mapPosition |
| JavaPlayerLookAtTranslator | L70 | `packet.getY()` → `session.mapY(packet.getY())` |
| JavaBlockUpdateTranslator | L64,101 | position → mapPosition |
| JavaBlockDestructionTranslator | L52,55 | position → mapPosition |
| JavaBlockEventTranslator | L62,71,106,135,157 | position → mapPosition |
| JavaBlockEntityDataTranslator | L66-67,85 | position → mapPosition |
| JavaOpenSignEditorTranslator | L40 | position → mapPosition |
| JavaSetDefaultSpawnPositionTranslator | L41 | position → mapPosition |
| JavaSectionBlocksUpdateTranslator | L66-68 | sub-chunk position → mapPosition |

### Bedrock→Java (反向映射)

| 文件 | 修改位置 | 修改内容 |
|------|---------|---------|
| BedrockMovePlayer | L194,203 | `position.getY()` → `session.inverseMapY(position.getY())` |
| BedrockPlayerAuthInputTranslator | L323,336 | position → inverseMapPosition |
| BedrockInventoryTransactionTranslator | L175,246-250,287-288 | blockPosition → inverseMapPosition |
| BedrockBlockPickRequestTranslator | L44-45 | vector → inverseMapPosition |
| BedrockBlockEntityDataTranslator | L110-111,116 | NBT y坐标 → inverseMapY |

### 不需要修改的

- BedrockMovePlayer L62 (confirmTeleport): 使用packet.getPosition()与entity.bedrockPosition()比较距离，两者都在同一个映射空间
- BedrockMovePlayer L129 (void check): 比较的是映射后的边界
- JavaSoundEntityTranslator: 使用entity.bedrockPosition()，实体位置已被映射
- 所有LevelEvent/Sound子翻译器: 从调用方传入的position已映射
- 所有delta移动翻译器: 增量不涉及坐标映射

## 不修改的文件（明确排除）

- JavaMoveEntityPosTranslator: delta offset
- JavaMoveEntityPosRotTranslator: delta offset
- JavaMoveEntityRotTranslator: 无Y坐标
- JavaTeleportEntityTranslator: 仅日志
- JavaForgetLevelChunkTranslator: 无Y
- JavaSetPassengersTranslator: 无Y（乘客位置通过Entity内部处理）
- JavaSetEntityDataTranslator: 无直接Y操作
- JavaGameEventTranslator: 无位置Y
- JavaTrackedWaypointTranslator: 委托给WaypointCache（不涉及Y坐标）
- BedrockInteractTranslator: 使用Vector3d.ZERO
- BedrockPlayerActionTranslator: 无Y
- BedrockRespawnTranslator: 无Y
- BedrockContainerCloseTranslator: 无Y
- BedrockLecternUpdateTranslator: 只有页码
- BedrockEntityPickRequestTranslator: 无Y
- BedrockEntityEventTranslator: 无Y

## 区块翻译处理

`JavaLevelChunkWithLightTranslator` 和 `JavaSectionBlocksUpdateTranslator` **不需要修改**。

原因：它们依赖 `session.getChunkCache().getChunkMinY()` 和 `session.getBedrockDimension().minY()` 来计算 section 偏移。初始化时将这些值设置为映射后的值，区块翻译自动正确。

不过需要注意 `JavaLevelChunkWithLightTranslator` 中 L124-128 行：
```java
if (bedrockSectionY < 0 || maxBedrockSectionY < bedrockSectionY) {
    continue; // Ignore this chunk section
}
```
当映射后 bedrockDimension 的 range 缩小（span > 1024时），这个过滤会自动丢弃超出映射范围section。

## 边界情况

1. **玩家在映射边界附近**: BedrockMovePlayer 中的碰撞检测使用映射后的坐标，边界检查也使用映射后的 dimension minY。当玩家在 Bedrock客户端看来在 Y=-512 附近时，实际上在Java服务端可能在 Y=-64 或更低位置。碰撞检测/世界边界检查已经使用session.getBedrockDimension()因此正确。

2. **多维度切换**: 每次切换维度时 `ChunkUtils.loadDimension()` 都会重新计算映射参数，映射器会跟随维度变化更新。

3. **配置变化**: 映射参数在玩家每次加载维度时计算，不会持久化，无需处理热重载。

## 测试要点

1. Java维度在正常范围(-64~320)内 → needsMapping=false, 行为不变
2. Java维度 -64~960 → 映射到 -512~512
3. Java维度 -64~660 → 映射到 -512~212
4. Java维度 -64~2032 → 映射到 -512~512，超出部分丢弃
5. Bedrock→Java方块放置/交互 → Y坐标正确反扩射
6. 玩家移动Y → 正反向映射正确
7. 实体音效 → Y映射正确
8. 实体生成/同步位置 → Y映射正确
