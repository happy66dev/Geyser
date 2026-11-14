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

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.geysermc.geyser.level.particle.ParticleRandomization;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.Particle;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelParticlesPacket;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.level.particle.ParticleMapping;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.Translator;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

@Translator(packet = ClientboundLevelParticlesPacket.class)
public class JavaLevelParticlesTranslator extends PacketTranslator<ClientboundLevelParticlesPacket> {
    private static final int MAX_PARTICLES = 100;

    @Override
    public void translate(GeyserSession session, ClientboundLevelParticlesPacket packet) {
        Particle particle = packet.getParticle();
        ParticleMapping particleMapping = Registries.PARTICLES.get(particle.getType());

        if (particleMapping != null) {
            Function<ParticleRandomization, BedrockPacket> packetConstructor = particleMapping.mapParticle(session, particle.getData());
            Random random = ThreadLocalRandom.current();
            if (packet.getAmount() == 0) {
                // 0 means don't apply the offset
                session.sendUpstreamPacket(packetConstructor.apply(mapRandomizationCoordinates(session, ParticleRandomization.ofPacket(packet, random))));
            } else {
                int amount = Math.min(MAX_PARTICLES, packet.getAmount());
                for (int i = 0; i < amount; i++) {
                    session.sendUpstreamPacket(packetConstructor.apply(mapRandomizationCoordinates(session, ParticleRandomization.ofPacket(packet, random))));
                }
            }
        } else {
            // Null is only returned when no particle of this type is found
            session.getGeyser().getLogger().debug("Unhandled particle packet: " + packet);
        }
    }

    /**
     * 把粒子随机化结果里的坐标从 Java 服务端坐标系换算成 Bedrock 客户端坐标系喵~
     * <p>
     * 输入：session 为当前玩家会话，randomization 为上游按 Java 原始坐标算好的粒子位置与速度喵~
     * 输出：X/Y/Z 三轴都已映射的新粒子随机化对象；不需要映射时原样返回喵~
     * 边界：用不 clamp 的版本，允许粒子超出维度高度范围喵~
     */
    private static ParticleRandomization mapRandomizationCoordinates(GeyserSession session, ParticleRandomization randomization) {
        // 粒子位置属于 Java 坐标系，X/Y/Z 三轴都要映射，只改 Y 会让粒子跑到错误的水平位置喵~
        Vector3f mappedPosition = session.mapPositionUnclamped(randomization.position());
        // 速度辅助量属于方向性数据，不参与坐标平移，原样透传喵~
        return new ParticleRandomization(mappedPosition, randomization.xAux(), randomization.yAux(), randomization.zAux());
    }
}
