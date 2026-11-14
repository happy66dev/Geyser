/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.level;

import org.cloudburstmc.math.GenericMath;
import org.cloudburstmc.math.vector.Vector2d;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector2i;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;

/**
 * 把 Java 服务端的水平坐标（X/Z）换算到 Bedrock 客户端就近坐标，绕开大坐标浮点精度崩坏喵~
 * <p>
 * 背景：Bedrock 客户端用 float32 存实体坐标，当 |X| 或 |Z| 达到几千格时，单个 float 已无法精确表示
 * 相邻格，会出现方块错位、移动抖动、贴图撕裂喵~ 本类通过给 Bedrock 侧整体平移一个 16 格对齐的 offset，
 * 让客户端始终以为玩家在原点附近，而 Java 服务端始终收发真实坐标喵~
 *
 * <h2>坐标系约定</h2>
 * <ul>
 *     <li>「Java 坐标」= 服务端权威坐标，方块、实体、区块缓存内部一律使用这套喵~</li>
 *     <li>「Bedrock 坐标」= 发给客户端的坐标，等于 <code>java - offset</code> 喵~</li>
 *     <li>正向映射（Java→Bedrock）做减法，反向映射（Bedrock→Java）做加法喵~</li>
 * </ul>
 *
 * <h2>offset 选择规则</h2>
 * <p>
 * 当玩家水平位置任一分量绝对值超过 <code>triggerDistance</code> 时，把超限的分量压缩到原点附近：
 * 先求出「缩短后的目标坐标」（对超大值反复开平方，直到落入 100 格内），再让 offset 对齐到 16 格
 * （一个区块宽度），保证映射后区块边界仍然对齐，避免半区块错位喵~
 * 未超限的分量 offset 保持 0，因此普通世界不受影响喵~
 *
 * <h2>输入输出与边界</h2>
 * <ul>
 *     <li>输入：Java 坐标、当前玩家位置、配置阈值喵~</li>
 *     <li>输出：可直接发给 Bedrock 的坐标；以及反向还原后的 Java 坐标喵~</li>
 *     <li>边界：所有 offset 分量都是 16 的整数倍；不需要映射时是零向量，所有方法退化为恒等变换喵~</li>
 * </ul>
 */
public final class HorizontalOffsetMapper {

    /** 一个 Bedrock 区块的宽度，单位：格；offset 必须对齐到它才能保证区块索引不错位喵~ */
    private static final int CHUNK_SIZE = 16;

    /** 压缩目标上限，单位：格；开平方压缩时一旦落入这个范围就停止喵~ */
    private static final double RELATIVE_TARGET = 100.0;

    /** 开平方压缩的最大迭代次数；纯属双重保险，正常数据 4 次以内必然收敛喵~ */
    private static final int MAX_SHRINK_ITERATIONS = 64;

    /**
     * offset 量化粒度，单位：格，等于 64 个区块喵~
     * <p>
     * offset 只在跨过这个粒度时才变化，因此客户端世界重建的频率从「每 15 格」降到「每 4096 格」喵~
     * 取值权衡：粒度越大，世界重建越少但客户端坐标范围越大、float32 精度越差；
     * 4096 时客户端坐标最大约 4196，float32 精度仍优于 0.001 格，是舒适区间喵~
     */
    private static final int OFFSET_GRANULARITY = 4096;

    /** 恒等映射单例，未启用水平映射时使用，避免每个会话重复分配对象喵~ */
    private static final HorizontalOffsetMapper IDENTITY = new HorizontalOffsetMapper(0, 0);

    /** Java→Bedrock 的平移量，单位：格；Bedrock 坐标 = Java 坐标 - offsetX/offsetZ 喵~ */
    private final int offsetX;
    private final int offsetZ;

    /**
     * 私有构造器，强制走 {@link #create(Vector3d, int)} 或 {@link #identity()} 工厂方法喵~
     *
     * @param offsetX X 轴平移量，单位：格，必须是 16 的整数倍
     * @param offsetZ Z 轴平移量，单位：格，必须是 16 的整数倍
     */
    private HorizontalOffsetMapper(int offsetX, int offsetZ) {
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
    }

    /**
     * 按玩家当前位置计算应有的水平 offset 喵~
     *
     * @param playerPosition 玩家在 <b>Java 坐标系</b> 中的位置，不能为 null
     * @param triggerDistance 触发重定位的距离阈值，单位：格；任一水平分量绝对值超过它就重算
     * @return 新的水平映射器；玩家仍在阈值内时返回零 offset 映射器喵~
     */
    public static HorizontalOffsetMapper create(Vector3d playerPosition, int triggerDistance) {
        // 喵~防御：玩家位置缺失时无法计算 offset，直接退化为恒等映射而不是抛异常打断登录喵~
        if (playerPosition == null) {
            return IDENTITY;
        }

        // 喵~防御：NaN 与无穷大无法参与任何整数换算（floor 会产生 Integer.MIN_VALUE），直接退化为恒等映射喵~
        if (!Double.isFinite(playerPosition.getX()) || !Double.isFinite(playerPosition.getZ())) {
            return IDENTITY;
        }

        // 喵~防御：阈值必须为正，否则任何位置都会触发无限重定位；非法值按默认 3000 处理喵~
        int safeTriggerDistance = triggerDistance > 0 ? triggerDistance : 3000;

        // 分别判断两个水平分量是否超限，未超限的分量保持 offset 为 0 喵~
        int computedOffsetX = writeOffsetFor(playerPosition.getX(), safeTriggerDistance);
        int computedOffsetZ = writeOffsetFor(playerPosition.getZ(), safeTriggerDistance);

        // 两个分量都没超限时直接复用恒等单例，省掉一次对象分配喵~
        if (computedOffsetX == 0 && computedOffsetZ == 0) {
            return IDENTITY;
        }
        return new HorizontalOffsetMapper(computedOffsetX, computedOffsetZ);
    }

    /**
     * 计算单个水平分量需要的平移量，按大网格量化后对齐到区块喵~
     * <p>
     * <b>为什么必须量化</b>
     * <p>
     * 如果直接拿「原始坐标 - 开平方压缩值」当 offset，压缩值会随坐标连续变化，
     * 实测每走约 15 格 offset 就会变一次，等于每 15 格重建一次客户端世界，
     * 比不优化还卡喵~ 所以这里把 offset 量化到 {@link #OFFSET_GRANULARITY} 的整数倍，
     * 只有玩家跨过网格边界时才换一次坐标系，频率从「每 15 格」降到「每 4096 格」喵~
     *
     * <b>量化后客户端坐标范围</b>
     * <p>
     * 量化值落在 <code>[0, 粒度)</code> 之间，加上压缩基值后客户端坐标大致在
     * <code>[-100, 粒度)</code> 内，float32 在这个范围仍有约 0.0005 格的精度，
     * 远优于肉眼与碰撞检测所需，足以消除方块错位与移动抖动喵~
     *
     * @param coordinate 该分量在 Java 坐标系中的值，单位：格
     * @param triggerDistance 触发阈值，单位：格
     * @return 该分量的平移量，单位：格；保证是 16 的整数倍，未超阈值时为 0 喵~
     */
    private static int writeOffsetFor(double coordinate, int triggerDistance) {
        // 未超过阈值时分量保持原位，普通世界因此完全不受影响喵~
        if (Math.abs(coordinate) <= triggerDistance) {
            return 0;
        }

        // 求出该分量压缩后希望落在的基值，范围限定在 ±100 格内喵~
        int compressed = GenericMath.floor(shrinkTowardsOrigin(coordinate));

        // 取坐标在「大网格」内的余数，offset 用「坐标减去它」就能把坐标拉回网格起点喵~
        // 用 Math.floorMod 而不是 % 运算符，因为前者对负数也返回非负余数，符号处理更简单喵~
        long gridRemainder = Math.floorMod((long) Math.floor(coordinate), OFFSET_GRANULARITY);

        // offset = 坐标 - 量化后的余数 - 压缩基值，这样映射结果 = 压缩基值 + 量化余数喵~
        long rawOffset = (long) Math.floor(coordinate) - gridRemainder - compressed;

        // 向下对齐到区块，保证 offset 是 16 的整数倍、映射后区块边界不错位喵~
        // 量化粒度是 16 的倍数，压缩基值也在 ±100 内，因此对齐后映射结果的偏移量小于一个区块，仍在可接受范围喵~
        long chunkAlignedOffset = (rawOffset >> 4) << 4;

        // 喵~防御：超出 int 表示范围时退化为 0（不做映射），总比世界整体跳到错误位置安全喵~
        if (chunkAlignedOffset > Integer.MAX_VALUE || chunkAlignedOffset < Integer.MIN_VALUE) {
            return 0;
        }
        return (int) chunkAlignedOffset;
    }

    /**
     * 把超大坐标反复开平方压到原点附近，保留符号喵~
     * <p>
     * 思路：绝对值大于 100 时不断取平方根，因为平方根增长极慢，几步之内一定能落进 100 格内喵~
     * 输入输出都是双精度；0 与负数都能正确处理（负数先取绝对值再还原符号）喵~
     *
     * <b>终止性保证</b>
     * <p>
     * 平方根单调递减只在有限输入下成立：<code>sqrt(Infinity) == Infinity</code>，
     * 因此必须在入口拦下非有限值，否则循环会永久卡住整个会话事件循环喵~
     *
     * @param coordinate 待压缩的坐标值，单位：格
     * @return 压缩后的坐标值，绝对值不超过 100 格左右；非有限输入返回 0 喵~
     */
    private static double shrinkTowardsOrigin(double coordinate) {
        // 喵~防御：NaN 会让所有比较都返回 false，无穷大在开平方后仍是无穷大，两者都会让下面的循环永不退出喵~
        // 这里直接返回 0，把异常数据退化成「不需要压缩」，绝不阻塞事件循环喵~
        if (!Double.isFinite(coordinate)) {
            return 0;
        }

        // 记录原始符号，压缩过程只对绝对值做运算喵~
        double sign = Math.signum(coordinate);
        // 复制一份可变的值，避免修改调用方数据喵~
        double magnitude = Math.abs(coordinate);

        // 反复开平方直到落入目标范围；平方根单调递减，循环一定会终止喵~
        // 喵~防御：额外的迭代上限只是双重保险，正常情况下 4 次以内就会退出喵~
        int safetyCounter = 0;
        while (magnitude > RELATIVE_TARGET && safetyCounter++ < MAX_SHRINK_ITERATIONS) {
            magnitude = Math.sqrt(magnitude);
        }

        // 还原符号后返回喵~
        return magnitude * sign;
    }

    /**
     * 返回恒等映射器，所有映射都是原样返回喵~
     *
     * @return 全局共享的零 offset 映射器喵~
     */
    public static HorizontalOffsetMapper identity() {
        return IDENTITY;
    }

    /**
     * 当前是否启用了水平映射喵~
     *
     * @return 任一水平分量 offset 非零时为 true 喵~
     */
    public boolean needsMapping() {
        return offsetX != 0 || offsetZ != 0;
    }

    /**
     * X 轴平移量，单位：格喵~
     *
     * @return 16 的整数倍，未启用时为 0 喵~
     */
    public int offsetX() {
        return offsetX;
    }

    /**
     * Z 轴平移量，单位：格喵~
     *
     * @return 16 的整数倍，未启用时为 0 喵~
     */
    public int offsetZ() {
        return offsetZ;
    }

    /**
     * 把 Java 方块坐标映射成 Bedrock 方块坐标喵~
     *
     * @param javaPosition Java 坐标系中的方块位置，不能为 null
     * @return Bedrock 坐标系中的方块位置；Y 轴原样保留喵~
     */
    public Vector3i mapPosition(Vector3i javaPosition) {
        // 未启用映射时直接返回入参，省掉一次对象分配喵~
        if (!needsMapping()) {
            return javaPosition;
        }
        return Vector3i.from(javaPosition.getX() - offsetX, javaPosition.getY(), javaPosition.getZ() - offsetZ);
    }

    /**
     * 把 Java 浮点位置映射成 Bedrock 浮点位置喵~
     *
     * @param javaPosition Java 坐标系中的位置，不能为 null
     * @return Bedrock 坐标系中的位置；Y 轴原样保留喵~
     */
    public Vector3f mapPosition(Vector3f javaPosition) {
        if (!needsMapping()) {
            return javaPosition;
        }
        return Vector3f.from(javaPosition.getX() - offsetX, javaPosition.getY(), javaPosition.getZ() - offsetZ);
    }

    /**
     * 把 Java 双精度位置映射成 Bedrock 双精度位置喵~
     *
     * @param javaPosition Java 坐标系中的位置，不能为 null
     * @return Bedrock 坐标系中的位置；Y 轴原样保留喵~
     */
    public Vector3d mapPosition(Vector3d javaPosition) {
        if (!needsMapping()) {
            return javaPosition;
        }
        return Vector3d.from(javaPosition.getX() - offsetX, javaPosition.getY(), javaPosition.getZ() - offsetZ);
    }

    /**
     * 把 Java 水平坐标映射成 Bedrock 水平坐标喵~（用于只有 X/Z 的场合，如世界边界中心）
     *
     * @param javaPosition Java 坐标系中的二维位置，不能为 null
     * @return Bedrock 坐标系中的二维位置喵~
     */
    public Vector2d mapPosition(Vector2d javaPosition) {
        if (!needsMapping()) {
            return javaPosition;
        }
        return Vector2d.from(javaPosition.getX() - offsetX, javaPosition.getY() - offsetZ);
    }

    /**
     * 把 Bedrock 方块坐标还原成 Java 方块坐标喵~
     *
     * @param bedrockPosition Bedrock 坐标系中的方块位置，不能为 null
     * @return Java 坐标系中的方块位置；Y 轴原样保留喵~
     */
    public Vector3i inverseMapPosition(Vector3i bedrockPosition) {
        if (!needsMapping()) {
            return bedrockPosition;
        }
        return Vector3i.from(bedrockPosition.getX() + offsetX, bedrockPosition.getY(), bedrockPosition.getZ() + offsetZ);
    }

    /**
     * 把 Bedrock 浮点位置还原成 Java 浮点位置喵~
     *
     * @param bedrockPosition Bedrock 坐标系中的位置，不能为 null
     * @return Java 坐标系中的位置；Y 轴原样保留喵~
     */
    public Vector3f inverseMapPosition(Vector3f bedrockPosition) {
        if (!needsMapping()) {
            return bedrockPosition;
        }
        return Vector3f.from(bedrockPosition.getX() + offsetX, bedrockPosition.getY(), bedrockPosition.getZ() + offsetZ);
    }

    /**
     * 把 Bedrock 双精度位置还原成 Java 双精度位置喵~
     *
     * @param bedrockPosition Bedrock 坐标系中的位置，不能为 null
     * @return Java 坐标系中的位置；Y 轴原样保留喵~
     */
    public Vector3d inverseMapPosition(Vector3d bedrockPosition) {
        if (!needsMapping()) {
            return bedrockPosition;
        }
        return Vector3d.from(bedrockPosition.getX() + offsetX, bedrockPosition.getY(), bedrockPosition.getZ() + offsetZ);
    }

    /**
     * 把 Bedrock 水平坐标还原成 Java 水平坐标喵~
     *
     * @param bedrockPosition Bedrock 坐标系中的二维位置，不能为 null
     * @return Java 坐标系中的二维位置喵~
     */
    public Vector2d inverseMapPosition(Vector2d bedrockPosition) {
        if (!needsMapping()) {
            return bedrockPosition;
        }
        return Vector2d.from(bedrockPosition.getX() + offsetX, bedrockPosition.getY() + offsetZ);
    }

    /**
     * 判断某个 Java 方块坐标是否需要重新计算 offset 喵~
     *
     * @param javaX Java 坐标系中的 X 坐标，单位：格
     * @param javaZ Java 坐标系中的 Z 坐标，单位：格
     * @param triggerDistance 触发阈值，单位：格
     * @return 任一分量绝对值超过阈值时为 true 喵~
     */
    public static boolean exceedsTrigger(double javaX, double javaZ, int triggerDistance) {
        // 喵~防御：非法阈值按默认值处理，避免配置错误导致每帧都重定位喵~
        int safeTriggerDistance = triggerDistance > 0 ? triggerDistance : 3000;
        return Math.abs(javaX) > safeTriggerDistance || Math.abs(javaZ) > safeTriggerDistance;
    }

    /**
     * 二维整数位置映射，供区块索引等只需要 X/Z 的场合使用喵~
     *
     * @param javaPosition Java 坐标系中的二维方块位置，不能为 null
     * @return Bedrock 坐标系中的二维方块位置喵~
     */
    public Vector2i mapChunkCoordinates(Vector2i javaPosition) {
        if (!needsMapping()) {
            return javaPosition;
        }
        // 区块坐标直接按 16 对齐平移，保证与 offset 的区块对齐约定一致喵~
        return Vector2i.from(javaPosition.getX() - (offsetX / CHUNK_SIZE), javaPosition.getY() - (offsetZ / CHUNK_SIZE));
    }

    /**
     * 二维浮点水平映射，供只有 X/Z 的浮点场合使用喵~
     *
     * @param javaPosition Java 坐标系中的二维浮点位置，不能为 null
     * @return Bedrock 坐标系中的二维浮点位置喵~
     */
    public Vector2f mapHorizontal(Vector2f javaPosition) {
        if (!needsMapping()) {
            return javaPosition;
        }
        return Vector2f.from(javaPosition.getX() - offsetX, javaPosition.getY() - offsetZ);
    }
}
