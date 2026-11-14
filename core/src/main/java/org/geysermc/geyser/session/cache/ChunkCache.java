/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.session.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;
import org.geysermc.geyser.level.block.type.Block;
import org.geysermc.geyser.level.chunk.GeyserChunk;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.util.MathUtils;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.DataPalette;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

public class ChunkCache {
    @Getter
    private final boolean cache;
    private final Long2ObjectMap<GeyserChunk> chunks;

    /**
     * 原始 Java 区块包缓存，键是「Java 区块坐标」打包成的 long 喵~
     * <p>
     * 用途：水平坐标映射切换 offset 时，客户端已加载的区块必须按新坐标系重新下发，
     * 而 Geyser 内部的方块调色板已经丢失了原始区块数据（光照、生物群系、高度图等），
     * 只有保留 Java 原始包才能完整重放喵~ 代价是每个区块多存一份原始 payload，
     * 但换来的是重定位后世界不会半加载或重叠喵~
     * <p>
     * 该缓存受配置项 <code>horizontal-mapping-chunk-replay</code> 控制，默认开启；
     * 关掉后不再占用内存，但重定位后要等 Java 服务端重新下发区块世界才恢复喵~
     */
    private final Long2ObjectMap<ClientboundLevelChunkWithLightPacket> javaChunkPackets;

    /** 实时读取「是否启用区块重放」配置的函数，保证 /geyser reload 对已登录玩家立刻生效喵~ */
    private final BooleanSupplier chunkReplaySupplier;

    @Setter
    private int minY;
    @Setter
    private int heightY;

    public ChunkCache(GeyserSession session) {
        this.cache = !session.getGeyser().getWorldManager().hasOwnChunkCache(); // To prevent Spigot from initializing
        chunks = cache ? new Long2ObjectOpenHashMap<>() : null;
        // 原始包缓存表只在「Geyser 自己持有区块数据」时才建立，Spigot 等自带区块管理的服务端不占这份内存喵~
        javaChunkPackets = cache ? new Long2ObjectOpenHashMap<>() : null;
        // 存成 Supplier 而不是构造时取一次快照：配置在运行期可以通过 /geyser reload 修改，
        // 若抓成常量，已登录的玩家必须重连才会生效喵~
        // 喵~防御：必须同时看「水平映射总开关」，映射根本没启用的服务器永远不需要重放缓存，
        // 否则会给每个玩家白白多留几十 MB 内存，这是默认配置下的纯浪费喵~
        this.chunkReplaySupplier = () ->
            session.getGeyser().config().horizontalCoordinateMapping()
                && session.getGeyser().config().horizontalMappingChunkReplay();
    }

    /**
     * 当前是否启用了原始区块包缓存喵~
     *
     * @return 启用时为 true；服务器自带区块管理或配置关闭时为 false 喵~
     */
    public boolean isChunkReplayEnabled() {
        // 喵~防御：服务器自带区块管理时根本没有这张表，直接返回 false 喵~
        if (javaChunkPackets == null) {
            return false;
        }
        // 喵~防御：配置读取可能因重载而短暂异常，异常按「关闭」处理以节省内存喵~
        try {
            return chunkReplaySupplier.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 记录一份 Java 原始区块包，供 offset 切换时重放喵~
     * <p>
     * 输入：Java 坐标系中的区块坐标与原始包；输出：无。
     * 边界：缓存关闭（服务器自带区块管理或配置禁用）时直接忽略，不做任何存储喵~
     *
     * @param chunkX Java 坐标系中的区块 X 索引
     * @param chunkZ Java 坐标系中的区块 Z 索引
     * @param packet 原始 Java 区块包，不能为 null
     */
    public void cacheJavaPacket(int chunkX, int chunkZ, ClientboundLevelChunkWithLightPacket packet) {
        // 喵~防御：缓存关闭或包为空时什么都不做，避免无谓占用内存喵~
        if (javaChunkPackets == null || packet == null) {
            return;
        }
        // 喵~防御：配置禁用重放时也不存，保证关闭后不再增长内存喵~
        if (!isChunkReplayEnabled()) {
            return;
        }
        javaChunkPackets.put(MathUtils.chunkPositionToLong(chunkX, chunkZ), packet);
    }

    /**
     * 取出当前已缓存的全部 Java 原始区块包，供重定位时重放喵~
     *
     * @return 原始包集合的只读快照；缓存关闭时返回空集合喵~
     */
    public Collection<ClientboundLevelChunkWithLightPacket> getJavaChunkPackets() {
        // 喵~防御：缓存表不存在（服务器自带区块管理）时返回空集合而不是 null，调用方无需判空喵~
        if (javaChunkPackets == null || javaChunkPackets.isEmpty()) {
            return Collections.emptyList();
        }
        // 配置在运行期被关掉时，顺手把已经攒下的包丢掉，避免白白占着内存喵~
        if (!isChunkReplayEnabled()) {
            javaChunkPackets.clear();
            return Collections.emptyList();
        }
        return List.copyOf(javaChunkPackets.values());
    }

    public void addToCache(int x, int z, DataPalette[] chunks) {
        if (!cache) {
            return;
        }

        long chunkPosition = MathUtils.chunkPositionToLong(x, z);
        GeyserChunk geyserChunk = GeyserChunk.from(chunks);
        this.chunks.put(chunkPosition, geyserChunk);
    }

    /**
     * Doesn't check for cache enabled, so don't use this without checking that first!
     */
    private GeyserChunk getChunk(int chunkX, int chunkZ) {
        long chunkPosition = MathUtils.chunkPositionToLong(chunkX, chunkZ);
        return chunks.getOrDefault(chunkPosition, null);
    }

    /**
     * Doesn't check for cache enabled, so don't use this without checking that first!
     */
    @Deprecated
    public DataPalette getChunkSection(int chunkX, int chunkY, int chunkZ, boolean createIfAbsent) {
        GeyserChunk chunk = this.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            return null;
        }

        if (chunkY < getChunkMinY() || chunkY - getChunkMinY() > chunk.sections().length - 1) {
            return null;
        }

        DataPalette palette = chunk.sections()[chunkY - getChunkMinY()];
        if (createIfAbsent && palette == null) {
            palette = DataPalette.createForBlockState(Block.JAVA_AIR_ID, BlockRegistries.BLOCK_STATES.get().size());
            chunk.sections()[chunkY - getChunkMinY()] = palette;
        }

        return palette;
    }

    public void updateBlock(int x, int y, int z, int block) {
        if (!cache) {
            return;
        }

        DataPalette palette = this.getChunkSection(x >> 4, y >> 4, z >> 4, true);
        if (palette == null) {
            return;
        }

        palette.set(x & 0xF, y & 0xF, z & 0xF, block);
    }

    public int getBlockAt(int x, int y, int z) {
        if (!cache) {
            return Block.JAVA_AIR_ID;
        }

        DataPalette palette = this.getChunkSection(x >> 4, y >> 4, z >> 4, false);
        if (palette == null) {
            return Block.JAVA_AIR_ID;
        }

        return palette.get(x & 0xF, y & 0xF, z & 0xF);
    }

    public void removeChunk(int chunkX, int chunkZ) {
        if (!cache) {
            return;
        }

        long chunkPosition = MathUtils.chunkPositionToLong(chunkX, chunkZ);
        chunks.remove(chunkPosition);
        // 原始包缓存必须同步移除，否则卸载的区块会在重定位时被重新发回客户端喵~
        if (javaChunkPackets != null) {
            javaChunkPackets.remove(chunkPosition);
        }
    }

    /**
     * Manually clears all entries in the chunk cache.
     * The server is responsible for clearing chunk entries if out of render distance (for example) or switching dimensions,
     * but it is the client that must clear sections in the event of proxy switches.
     */
    public void clear() {
        if (!cache) {
            return;
        }

        chunks.clear();
        // 原始包缓存一起清空，避免跨维度或代理切换后残留旧世界数据喵~
        if (javaChunkPackets != null) {
            javaChunkPackets.clear();
        }
    }

    /**
     * 只清空原始 Java 包缓存，保留方块调色板缓存喵~
     * <p>
     * 用途：水平坐标映射切换 offset 后，旧坐标系下的原始包已经不能再用，
     * 但方块调色板本身与坐标系无关、仍然有效，因此只丢原始包喵~
     */
    public void clearJavaPacketCache() {
        // 喵~防御：缓存关闭时无需处理喵~
        if (javaChunkPackets == null) {
            return;
        }
        javaChunkPackets.clear();
    }

    public int getChunkMinY() {
        return minY >> 4;
    }

    public int getChunkHeightY() {
        return heightY >> 4;
    }
}
