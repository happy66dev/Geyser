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
     * 计算单个水平分量需要的平移量，并对齐到区块喵~
     *
     * @param coordinate 该分量在 Java 坐标系中的值，单位：格
     * @param triggerDistance 触发阈值，单位：格
     * @return 该分量的平移量，单位：格；不会返回非 16 倍数的值喵~
     */
    private static int writeOffsetFor(double coordinate, int triggerDistance) {
        // 未超过阈值时分量保持原位，普通世界因此完全不受影响喵~
        if (Math.abs(coordinate) <= triggerDistance) {
            return 0;
        }

        // 求出该分量压缩后希望落在的位置，范围限定在 ±100 格内喵~
        int compressed = GenericMath.floor(shrinkTowardsOrigin(coordinate));

        // offset 取「原始坐标 - 压缩目标」再向下对齐到区块，保证映射后坐标落在区块边界上喵~
        int rawOffset = GenericMath.floor(coordinate) - compressed;

        // 向下取整到 16 的倍数；用位运算实现，对负数同样正确喵~
        return (rawOffset >> 4) << 4;
    }

    /**
     * 把超大坐标反复开平方压到原点附近，保留符号喵~
     * <p>
     * 思路：绝对值大于 100 时不断取平方根，因为平方根增长极慢，几步之内一定能落进 100 格内喵~
     * 输入输出都是双精度；0 与负数都能正确处理（负数先取绝对值再还原符号）喵~
     *
     * @param coordinate 待压缩的坐标值，单位：格
     * @return 压缩后的坐标值，绝对值不超过 100 格左右喵~
     */
    private static double shrinkTowardsOrigin(double coordinate) {
        // 记录原始符号，压缩过程只对绝对值做运算喵~
        double sign = Math.signum(coordinate);
        // 复制一份可变的值，避免修改调用方数据喵~
        double magnitude = Math.abs(coordinate);

        // 反复开平方直到落入目标范围；平方根单调递减，循环一定会终止喵~
        while (magnitude > RELATIVE_TARGET) {
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
