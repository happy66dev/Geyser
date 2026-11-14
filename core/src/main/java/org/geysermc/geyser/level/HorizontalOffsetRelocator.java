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
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.util.ChunkUtils;

/**
 * 负责在玩家超出阈值时重新计算水平 offset，并把整个客户端世界状态一起搬过去喵~
 * <p>
 * 背景：{@link HorizontalOffsetMapper} 只负责算数，但换了 offset 之后客户端认知的坐标就全变了，
 * 已有的实体、区块、世界边界、未确认传送缓存都停留在旧坐标系里，必须同步搬移，否则会看到
 * 实体错位、方块消失、无头苍蝇式抖动喵~ 本类就是「算 offset」和「让世界跟上」之间的桥喵~
 *
 * <h2>整体思路</h2>
 * <ol>
 *     <li>拿到玩家的 Java 真实坐标（客户端当前坐标反向还原）喵~</li>
 *     <li>按配置阈值判断是否需要重算 offset，不需要就直接返回，成本接近零喵~</li>
 *     <li>算出新 offset，若与旧 offset 相同也直接返回，避免白白刷新一遍世界喵~</li>
 *     <li>把所有已存在实体的坐标从旧 offset 空间平移到新 offset 空间喵~</li>
 *     <li>让区块缓存与客户端区块重新对齐（卸载旧范围、重新请求新范围）喵~</li>
 *     <li>刷新世界边界、未确认传送缓存与坐标显示规则喵~</li>
 * </ol>
 *
 * <h2>输入输出与边界</h2>
 * <ul>
 *     <li>输入：一次玩家移动后的 Java 坐标；可选的 javaY 用于修正实体高度喵~</li>
 *     <li>输出：无返回值，失败时静默跳过本轮（下一帧还会再试），绝不抛异常打断移动包处理喵~</li>
 *     <li>边界：off 配置、未登录、未生成、玩家位置缺失、offset 未变化时都不做任何事喵~</li>
 * </ul>
 */
public final class HorizontalOffsetRelocator {

    /** 会话对象，用来读写映射器与各类缓存喵~ */
    private final GeyserSession session;

    /** 触发重定位的距离阈值，单位：格；来自配置，只读喵~ */
    private final int triggerDistance;

    /** 水平映射总开关，来自配置，只读喵~ */
    private final boolean mappingEnabled;

    /**
     * 构造重定位器喵~
     *
     * @param session 目标玩家会话，不能为 null
     * @param triggerDistance 触发重定位的距离阈值，单位：格
     * @param mappingEnabled 水平映射总开关
     */
    public HorizontalOffsetRelocator(GeyserSession session, int triggerDistance, boolean mappingEnabled) {
        this.session = session;
        this.triggerDistance = triggerDistance;
        this.mappingEnabled = mappingEnabled;
    }

    /**
     * 在玩家位置变化后检查是否需要重新对齐水平 offset 喵~
     * <p>
     * 这是唯一对外入口，逐帧调用也很便宜：开关关闭或未超阈值时只做两次绝对值比较喵~
     *
     * @param javaPosition 玩家 <b>Java 坐标系</b> 中的最新位置，不能为 null
     */
    public void checkRelocate(Vector3d javaPosition) {
        // 喵~防御：映射总开关关闭时不参与任何重算，保证未启用时行为与上游完全一致喵~
        if (!mappingEnabled) {
            return;
        }
        // 喵~防御：位置缺失或尚未登录生成时无法判断，跳过本轮而不是抛空指针喵~
        if (javaPosition == null || !session.isLoggedIn() || !session.isSpawned()) {
            return;
        }

        // 玩家还在阈值内且当前也没启用映射，说明仍在原点附近，直接跳过喵~
        boolean currentlyMapped = session.getHorizontalOffsetMapper().needsMapping();
        if (!currentlyMapped && !HorizontalOffsetMapper.exceedsTrigger(javaPosition.getX(), javaPosition.getZ(), triggerDistance)) {
            return;
        }

        // 按最新玩家位置算出应有的 offset；超出阈值的分量会压到原点附近喵~
        HorizontalOffsetMapper newMapper = HorizontalOffsetMapper.create(javaPosition, triggerDistance);

        // offset 没变化时不要刷新世界，否则每次移动都会触发一次区块重发，客户端会卡顿喵~
        if (newMapper.offsetX() == session.getHorizontalOffsetMapper().offsetX()
            && newMapper.offsetZ() == session.getHorizontalOffsetMapper().offsetZ()) {
            return;
        }

        applyOffset(newMapper, javaPosition);
    }

    /**
     * 真正执行一次 offset 切换，把客户端世界搬到新坐标系喵~
     * <p>
     * 输入：已经算好的新映射器与玩家 Java 坐标；
     * 输出：无；效果是所有实体、区块、边界、传送缓存都换到新坐标系喵~
     *
     * @param newMapper 新的水平映射器，不能为 null
     * @param javaPosition 玩家 Java 坐标系中的最新位置，不能为 null
     */
    private void applyOffset(HorizontalOffsetMapper newMapper, Vector3d javaPosition) {
        HorizontalOffsetMapper oldMapper = session.getHorizontalOffsetMapper();

        // 计算两个坐标系之间的平移量：旧空间坐标 + delta = 新空间坐标喵~
        // 推导：旧空间 = java - oldOffset，新空间 = java - newOffset，因此 delta = oldOffset - newOffset 喵~
        int deltaX = oldMapper.offsetX() - newMapper.offsetX();
        int deltaZ = oldMapper.offsetZ() - newMapper.offsetZ();
        Vector3f delta = Vector3f.from(deltaX, 0, deltaZ);

        // 先把映射器换掉，后续所有映射调用（含区块重发）都会用新坐标系喵~
        session.setHorizontalOffsetMapper(newMapper);

        // 玩家实体自己也要平移到位，否则会出现「世界搬了、玩家没搬」的错位喵~
        // 直接按新坐标系重新算一遍位置，比在旧坐标上加 delta 更能避免累积浮点误差喵~
        SessionPlayerEntity player = session.getPlayerEntity();
        player.setPosition(session.mapPosition(javaPosition.toFloat()));

        // 其余实体逐个平移后重新生成，让客户端按新坐标收到完整的生成包喵~
        relocateEntities(player, delta);

        // 区块缓存以 Java 坐标索引，但客户端区块索引变了，必须重新发布一次区块中心喵~
        Vector3i bedrockPlayerPosition = session.mapPosition(javaPosition.toFloat()).toInt();
        // 世界边界中心是 Java 坐标，重算一遍才能得到新坐标系下的边界值喵~
        session.getWorldBorder().update();
        // 原生坐标显示规则依赖映射状态，切换坐标系后必须重新下发一次，否则会显示假坐标喵~
        session.getPreferencesCache().updateShowCoordinates();

        // 清掉记住的区块位置，强制下一次 updateChunkPosition 一定重新发布区块范围喵~
        session.setLastChunkPosition(null);
        // 让 Java 服务端重新下发周围区块，这样客户端会以新坐标重新填充世界喵~
        ChunkUtils.updateChunkPosition(session, bedrockPlayerPosition);

        if (session.getGeyser().config().debugMode()) {
            session.getGeyser().getLogger().info("[horizontal-map] relocated: javaPosition=" + javaPosition
                + ", oldOffsetX=" + oldMapper.offsetX() + ", oldOffsetZ=" + oldMapper.offsetZ()
                + ", newOffsetX=" + newMapper.offsetX() + ", newOffsetZ=" + newMapper.offsetZ()
                + ", bedrockPlayerPosition=" + bedrockPlayerPosition);
        }
    }

    /**
     * 把所有已存在实体从旧坐标系平移到新坐标系并重新生成喵~
     * <p>
     * 输入：玩家实体（跳过它，前面已单独处理）与坐标平移量；
     * 输出：无；效果是每个实体先改内部坐标、再 despawn + spawn，
     * 让 Bedrock 客户端拿到与新坐标系一致的实体位置喵~
     *
     * @param player 玩家实体，不能为 null
     * @param delta 坐标平移量，单位：格
     */
    private void relocateEntities(SessionPlayerEntity player, Vector3f delta) {
        // 主人注意：这里遍历的是全部已加载实体，实体数量通常在数百级别；
        // 每次重定位只发生一次且只遍历一遍，复杂度 O(n)，不会成为热点喵~
        for (Entity entity : session.getEntityCache().getEntitiesUnsafe().values()) {
            // 玩家实体已经在调用方单独平移过，这里跳过避免重复移位喵~
            if (entity == player) {
                continue;
            }
            // 先在新坐标系里定位实体，再让客户端重新创建它喵~
            entity.setPosition(entity.position().add(delta));
            entity.despawnEntity();
            entity.spawnEntity();
        }
    }
}
