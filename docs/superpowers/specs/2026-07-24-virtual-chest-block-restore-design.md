# 虚拟箱子原方块复原设计

## 问题

Java 服务端打开虚拟单箱子或双箱子菜单时，Geyser 会在玩家上方创建 Bedrock 假箱子。高度映射维度中，假箱子位置以 Bedrock 坐标保存；关闭菜单时，现有代码直接将该 Bedrock 坐标传给 Java `WorldManager` 查询原方块。

映射维度的 Y 坐标存在偏移，查询会命中错误 Java 高度。Geyser 随后将错误方块状态发往假箱子位置，导致原本位于玩家头顶的方块不复原。

## 范围

修复两条虚拟容器关闭路径：

- `BlockInventoryHolder.closeInventory(...)`：单箱子及所有使用通用块容器持有器的虚拟容器。
- `DoubleChestInventoryTranslator.closeInventory(...)`：双箱子虚拟容器的左右两个方块位置。

不改变真实方块容器、假箱子创建、容器关闭包、Bedrock 更新位置或现有数据包 flags。

## 设计

容器持有器位置继续保持 Bedrock 坐标。关闭虚拟容器时：

1. 保留 `holderPosition` 作为发送 `UpdateBlockPacket` 的 Bedrock 坐标。
2. 使用 `session.inverseMapPosition(holderPosition)` 转换为 Java 坐标。
3. 使用转换后的 Java 坐标向 `WorldManager` 查询原始方块状态。
4. 将查询结果映射为 Bedrock 方块定义，并发送到未转换的原 Bedrock 坐标。
5. 双箱子对主方块和 `holderPosition + X` 分别重复该流程。

此方式遵循 `BlockUtils.restoreCorrectBlock(...)` 已使用的坐标转换约定：Java 世界查询使用反向映射后的坐标，Bedrock 客户端数据包使用映射后的坐标。

## 错误处理与边界

- 容器使用真实方块时，维持既有提前返回和强制关闭处理，不执行虚拟方块恢复查询。
- 普通维度的 mapper 偏移为零，反向映射等价于原坐标，因此行为不变。
- `holderPosition` 生命周期和既有非空前提保持不变，不新增状态或异步任务。

## 验证

1. 在高度映射维度，玩家头顶两格处为实体方块时，打开并关闭单箱子虚拟菜单，确认原方块恢复。
2. 在相同条件下打开并关闭双箱子虚拟菜单，确认两个假箱子位置的原方块均恢复。
3. 在未启用高度映射的普通维度重复单、双箱子场景，确认无回归。
4. 编译 `:velocity:shadowJar -x test`，并运行 `git diff --check`。
