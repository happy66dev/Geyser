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

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.geysermc.geyser.api.block.custom.CustomBlockState;
import org.geysermc.geyser.entity.VanillaEntities;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.entity.type.player.SkullPlayerEntity;
import org.geysermc.geyser.level.block.property.Properties;
import org.geysermc.geyser.level.block.type.BlockState;
import org.geysermc.geyser.level.block.type.WallSkullBlock;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.type.CustomSkull;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.texture.Texture;
import org.geysermc.mcprotocollib.auth.texture.TextureType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SkullCache {
    private static final Comparator<Skull> DISTANCE_COMPARATOR = Comparator.comparingInt(Skull::getDistanceSquared);
    private final int maxVisibleSkulls;
    private final boolean cullingEnabled;

    private final int skullRenderDistanceSquared;

    @Getter
    private final Map<Vector3i, Skull> skulls = new Object2ObjectOpenHashMap<>();

    private final List<Skull> inRangeSkulls = new ArrayList<>();

    private int totalSkullEntities = 0;

    private final GeyserSession session;

    private Vector3f lastPlayerPosition;

    public SkullCache(GeyserSession session) {
        this.session = session;
        this.maxVisibleSkulls = session.getGeyser().config().gameplay().maxVisibleCustomSkulls();
        this.cullingEnabled = this.maxVisibleSkulls != -1;

        // Normal skulls are not rendered beyond 64 blocks
        int distance = Math.min(session.getGeyser().config().gameplay().customSkullRenderDistance(), 64);
        this.skullRenderDistanceSquared = distance * distance;
    }

    public @Nullable Skull putSkull(Vector3i position, GameProfile resolved, BlockState blockState) {
        Texture texture;
        try {
            texture = resolved.getTexture(TextureType.SKIN, false);
        } catch (IllegalStateException e) {
            session.getGeyser().getLogger().debug("Player skull with invalid skin found at " + position + " with texture payload " + resolved.getProperty("textures"));
            return null;
        }
        if (texture != null) {
            return putSkull(position, resolved.getId(), texture.getURL(), texture.getHash(), blockState);
        }
        return null;
    }

    public Skull putSkull(Vector3i position, UUID uuid, String skinUrl, String skinHash, BlockState blockState) {
        Skull skull = skulls.computeIfAbsent(position, Skull::new);
        skull.uuid = uuid;
        skull.skinUrl = skinUrl;
        skull.skinHash = skinHash;
        skull.blockState = blockState;
        skull.blockDefinition = translateCustomSkull(skull.skinHash, blockState);

        if (skull.blockDefinition != null) {
            reassignSkullEntity(skull);
            return skull;
        }

        if (skull.entity != null) {
            skull.entity.updateSkull(skull);
        } else {
            if (!cullingEnabled) {
                assignSkullEntity(skull);
                return skull;
            }
            if (lastPlayerPosition == null) {
                return skull;
            }
            skull.distanceSquared = position.distanceSquared(lastPlayerPosition.getX(), lastPlayerPosition.getY(), lastPlayerPosition.getZ());
            if (skull.distanceSquared < skullRenderDistanceSquared) {
                // Keep list in order
                int i = Collections.binarySearch(inRangeSkulls, skull, DISTANCE_COMPARATOR);
                if (i < 0) { // skull.distanceSquared is a new distance value
                    i = -i - 1;
                }
                inRangeSkulls.add(i, skull);

                if (i < maxVisibleSkulls) {
                    // Reassign entity from the farthest skull to this one
                    if (inRangeSkulls.size() > maxVisibleSkulls) {
                        freeSkullEntity(inRangeSkulls.get(maxVisibleSkulls));
                    }
                    assignSkullEntity(skull);
                }
            }
        }
        return skull;
    }

    public void removeSkull(Vector3i position) {
        Skull skull = skulls.remove(position);
        if (skull != null) {
            reassignSkullEntity(skull);
        }
    }

    public Skull updateSkull(Vector3i position, BlockState blockState) {
        Skull skull = skulls.get(position);
        if (skull != null) {
            putSkull(position, skull.uuid, skull.skinUrl, skull.skinHash, blockState);
        }
        return skull;
    }

    public void updateVisibleSkulls() {
        if (cullingEnabled) {
            // No need to recheck skull visibility for small movements
            if (lastPlayerPosition != null && session.getPlayerEntity().position().distanceSquared(lastPlayerPosition) < 4) {
                return;
            }
            lastPlayerPosition = session.getPlayerEntity().position();

            inRangeSkulls.clear();
            for (Skull skull : skulls.values()) {
                if (skull.blockDefinition != null) {
                    continue;
                }

                skull.distanceSquared = skull.position.distanceSquared(lastPlayerPosition.getX(), lastPlayerPosition.getY(), lastPlayerPosition.getZ());
                if (skull.distanceSquared > skullRenderDistanceSquared) {
                    freeSkullEntity(skull);
                } else {
                    inRangeSkulls.add(skull);
                }
            }
            inRangeSkulls.sort(DISTANCE_COMPARATOR);

            for (int i = inRangeSkulls.size() - 1; i >= 0; i--) {
                if (i < maxVisibleSkulls) {
                    assignSkullEntity(inRangeSkulls.get(i));
                } else {
                    freeSkullEntity(inRangeSkulls.get(i));
                }
            }
        }
    }

    private void assignSkullEntity(Skull skull) {
        if (skull.entity != null) {
            return;
        }
        if (!cullingEnabled || totalSkullEntities < maxVisibleSkulls) {
            // Create a new entity
            skull.entity = new SkullPlayerEntity(EntitySpawnContext.DUMMY_CONTEXT.apply(session, UUID.randomUUID(), VanillaEntities.PLAYER));
            skull.entity.spawnEntity();
            skull.entity.updateSkull(skull);
            totalSkullEntities++;
        }
    }

    private void freeSkullEntity(Skull skull) {
        if (skull.entity != null) {
            skull.entity.despawnEntity();
            totalSkullEntities--;
            skull.entity = null;
        }
    }

    private void reassignSkullEntity(Skull skull) {
        boolean hadEntity = skull.entity != null;
        freeSkullEntity(skull);

        if (cullingEnabled) {
            inRangeSkulls.remove(skull);
            if (hadEntity && inRangeSkulls.size() >= maxVisibleSkulls) {
                // Reassign entity to the closest skull without an entity
                assignSkullEntity(inRangeSkulls.get(maxVisibleSkulls - 1));
            }
        }
    }

    public void clear() {
        for (Skull skull : skulls.values()) {
            if (skull.entity != null) {
                skull.entity.despawnEntity();
            }
        }
        skulls.clear();
        inRangeSkulls.clear();
        totalSkullEntities = 0;
        lastPlayerPosition = null;
    }

    /**
     * 水平坐标映射切换 offset 时，把所有自定义头颅从旧坐标系搬到新坐标系喵~
     * <p>
     * 输入：坐标平移量（旧空间 + delta = 新空间），单位：格；
     * 输出：无。效果是缓存 key、剔除用的距离计算与实体位置全部换到新坐标系喵~
     *
     * <h2>为什么必须搬</h2>
     * <p>
     * 这里的 key 是 Bedrock 坐标，距离剔除也是拿玩家 Bedrock 位置比的；
     * offset 一变，所有头颅都会凭空「远了上一整段平移量」，
     * 结果就是头颅在剔除半径边缘忽隐忽现、按位置移除也从此找不到它喵~
     *
     * @param delta 坐标平移量，单位：格
     */
    public void relocateBy(Vector3f delta) {
        // 喵~防御：平移量为零（理论上不会发生）时直接返回，避免无谓地重建整张表喵~
        if (delta.getX() == 0f && delta.getZ() == 0f) {
            return;
        }

        // 主人注意：头颅数量受 max-visible-custom-skulls 限制，通常只有几十个，
        // 整体重建一次是 O(n)，只在 offset 切换时发生，不会成为性能热点喵~

        // 先收集所有条目，避免边遍历边改 map 触发并发修改异常喵~
        List<Map.Entry<Vector3i, Skull>> snapshot = new ArrayList<>(skulls.entrySet());
        skulls.clear();

        // 实体与展示框都用 Bedrock 坐标平移；这里必须与 HorizontalOffsetRelocator 的 delta 朝向一致喵~
        Vector3i integerDelta = delta.toInt();
        for (Map.Entry<Vector3i, Skull> entry : snapshot) {
            // 用新的方块坐标作为 key 重新塞回表里喵~
            Vector3i newPosition = entry.getKey().add(integerDelta);
            Skull skull = entry.getValue();
            skulls.put(newPosition, skull);
            // Skull 内部也存了一份坐标，距离剔除与后续移除都用它，必须同步更新喵~
            // 喵~防御：若漏掉这一步，updateVisibleSkulls 会拿旧坐标算距离，
            // 重定位后所有头的可见性判断都会错位喵~
            skull.setPosition(newPosition);
            // 装饰实体本身也要跟着搬，否则它仍渲染在旧坐标系的位置上喵~
            if (skull.entity != null) {
                skull.entity.setPosition(skull.entity.position().add(delta));
            }
        }

        // 距离剔除列表依赖玩家位置，搬完后必须重算一次，否则会按旧坐标判断可见性喵~
        inRangeSkulls.clear();
        // 喵~防御：不能直接把计数器清零！已存在的头颅实体并没有被回收，
        // 清零会让 assignSkullEntity 以为还有名额，于是重复创建实体造成内存与网络包泄漏喵~
        // 这里改为按实际仍持有实体的头颅重新统计一次，保证计数与真实情况一致喵~
        int liveSkullEntityCount = 0;
        for (Skull skull : skulls.values()) {
            if (skull.entity != null) {
                liveSkullEntityCount++;
            }
        }
        totalSkullEntities = liveSkullEntityCount;
        lastPlayerPosition = null;
    }

    private @Nullable BlockDefinition translateCustomSkull(@Nullable String skinHash, BlockState blockState) {
        if (skinHash == null) {
            return null;
        }

        CustomSkull customSkull = BlockRegistries.CUSTOM_SKULLS.get(skinHash);
        if (customSkull != null) {
            CustomBlockState customBlockState;
            if (blockState.block() instanceof WallSkullBlock) {
                customBlockState = customSkull.getWallBlockState(WallSkullBlock.getDegrees(blockState));
            } else {
                customBlockState = customSkull.getFloorBlockState(blockState.getValue(Properties.ROTATION_16));
            }

            return session.getBlockMappings().getCustomBlockStateDefinitions().get(customBlockState);
        }
        return null;
    }

    @Getter
    @Setter
    public static class Skull {
        private UUID uuid;
        private String skinHash;
        private String skinUrl;

        private BlockState blockState;
        private BlockDefinition blockDefinition;
        private SkullPlayerEntity entity;

        /**
         * 头颅所在的 Bedrock 方块坐标喵~
         * <p>
         * 不能是 final：水平坐标映射切换 offset 时整个客户端世界都会平移，
         * {@link SkullCache#relocateBy(org.cloudburstmc.math.vector.Vector3f)} 必须同步更新这个坐标，
         * 否则距离剔除与按位置移除都会按旧坐标判断，重定位后头颅会莫名消失或残留喵~
         */
        private Vector3i position;
        private int distanceSquared;

        /**
         * 显式构造器，供 <code>skulls.computeIfAbsent</code> 的方法引用与 lambda 使用喵~
         * <p>
         * 去掉 lombok 的 {@code @RequiredArgsConstructor} 是因为 position 不再是 final 字段，
         * 生成不出只接收 position 的构造器，这里直接手写一个语义完全一致的版本喵~
         *
         * @param position 头颅所在的 Bedrock 方块坐标，不能为 null
         */
        public Skull(Vector3i position) {
            // 喵~防御：坐标缺失时用原点占位，避免后续 distanceSquared 计算直接空指针喵~
            this.position = position == null ? Vector3i.ZERO : position;
        }
    }
}
