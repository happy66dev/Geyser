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

package org.geysermc.geyser.translator.protocol.java.entity.player;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.TeleportCache;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.Translator;
import org.geysermc.geyser.util.ChunkUtils;
import org.geysermc.geyser.util.MathUtils;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;

@Translator(packet = ClientboundPlayerPositionPacket.class)
public class JavaPlayerPositionTranslator extends PacketTranslator<ClientboundPlayerPositionPacket> {

    @Override
    public void translate(GeyserSession session, ClientboundPlayerPositionPacket packet) {
        if (!session.isLoggedIn()) {
            return;
        }

        final SessionPlayerEntity entity = session.getPlayerEntity();
        if (entity.getVehicle() != null) { // Vanilla behaviour, the player ignores teleport if they're on a vehicle.
            // 玩家实体存储 Bedrock 坐标；回传 Java 服务端前必须反向映射喵~
            acceptTeleport(session, session.inverseMapPosition(entity.position()).toDouble(), entity.getJavaYaw(), entity.getPitch(), packet.getId());
            // 载具状态下忽略传送，同时清空床位交互预期，避免后续床位判定误判喵~
            entity.setPendingBedInteraction(null);
            return;
        }

        // 相对传送必须先在 Java 坐标系里加算，避免映射后的 Bedrock 坐标参与 Java 端计算喵~
        Vector3d javaEntityPos = session.inverseMapPosition(entity.position()).toDouble();
        Vector3d javaTeleportPosition = packet.getPosition().add(
            packet.getRelatives().contains(PositionElement.X) ? javaEntityPos.getX() : 0,
            packet.getRelatives().contains(PositionElement.Y) ? javaEntityPos.getY() : 0,
            packet.getRelatives().contains(PositionElement.Z) ? javaEntityPos.getZ() : 0);

        // 仅 Bedrock 侧状态、传送包与确认缓存使用映射后的 float 坐标喵~
        Vector3d position = session.mapPositionUnclamped(javaTeleportPosition.toFloat()).toDouble();

        float newPitch = MathUtils.clamp(packet.getXRot() + (packet.getRelatives().contains(PositionElement.X_ROT) ? entity.getPitch() : 0), -90, 90);
        float newYaw = packet.getYRot() + (packet.getRelatives().contains(PositionElement.Y_ROT) ? entity.getYaw() : 0);

        final int teleportId = packet.getId();

        // acceptTeleport should only be called at the end of all translation handling (incl. bed, spawn, and regular teleport handling)
        // granted, vehicle handling calls it immediately as the teleport is ignored, so it can safely confirm
        // We shouldn't accept the teleport immediately (the method call was originally here) as we need the packets to be sent in order
        // teleport packet -> ping packet -> reply = client processed teleport (same logic as Java clients)
        // for beds specifically, the Bedrock client doesn't care and has handled it, so we don't translate it, but the backend still expects a response
        // so we call acceptTeleport there as well

        if (!session.isSpawned()) {
            entity.setPosition(position.toFloat());
            entity.setYaw(packet.getYRot());
            entity.setPitch(packet.getXRot());
            entity.setHeadYaw(packet.getYRot());

            RespawnPacket respawnPacket = new RespawnPacket();
            respawnPacket.setRuntimeEntityId(0); // Bedrock server behavior
            respawnPacket.setPosition(entity.bedrockPosition());
            respawnPacket.setState(RespawnPacket.State.SERVER_READY);
            session.sendUpstreamPacket(respawnPacket);

            entity.updateBedrockMetadata();

            MovePlayerPacket movePlayerPacket = new MovePlayerPacket();
            movePlayerPacket.setRuntimeEntityId(entity.geyserId());
            movePlayerPacket.setPosition(entity.bedrockPosition());
            movePlayerPacket.setRotation(entity.bedrockRotation());
            movePlayerPacket.setMode(MovePlayerPacket.Mode.RESPAWN);
            session.sendUpstreamPacket(movePlayerPacket);

            // Fixes incorrect rotation upon login
            // Yes, even that's not respected by Bedrock. Try it out in singleplayer!
            // Log out and back in - and you're looking elsewhere :)
            entity.updateOwnRotation(entity.getYaw(), entity.getPitch(), entity.getHeadYaw());
            session.setSpawned(true);
            entity.schedulePendingHeightOffsetNotice();
            entity.sendMaximumHeightWarning(entity.bedrockPosition());

            // Make sure the player moves away from (0, 32767, 0) before accepting movement packets
            Vector3f entityPosition = entity.position();
            session.setUnconfirmedTeleport(new TeleportCache(session, entityPosition, packet.getXRot(), packet.getYRot(), packet.getId()));

            if (session.getServerRenderDistance() > 32 && !session.isEmulatePost1_13Logic()) {
                // See DimensionUtils for an explanation
                ChunkRadiusUpdatedPacket chunkRadiusUpdatedPacket = new ChunkRadiusUpdatedPacket();
                chunkRadiusUpdatedPacket.setRadius(session.getServerRenderDistance());
                session.sendUpstreamPacket(chunkRadiusUpdatedPacket);

                session.setLastChunkPosition(null);
            }

            ChunkUtils.updateChunkPosition(session, entityPosition.toInt());

            if (session.getGeyser().config().debugMode()) {
                session.getGeyser().getLogger().debug("Spawned player at " + packet.getPosition());
            }
            entity.setPendingBedInteraction(null);
            // 确认包回传 Java 服务端坐标，不是映射后的 Bedrock 坐标喵~
            acceptTeleport(session, javaTeleportPosition, newYaw, newPitch, teleportId);
            return;
        }

        Vector3f lastPlayerPosition = entity.position();
        session.getGeyser().getLogger().debug("Teleport (" + teleportId + ") from " + lastPlayerPosition);

        float lastPlayerPitch = entity.getPitch();
        float lastPlayerYaw = entity.getYaw();
        Vector3f teleportDestination = position.toFloat();

        Vector3f deltaMovement = packet.getDeltaMovement().toFloat().add(
            packet.getRelatives().contains(PositionElement.DELTA_X) ? entity.getMotion().getX() : 0,
            packet.getRelatives().contains(PositionElement.DELTA_Y) ? entity.getMotion().getY() : 0,
            packet.getRelatives().contains(PositionElement.DELTA_Z) ? entity.getMotion().getZ() : 0
        );

        if (packet.getRelatives().contains(PositionElement.ROTATE_DELTA)) {
            deltaMovement = MathUtils.xYRot(deltaMovement, (float) Math.toRadians(lastPlayerPitch - newPitch), (float) Math.toRadians(lastPlayerYaw - newYaw));
        }

        SessionPlayerEntity.PendingBedInteraction pendingBedInteraction = entity.getPendingBedInteraction();
        if (pendingBedInteraction != null) {
            // See the Javadocs over at SessionPlayerEntity#pendingBedInteraction - we need to silently ignore a teleport if it just teleports us to the bed

            entity.setPendingBedInteraction(null);
            // 床位预期坐标来源于 Bedrock 客户端方块包，属于 Bedrock 坐标系，因此用 position 比对喵~
            if (looksLikeBedTeleport(pendingBedInteraction, position, deltaMovement)) {
                GeyserImpl.getInstance().getLogger().debug("Not translating teleport because it is to a pending bed interaction");
                // 确认包必须回传 Java 服务端坐标，不能用上面的 Bedrock 坐标喵~
                acceptTeleport(session, javaTeleportPosition, newYaw, newPitch, teleportId);
                return;
            }
        }

        TeleportCache.TeleportType type = (deltaMovement.distanceSquared(Vector3f.ZERO) > 1.0E-8F) ?
            TeleportCache.TeleportType.KEEP_VELOCITY : TeleportCache.TeleportType.NORMAL;

        // Set the unconfirmed teleport to ensure we send the adjusted Bedrock position in moveAbsolute (see CollisionManager#adjustPositionForBedrock)
        session.setUnconfirmedTeleport(new TeleportCache(session, teleportDestination, deltaMovement, newPitch, newYaw, teleportId, type));
        entity.moveAbsolute(teleportDestination, newYaw, newPitch, false, true);
        entity.sendMaximumHeightWarning(entity.bedrockPosition());

        // Bedrock ignores teleports that are extremely close to the player's original position and orientation, so check if we need to cache the teleport
        if (lastPlayerPosition.distanceSquared(teleportDestination) < 0.001 && Math.abs(newPitch - lastPlayerPitch) < 5 && Math.abs(newYaw - lastPlayerYaw) < 5) {
            session.setUnconfirmedTeleport(null);
        }

        if (type == TeleportCache.TeleportType.KEEP_VELOCITY) {
            entity.setMotion(deltaMovement);
            // Our motion got reset by the teleport but the deltaMovement is not 0 so send a motion packet to fix that.
            SetEntityMotionPacket entityMotionPacket = new SetEntityMotionPacket();
            entityMotionPacket.setRuntimeEntityId(entity.geyserId());
            entityMotionPacket.setMotion(entity.getMotion());
            session.sendUpstreamPacket(entityMotionPacket);
        }

        session.getGeyser().getLogger().debug("to " + entity.position());
        // 普通传送同样回传 Java 服务端坐标，确保确认包里的位置与服务端记录一致喵~
        acceptTeleport(session, javaTeleportPosition, newYaw, newPitch, teleportId);
    }

    /**
     * Confirm the teleport to the backend only when Bedrock has answered a latency ping.
     * (see the comment in {@link #translate} for where this should be called)
     * <p>
     * Previously, teleports were acknowledged instantly (without a latency stack packet), but this behavior broke in 26.x where the confirmation packet
     * now includes a position:
     * <a href="https://src.papermc.io/1/26.3/net/minecraft/server/network/ServerGamePacketListenerImpl#L586">{@code ServerGamePacketListenerImpl#handleAcceptTeleportPacket}</a>
     * calls <a href="https://src.papermc.io/1/26.3/net/minecraft/server/network/ServerGamePacketListenerImpl#L1129">{@code #handlePlayerPositionChange}</a>, which
     * checks the position relative to the player's position at the start of the tick...
     * For a player sleeping in a bed (>1 block from that tick position), the backend tries to teleport the player back to the bed (see {@code #isSleeping} in that method).
     * <p>
     * from there, it's an evil cycle of Geyser instantly confirming the teleport, the server having a tantrum and teleporting, yada yada until the end of the server tick (#6775).
     */
    private void acceptTeleport(GeyserSession session, Vector3d position, float yaw, float pitch, int id) {
        // 上游 26.x 起确认包自带坐标，必须传 Java 服务端的原始 double 坐标，不能传映射后的 Bedrock 坐标喵~
        ServerboundAcceptTeleportationPacket teleportConfirmPacket = new ServerboundAcceptTeleportationPacket(id, position.getX(), position.getY(), position.getZ(), yaw, pitch);
        // 等 Bedrock 回应延迟探测后再确认，避免床位传送陷入服务端反复回拉的死循环喵~
        session.sendNetworkLatencyStackPacket(id, true, () -> session.sendDownstreamGamePacket(teleportConfirmPacket));
    }

    private static boolean looksLikeBedTeleport(SessionPlayerEntity.PendingBedInteraction pendingInteraction, Vector3d packetPosition, Vector3f deltaMovement) {
        // Teleports to a bed after a bed interaction always teleports to the centre of the bed (plus some Y offset), with a delta movement of 0 (as of Java 26.3)
        // 调用方传入的 packetPosition 必须与 PendingBedInteraction 同属 Bedrock 坐标系喵~
        return pendingInteraction.expectedTeleportPosition().equals(packetPosition) && deltaMovement.length() == 0.0;
    }
}
