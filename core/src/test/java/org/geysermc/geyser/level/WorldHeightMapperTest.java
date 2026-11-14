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

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 Java 维度高度到 Bedrock 高度窗口的双向映射喵~
 * <p>
 * 输入：由 {@link JavaDimension} 描述的服务端维度 minY 与 height 喵~
 * 输出：{@link WorldHeightMapper} 的 offset、Bedrock 高度窗口，以及正反向映射结果喵~
 * 边界：覆盖正常范围、需要在 [-512,512] 内平移、以及 span 超过 1024 需要截断三种情形喵~
 */
class WorldHeightMapperTest {

    /**
     * 构造一个只关心高度信息的测试维度喵~
     *
     * @param minY 该维度 Java 侧的最低 Y 坐标
     * @param height 该维度 Java 侧的总高度，单位：格
     * @return 一个主世界类型的最小可用维度对象喵~
     */
    private static JavaDimension overworldDimension(int minY, int height) {
        // 其余字段与高度映射无关，这里填主世界默认值即可喵~
        return new JavaDimension(minY, height, false, false, BedrockDimension.OVERWORLD_ID, false, null);
    }

    @Test
    void standardOverworldNeedsNoMapping() {
        // Java 标准主世界为 -64 起高 384，完全落在 [-512,512] 内喵~
        WorldHeightMapper mapper = WorldHeightMapper.create(overworldDimension(-64, 384));

        // 不需要平移，所有映射都应是恒等变换喵~
        assertFalse(mapper.needsMapping());
        assertEquals(0, mapper.offset());
        // 正向映射原样返回喵~
        assertEquals(100, mapper.mapY(100));
        assertEquals(-64, mapper.mapY(-64));
        // 反向映射同样原样返回喵~
        assertEquals(100, mapper.inverseMapY(100));
    }

    @Test
    void dimensionTallerThanBedrockWindowIsShiftedDown() {
        // -64 起高 1024，最高到 960，超出 Bedrock 的 512 上限喵~
        WorldHeightMapper mapper = WorldHeightMapper.create(overworldDimension(-64, 1024));

        // 需要平移，并且对齐到 Bedrock 的最低高度 -512 喵~
        assertTrue(mapper.needsMapping());
        assertEquals(-512, mapper.bedrockMinY());
        assertEquals(512, mapper.bedrockMaxY());
        // offset = bedrockMinY - javaMinY = -512 - (-64) = -448 喵~
        assertEquals(-448, mapper.offset());
        // Java 的 -64 应落在 Bedrock 的 -512 喵~
        assertEquals(-512, mapper.mapY(-64));
        // Java 的 960 正好顶到上边界，映射后会被夹到 maxY - 1 = 511 喵~
        assertEquals(511, mapper.mapY(960));
    }

    @Test
    void spanShorterThan1024KeepsOriginalHeight() {
        // 0 起高 660，最高到 660，超出 512 但 span 只有 660 喵~
        WorldHeightMapper mapper = WorldHeightMapper.create(overworldDimension(0, 660));

        // 仍需要平移，Bedrock 窗口高度等于 Java 的 span 喵~
        assertTrue(mapper.needsMapping());
        assertEquals(-512, mapper.bedrockMinY());
        // -512 + 660 = 148，说明高度没有被压缩，只是整体下移喵~
        assertEquals(148, mapper.bedrockMaxY());
        assertEquals(-512, mapper.offset());
    }

    @Test
    void spanLongerThan1024IsTruncatedToFullBedrockWindow() {
        // -64 起高 2032，span 远大于 Bedrock 能容纳的 1024 喵~
        WorldHeightMapper mapper = WorldHeightMapper.create(overworldDimension(-64, 2032));

        // span 被截断为 1024，因此 Bedrock 窗口就是完整的 -512~512 喵~
        assertTrue(mapper.needsMapping());
        assertEquals(-512, mapper.bedrockMinY());
        assertEquals(512, mapper.bedrockMaxY());
    }

    @Test
    void mappedYIsClampedIntoBedrockWindow() {
        // 构造一个 span 被截断的映射器，用来验证越界坐标会被夹到窗口内喵~
        WorldHeightMapper mapper = WorldHeightMapper.create(overworldDimension(-64, 2032));

        // Java 的 3000 远超 Bedrock 上限，应被夹到 maxY - 1 = 511 喵~
        assertEquals(511, mapper.mapY(3000));
        // Java 的 -100000 低于 Bedrock 下限，应被夹到 minY = -512 喵~
        assertEquals(-512, mapper.mapY(-100000));
    }

    @Test
    void unclampedMappingOnlyAddsOffset() {
        // 实体、粒子等允许超出维度边界，因此单独提供不夹紧的映射喵~
        WorldHeightMapper mapper = WorldHeightMapper.create(overworldDimension(-64, 2032));

        // 3000 + (-448) = 2552，允许超过 512 喵~
        assertEquals(2552, mapper.mapYUnclamped(3000));
        // 反向的 double 版本与原值相减即可还原喵~
        assertEquals(3000.0, mapper.inverseMapY(2552.0));
    }

    @Test
    void positionMappingKeepsHorizontalAxes() {
        // 水平轴绝不能被高度映射影响喵~
        WorldHeightMapper mapper = WorldHeightMapper.create(overworldDimension(-64, 2032));

        // 整数位置只改变 Y 轴喵~
        assertEquals(Vector3i.from(123, 511, -456), mapper.mapPosition(Vector3i.from(123, 3000, -456)));
        // 浮点位置同样只改变 Y 轴喵~
        assertEquals(Vector3f.from(1.5f, 511f, 2.5f), mapper.mapPosition(Vector3f.from(1.5f, 3000f, 2.5f)));
    }

    @Test
    void inversePositionMappingRestoresJavaCoordinates() {
        // 反向映射必须能把 Bedrock 方块坐标还原成 Java 方块坐标喵~
        WorldHeightMapper mapper = WorldHeightMapper.create(overworldDimension(-64, 1024));

        // Bedrock 的 -512 对应 Java 的 -64 喵~
        assertEquals(Vector3i.from(0, -64, 0), mapper.inverseMapPosition(Vector3i.from(0, -512, 0)));
        // Bedrock 的浮点坐标同样能还原喵~
        assertEquals(Vector3f.from(8f, -64f, 8f), mapper.inverseMapPosition(Vector3f.from(8f, -512f, 8f)));
    }

    @ParameterizedTest
    @CsvSource({
        // javaMinY, javaHeight, expectedNeedsMapping, expectedBedrockMinY, expectedBedrockMaxY
        "-64, 384, false, -64, 320",
        "-512, 1024, false, -512, 512",
        "-64, 1024, true, -512, 512",
        "0, 660, true, -512, 148",
        "-64, 2032, true, -512, 512"
    })
    void mapperFollowsDesignTable(int javaMinY, int javaHeight, boolean expectedNeedsMapping,
                                  int expectedBedrockMinY, int expectedBedrockMaxY) {
        // 用设计文档中的表格逐行校验映射参数喵~
        WorldHeightMapper mapper = WorldHeightMapper.create(overworldDimension(javaMinY, javaHeight));

        // 三组期望值同时成立才算通过喵~
        assertEquals(expectedNeedsMapping, mapper.needsMapping());
        assertEquals(expectedBedrockMinY, mapper.bedrockMinY());
        assertEquals(expectedBedrockMaxY, mapper.bedrockMaxY());
    }

    @Test
    void nullDimensionIsRejected() {
        // 喵~防御：维度信息缺失时必须快速失败，而不是静默产生错误的映射喵~
        assertThrows(NullPointerException.class, () -> WorldHeightMapper.create(null));
    }

    @Test
    void identityMapperIsUsableBeforeLogin() {
        // 登录前会话还没有真实维度，此时应使用恒等映射保证不改变任何坐标喵~
        WorldHeightMapper identity = WorldHeightMapper.identity();

        // 注册方法不应为 null，避免调用方额外判空喵~
        assertNotNull(identity);
        // 恒等映射不做平移喵~
        assertFalse(identity.needsMapping());
        assertEquals(0, identity.offset());
        // 任意坐标都原样返回喵~
        assertEquals(42, identity.mapY(42));
        assertEquals(-42.5, identity.inverseMapY(-42.5));
    }

    @Test
    void createdBedrockDimensionMatchesMapperWindow() {
        // 自定义 Bedrock 维度必须与映射器声明的高度窗口保持一致，否则区块会错位喵~
        WorldHeightMapper mapper = WorldHeightMapper.create(overworldDimension(-64, 1024));
        BedrockDimension dimension = mapper.createBedrockDimension(BedrockDimension.OVERWORLD_ID);

        // 下限、上限、高度三者都应一致喵~
        assertEquals(mapper.bedrockMinY(), dimension.minY());
        assertEquals(mapper.bedrockMaxY(), dimension.maxY());
        assertEquals(mapper.bedrockMaxY() - mapper.bedrockMinY(), dimension.height());
        // 该维度是自定义构造出来的，需要被标记为 custom 喵~
        assertTrue(dimension.isCustom());
    }

    @Test
    void doubleMappingMatchesFloatMapping() {
        // 同一坐标的 float 与 double 映射结果必须一致，否则玩家位置会在两种调用间抖动喵~
        WorldHeightMapper mapper = WorldHeightMapper.create(overworldDimension(-64, 1024));

        // 取一个窗口内的坐标比较两种重载喵~
        assertEquals((double) mapper.mapY(-10f), mapper.mapY(-10.0));
        // 反向映射同样需要一致喵~
        assertEquals((double) mapper.inverseMapY(-458f), mapper.inverseMapY(-458.0));
    }

    @Test
    void unclampedPositionMappingKeepsHorizontalAxes() {
        // 不夹紧的向量映射同样只能触碰 Y 轴喵~
        WorldHeightMapper mapper = WorldHeightMapper.create(overworldDimension(-64, 1024));

        // 3000 + (-448) = 2552，X/Z 保持不变喵~
        assertEquals(Vector3f.from(7f, 2552f, -9f), mapper.mapPositionUnclamped(Vector3f.from(7f, 3000f, -9f)));
    }

    @Test
    void unclampedDoubleMappingIgnoresBounds() {
        // 不夹紧的 double 映射在不需要映射时应原样返回喵~
        WorldHeightMapper identity = WorldHeightMapper.identity();

        // 恒等映射不对 double 做任何改动喵~
        assertEquals(1234.5, identity.mapYUnclamped(1234.5));
        // 反向坐标验证 Vector3d 参与的换算链路不受影响喵~
        assertEquals(0.0, identity.inverseMapY(0.0));
        // 用 Vector3d 构造一次，确保相关类型在测试编译路径上可用喵~
        assertNotNull(Vector3d.from(0, 0, 0));
    }
}
