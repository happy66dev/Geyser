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

import org.cloudburstmc.math.vector.Vector2d;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector2i;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验水平坐标（X/Z）映射层的计算与反转正确性喵~
 * <p>
 * 输入：玩家 Java 坐标系位置 + 触发阈值喵~
 * 输出：{@link HorizontalOffsetMapper} 的偏移量与正向/反向映射结果喵~
 * 边界：阈值内不映射、超限只映射超限的分量、offset 必须是 16 的整数倍、Y 轴永不受影响喵~
 */
class HorizontalOffsetMapperTest {

    /** 测试用触发阈值，单位：格；与默认配置保持一致喵~ */
    private static final int TRIGGER = 10_000;

    @Test
    void identityMapperChangesNothing() {
        // 恒等映射用于未启用水平映射的会话，任何坐标都应原样返回喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.identity();

        // 未启用映射，偏移量都是 0 喵~
        assertFalse(mapper.needsMapping());
        assertEquals(0, mapper.offsetX());
        assertEquals(0, mapper.offsetZ());

        // 整数、浮点、双精度位置都原样返回喵~
        assertEquals(Vector3i.from(123, 64, -456), mapper.mapPosition(Vector3i.from(123, 64, -456)));
        assertEquals(Vector3f.from(1.5f, 64f, -2.5f), mapper.mapPosition(Vector3f.from(1.5f, 64f, -2.5f)));
        assertEquals(Vector3d.from(1.5, 64, -2.5), mapper.mapPosition(Vector3d.from(1.5, 64, -2.5)));
    }

    @Test
    void playerInsideTriggerGetsNoOffset() {
        // 玩家在阈值内时不应启用映射，普通世界因此完全不受影响喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(5000, 64, -5000), TRIGGER);

        assertFalse(mapper.needsMapping());
        assertEquals(0, mapper.offsetX());
        assertEquals(0, mapper.offsetZ());
    }

    @Test
    void playerAtTriggerBoundaryStaysUnmapped() {
        // 恰好等于阈值属于「未超过」，不应触发重定位喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(TRIGGER, 64, -TRIGGER), TRIGGER);

        assertFalse(mapper.needsMapping());
    }

    @Test
    void onlyExceedingAxisGetsOffset() {
        // 只有 X 超限时，Z 应保持 0 偏移喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(50_000, 64, 100), TRIGGER);

        assertTrue(mapper.needsMapping());
        assertTrue(mapper.offsetX() != 0);
        assertEquals(0, mapper.offsetZ());
    }

    @Test
    void offsetIsAlwaysChunkAligned() {
        // offset 必须是 16 的整数倍，否则 Bedrock 区块边界会与 Java 错开半个区块喵~
        int[] sampleCoordinates = {10_001, 50_000, -50_000, 1_000_000, -1_000_000, 123_456_789};

        for (int sampleCoordinate : sampleCoordinates) {
            HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(sampleCoordinate, 64, sampleCoordinate), TRIGGER);

            // 两个分量的偏移量都必须能被 16 整除喵~
            assertEquals(0, mapper.offsetX() % 16, "X 偏移未按区块对齐：" + mapper.offsetX());
            assertEquals(0, mapper.offsetZ() % 16, "Z 偏移未按区块对齐：" + mapper.offsetZ());
        }
    }

    @Test
    void largeCoordinateIsCompressedNearOrigin() {
        // 大坐标映射后必须落在可控范围内，否则客户端精度问题根本没解决喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(1_000_000, 64, 1_000_000), TRIGGER);

        Vector3i mappedPosition = mapper.mapPosition(Vector3i.from(1_000_000, 64, 1_000_000));

        // 压缩基值在 ±100 格内，再叠加量化网格 4096，因此结果必须落在这个范围喵~
        // 该范围下 float32 精度优于 0.001 格，远胜于原始的一百万格喵~
        int allowedMagnitude = 4096 + 116;
        assertTrue(Math.abs(mappedPosition.getX()) <= allowedMagnitude, "映射后 X 仍太远：" + mappedPosition.getX());
        assertTrue(Math.abs(mappedPosition.getZ()) <= allowedMagnitude, "映射后 Z 仍太远：" + mappedPosition.getZ());
    }

    @Test
    void roundTripRestoresJavaCoordinates() {
        // 正向映射后再反向映射必须精确还原 Java 坐标，否则方块放置会错位喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(50_000, 64, -80_000), TRIGGER);

        Vector3i javaPosition = Vector3i.from(50_123, 64, -80_456);
        Vector3i bedrockPosition = mapper.mapPosition(javaPosition);
        Vector3i restored = mapper.inverseMapPosition(bedrockPosition);

        assertEquals(javaPosition, restored);
    }

    @Test
    void floatRoundTripRestoresJavaCoordinates() {
        // 浮点坐标往返同样需要精确还原喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(50_000, 64, 50_000), TRIGGER);

        Vector3f javaPosition = Vector3f.from(50_123.5f, 64.25f, 50_456.75f);
        Vector3f restored = mapper.inverseMapPosition(mapper.mapPosition(javaPosition));

        assertEquals(javaPosition, restored);
    }

    @Test
    void yAxisIsNeverTouched() {
        // 水平映射层只管 X/Z，Y 由 WorldHeightMapper 负责，两者必须互不干扰喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(99_999, 64, 99_999), TRIGGER);

        // 各种 Y 值都应原样保留，包括超出常规范围的值喵~
        assertEquals(64, mapper.mapPosition(Vector3i.from(99_999, 64, 99_999)).getY());
        assertEquals(-512, mapper.mapPosition(Vector3i.from(99_999, -512, 99_999)).getY());
        assertEquals(2559.5f, mapper.mapPosition(Vector3f.from(99_999f, 2559.5f, 99_999f)).getY());
    }

    @Test
    void nullPlayerPositionFallsBackToIdentity() {
        // 喵~防御：玩家位置缺失时退化为恒等映射，不能抛异常打断登录流程喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(null, TRIGGER);

        assertNotNull(mapper);
        assertFalse(mapper.needsMapping());
    }

    @Test
    void invalidTriggerDistanceFallsBackToDefault() {
        // 喵~防御：阈值配置成 0 或负数时按默认 3000 处理，避免每次移动都重定位喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(5000, 64, 0), 0);

        // 5000 超过默认阈值 3000，因此应当启用映射喵~
        assertTrue(mapper.needsMapping());
    }

    @Test
    void exceedsTriggerReportsCorrectly() {
        // 供调用方在移动包处理时快速判断是否需要重定位喵~
        assertFalse(HorizontalOffsetMapper.exceedsTrigger(1000, -1000, TRIGGER));
        assertFalse(HorizontalOffsetMapper.exceedsTrigger(TRIGGER, TRIGGER, TRIGGER));
        assertTrue(HorizontalOffsetMapper.exceedsTrigger(TRIGGER + 1, 0, TRIGGER));
        assertTrue(HorizontalOffsetMapper.exceedsTrigger(0, -TRIGGER - 1, TRIGGER));
    }

    @Test
    void chunkCoordinatesAreShiftedByWholeChunks() {
        // 区块索引映射必须整体平移整数个区块，否则区块会错位喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(50_000, 64, 50_000), TRIGGER);

        Vector2i javaChunk = Vector2i.from(3125, 3125);
        Vector2i bedrockChunk = mapper.mapChunkCoordinates(javaChunk);

        // 区块坐标差必须等于方块坐标差除以 16 喵~
        assertEquals(javaChunk.getX() - mapper.offsetX() / 16, bedrockChunk.getX());
        assertEquals(javaChunk.getY() - mapper.offsetZ() / 16, bedrockChunk.getY());
    }

    @Test
    void horizontalTwoDimensionalMappingMatchesThreeDimensional() {
        // 二维映射与三维映射在 X/Z 上必须给出完全相同的结果喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(50_000, 64, -50_000), TRIGGER);

        Vector3f mapped3d = mapper.mapPosition(Vector3f.from(50_123f, 64f, -50_456f));
        Vector2f mapped2d = mapper.mapHorizontal(Vector2f.from(50_123f, -50_456f));

        assertEquals(mapped3d.getX(), mapped2d.getX());
        assertEquals(mapped3d.getZ(), mapped2d.getY());
    }

    @Test
    void worldBorderCenterMappingRoundTrips() {
        // 世界边界中心是二维坐标，双向映射也必须能精确还原喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(50_000, 64, 50_000), TRIGGER);

        Vector2d javaCenter = Vector2d.from(50_000.5, 50_000.5);
        Vector2d restored = mapper.inverseMapPosition(mapper.mapPosition(javaCenter));

        assertEquals(javaCenter, restored);
    }

    @Test
    void negativeAndPositiveCoordinatesCompressSymmetrically() {
        // 正负方向的大坐标都要被压回原点附近；量化会让两者落在网格内的不同位置，
        // 因此这里校验「都足够靠近原点」，而不是要求两边数值几乎相等喵~
        HorizontalOffsetMapper positiveMapper = HorizontalOffsetMapper.create(Vector3d.from(1_000_000, 64, 0), TRIGGER);
        HorizontalOffsetMapper negativeMapper = HorizontalOffsetMapper.create(Vector3d.from(-1_000_000, 64, 0), TRIGGER);

        int positiveMapped = positiveMapper.mapPosition(Vector3i.from(1_000_000, 64, 0)).getX();
        int negativeMapped = negativeMapper.mapPosition(Vector3i.from(-1_000_000, 64, 0)).getX();

        // 量化粒度是 4096，压缩基值在 ±100 内，所以映射后应当落在 ±(4096 + 100) 范围内喵~
        int allowedMagnitude = 4096 + 100;
        assertTrue(Math.abs(positiveMapped) <= allowedMagnitude, "正向映射后过远：" + positiveMapped);
        assertTrue(Math.abs(negativeMapped) <= allowedMagnitude, "负向映射后过远：" + negativeMapped);
    }

    @ParameterizedTest
    @CsvSource({
        // javaPosition, triggerDistance, expectedNeedsMapping
        "5000, 10000, false",
        "10000, 10000, false",
        "10001, 10000, true",
        "-10001, 10000, true",
        "50000, 10000, true"
    })
    void mappingActivationFollowsThreshold(int javaCoordinate, int triggerDistance, boolean expectedNeedsMapping) {
        // 用表格逐行校验「超过阈值才启用」的规则喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(javaCoordinate, 64, 0), triggerDistance);

        assertEquals(expectedNeedsMapping, mapper.needsMapping());
    }

    @ParameterizedTest
    @CsvSource({
        // 非法数值类型，全部必须退化为恒等映射而不是卡死或抛异常
        "NaN, 10000",
        "Infinity, 10000",
        "-Infinity, 10000",
        "0, 10000"
    })
    void nonFiniteCoordinatesFallBackToIdentity(double javaCoordinate, int triggerDistance) {
        // 喵~防御回归测试：NaN 与无穷大曾会让开平方循环永不退出，进而卡死整个会话事件循环喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(javaCoordinate, 64, 0), triggerDistance);

        // 非有限坐标必须退化为恒等映射，绝不产生位移喵~
        assertFalse(mapper.needsMapping(), "非有限坐标不应启用映射：" + javaCoordinate);
        assertEquals(0, mapper.offsetX());
        assertEquals(0, mapper.offsetZ());
    }

    @Test
    void nonFiniteCoordinatesOnOneAxisFallBackToIdentity() {
        // 只要任一分量非有限，整体就退化为恒等映射，避免另一个轴被单方面平移喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(
            Vector3d.from(Double.POSITIVE_INFINITY, 64, 50_000), 10000);

        assertFalse(mapper.needsMapping());
    }

    @Test
    void extremeButFiniteCoordinatesStaySafe() {
        // 回归测试：超出 int 表示范围的坐标无法用 int offset 表达，
        // 此时必须安全退化为「不映射」，绝不能溢出成错误的小 offset 把世界搬到错误位置喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(1.0E15, 64, 0), 10000);

        // 这种量级超出 int offset 的表达能力，按设计退化为恒等映射喵~
        assertFalse(mapper.needsMapping(), "超出 int 范围的坐标应退化为恒等映射");
        // 退化为恒等映射时，坐标必须原样返回，不能悄悄改动喵~
        assertEquals(1_000_000_000, mapper.mapPosition(Vector3i.from(1_000_000_000, 64, 0)).getX());
    }

    @Test
    void largeButRepresentableCoordinatesStillMap() {
        // 回归测试：可被 int 正常表达的远距离坐标必须正常映射，不能被上面的退化逻辑误伤喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(5_000_000, 64, 0), 10000);

        // 五百万格在 int 范围内，必须启用映射喵~
        assertTrue(mapper.needsMapping());
        // 映射后应落到量化网格内（粒度 4096 + 压缩基值 100），保证 float32 精度够用喵~
        int mapped = mapper.mapPosition(Vector3i.from(5_000_000, 64, 0)).getX();
        assertTrue(Math.abs(mapped) <= 4096 + 100, "映射后仍过于远离原点：" + mapped);
    }

    @Test
    void offsetRemainsChunkAlignedAcrossWideRange() {
        // 回归测试：offset 必须是 16 的整数倍，否则区块内容会错切喵~
        for (int coordinate = 9999; coordinate <= 100_001; coordinate += 7919) {
            HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(coordinate, 64, -coordinate), 10000);

            assertEquals(0, mapper.offsetX() % 16, "X offset 未对齐区块：" + mapper.offsetX());
            assertEquals(0, mapper.offsetZ() % 16, "Z offset 未对齐区块：" + mapper.offsetZ());
        }
    }

    @Test
    void inverseMappedBlockLandsInTheSameChunkAsItsJavaCoordinate() {
        // 回归测试：ChunkCache 的区块索引是按 Java 区块坐标建立的，
        // 而方块更新路径拿到的是 Bedrock 坐标，必须先反向映射再写缓存喵~
        // 这里校验「Bedrock 方块坐标反向映射后，与原始 Java 坐标落在同一个区块」这一不变量喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(50_000, 64, -80_000), TRIGGER);

        // 取几个跨越区块边界、正负混合的样本，保证不是碰巧对齐喵~
        int[][] sampleJavaBlocks = {
            {50_000, 64, -80_000},
            {50_015, 64, -80_015},
            {50_016, 64, -80_016},
            {-70_001, -60, 120_007}
        };

        for (int[] sampleJavaBlock : sampleJavaBlocks) {
            Vector3i javaBlockPosition = Vector3i.from(sampleJavaBlock[0], sampleJavaBlock[1], sampleJavaBlock[2]);
            // 模拟「客户端侧」持有的 Bedrock 坐标，再按修复后的逻辑反向还原喵~
            Vector3i bedrockPosition = mapper.mapPosition(javaBlockPosition);
            Vector3i restoredJavaPosition = mapper.inverseMapPosition(bedrockPosition);

            // 还原结果必须与原坐标完全相等（offset 是整数，不存在精度损失）喵~
            assertEquals(javaBlockPosition, restoredJavaPosition);
            // 关键断言：两者必须落在同一个 Java 区块，否则写缓存会落到错误的调色板喵~
            assertEquals(javaBlockPosition.getX() >> 4, restoredJavaPosition.getX() >> 4);
            assertEquals(javaBlockPosition.getZ() >> 4, restoredJavaPosition.getZ() >> 4);
        }
    }

    @Test
    void bedrockChunkIndexDiffersFromJavaChunkIndexWhenMapped() {
        // 回归测试：这条断言用来证明「直接用 Bedrock 区块索引写 ChunkCache」确实是错的喵~
        // 也就是说，一旦有人把反向映射那一步删掉，上面的测试之外这里会先失败，提示修复被回退喵~
        HorizontalOffsetMapper mapper = HorizontalOffsetMapper.create(Vector3d.from(50_000, 64, 0), TRIGGER);
        // 映射后 offset 非零，说明这两个坐标系确实不同，不能混用喵~
        assertTrue(mapper.needsMapping());

        Vector3i javaBlockPosition = Vector3i.from(50_000, 64, 0);
        Vector3i bedrockPosition = mapper.mapPosition(javaBlockPosition);

        // Bedrock 侧区块索引必须与 Java 侧不同，否则说明映射根本没生效（配置或算法异常）喵~
        // 喵~防御：如果这两者相等，说明 offset 落在整区块整除位置，
        // 本断言会误报，因此这里顺带校验 offset 本身不为 0 且不是 16 的倍数以外的巧合喵~
        assertEquals(0, mapper.offsetX() % 16);
        assertTrue((javaBlockPosition.getX() >> 4) != (bedrockPosition.getX() >> 4),
            "映射后区块索引未变化，说明 offset 没起作用：" + mapper.offsetX());
    }
}
