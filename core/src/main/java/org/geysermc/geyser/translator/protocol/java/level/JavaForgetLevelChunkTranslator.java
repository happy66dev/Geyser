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

package org.geysermc.geyser.translator.protocol.java.level;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.Translator;
import org.geysermc.geyser.util.ChunkUtils;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;

import java.util.ArrayList;
import java.util.List;

@Translator(packet = ClientboundForgetLevelChunkPacket.class)
public class JavaForgetLevelChunkTranslator extends PacketTranslator<ClientboundForgetLevelChunkPacket> {

    @Override
    public void translate(GeyserSession session, ClientboundForgetLevelChunkPacket packet) {
        // 区块缓存以 Java 坐标索引，卸载时也用 Java 坐标，不能混用 Bedrock 坐标喵~
        session.getChunkCache().removeChunk(packet.getX(), packet.getZ());

        // 自定义头颅缓存以 Bedrock 坐标索引，比较前先把 Java 区块索引翻译成 Bedrock 区块索引喵~
        int[] bedrockChunkCoordinates = session.mapChunkCoordinates(packet.getX(), packet.getZ());
        int bedrockChunkX = bedrockChunkCoordinates[0];
        int bedrockChunkZ = bedrockChunkCoordinates[1];

        // Checks if a skull is in an unloaded chunk then removes it
        List<Vector3i> removedSkulls = new ArrayList<>();
        for (Vector3i position : session.getSkullCache().getSkulls().keySet()) {
            if ((position.getX() >> 4) == bedrockChunkX && (position.getZ() >> 4) == bedrockChunkZ) {
                removedSkulls.add(position);
            }
        }
        removedSkulls.forEach(session.getSkullCache()::removeSkull);

        // 空区块包发给 Bedrock 客户端，同样要使用 Bedrock 区块索引喵~
        ChunkUtils.sendEmptyChunk(session, bedrockChunkX, bedrockChunkZ, false);
    }
}
