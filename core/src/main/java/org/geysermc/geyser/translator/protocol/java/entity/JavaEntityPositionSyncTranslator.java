/*
 * Copyright (c) 2024 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.translator.protocol.java.entity;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.vehicle.ClientVehicle;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.Translator;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;

import java.util.Objects;

@Translator(packet = ClientboundEntityPositionSyncPacket.class)
public class JavaEntityPositionSyncTranslator extends PacketTranslator<ClientboundEntityPositionSyncPacket> {

    @Override
    public void translate(GeyserSession session, ClientboundEntityPositionSyncPacket packet) {
        Entity entity = session.getEntityCache().getEntityByJavaId(packet.getId());
        if (entity == null) return;

        // Java 服务端下发的原始坐标，Y 轴此时仍是 Java 坐标系喵~
        Vector3d javaPos = packet.getEndPosition();
        if (javaPos == null) {
            // 喵~防御：上游允许同步包结束位置为空，此时回退到最后一个插值步骤的坐标喵~
            javaPos = Objects.requireNonNull(packet.getSteps()).getLast().position();
        }
        // 实体位置同步 Y 轴仅加 offset，不 clamp，允许实体处于维度高度范围之外喵~
        Vector3f target = session.mapPositionUnclamped(javaPos.toFloat());
        // Compute before the cached position is overwritten. Java clients snap rather than
        // interpolate a sync farther than 64 blocks; Bedrock needs teleport semantics there too
        boolean teleported = entity.position().distanceSquared(target) > 4096;

        if (entity instanceof ClientVehicle clientVehicle) {
            // Ignore if player is controlling
            if (clientVehicle.shouldSimulateMovement()) {
                return;
            }
            // 载具组件保存的也是 Bedrock 坐标，这里必须传入映射后的 target 喵~
            clientVehicle.getVehicleComponent().moveAbsolute(target.getX(), target.getY(), target.getZ());
        }

        // As in Entity#teleport, the head yaw follows the yaw
        entity.moveAbsolute(target, packet.getYRot(), packet.getXRot(), packet.getYRot(), packet.isOnGround(), teleported);
    }
}
