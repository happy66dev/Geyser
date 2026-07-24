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

package org.geysermc.geyser.session;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.geysermc.geyser.network.GeyserBedrockPeer;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

@RequiredArgsConstructor
public class UpstreamSession {
    @Getter private final BedrockServerSession session;
    @Getter @Setter
    private boolean initialized = false;
    private Queue<BedrockPacket> postStartGamePackets = new ArrayDeque<>();

    // 仅在 debug 模式启用；统计实际提交给 Bedrock 会话的包，不保留包内容喵~
    private @Nullable UpstreamPacketDiagnostics packetDiagnostics;

    public void startPacketDiagnostics() {
        if (packetDiagnostics == null) {
            packetDiagnostics = new UpstreamPacketDiagnostics();
        }
        packetDiagnostics.start();
    }

    public String packetDiagnosticsSnapshot(int requestedSeconds) {
        return packetDiagnostics == null ? null : packetDiagnostics.snapshot(requestedSeconds);
    }

    public void sendPacket(@NonNull BedrockPacket packet) {
        if (!isClosed()) {
            recordPacket(packet);
            session.sendPacket(packet);
        }
    }

    public void sendPacketImmediately(@NonNull BedrockPacket packet) {
        if (!isClosed()) {
            recordPacket(packet);
            session.sendPacketImmediately(packet);
        }
    }

    public void disconnect(String reason) {
        this.session.disconnect(reason);
    }

    /**
     * Queue a packet that must be delayed until after login.
     */
    public void queuePostStartGamePacket(BedrockPacket packet) {
        postStartGamePackets.add(packet);
    }

    public void sendPostStartGamePackets() {
        if (isClosed()) {
            return;
        }

        BedrockPacket packet;
        while ((packet = postStartGamePackets.poll()) != null) {
            recordPacket(packet);
            session.sendPacket(packet);
        }
        postStartGamePackets = null;
    }

    private void recordPacket(BedrockPacket packet) {
        if (packetDiagnostics != null) {
            packetDiagnostics.record(packet);
        }
    }

    /**
     * Debug-only packet counters for the first seconds of a Bedrock login.
     */
    private static final class UpstreamPacketDiagnostics {
        private final Map<String, LongAdder> packetTotals = new ConcurrentHashMap<>();
        private final LongAdder totalPackets = new LongAdder();
        private final Map<String, Long> previousPacketTotals = new HashMap<>();
        private long startedAtNanos;
        private long previousSnapshotNanos;
        private long previousTotalPackets;

        void start() {
            packetTotals.clear();
            previousPacketTotals.clear();
            totalPackets.reset();
            previousTotalPackets = 0;
            startedAtNanos = System.nanoTime();
            previousSnapshotNanos = startedAtNanos;
        }

        void record(BedrockPacket packet) {
            packetTotals.computeIfAbsent(packet.getClass().getSimpleName(), ignored -> new LongAdder()).increment();
            totalPackets.increment();
        }

        String snapshot(int requestedSeconds) {
            long now = System.nanoTime();
            long currentTotalPackets = totalPackets.sum();
            double elapsedSeconds = (now - startedAtNanos) / 1_000_000_000.0;
            double intervalSeconds = (now - previousSnapshotNanos) / 1_000_000_000.0;
            long deltaTotalPackets = currentTotalPackets - previousTotalPackets;
            double packetsPerSecond = intervalSeconds > 0 ? deltaTotalPackets / intervalSeconds : 0;

            StringBuilder output = new StringBuilder("[bedrock-upstream] +")
                .append(requestedSeconds).append("s (actual ")
                .append(String.format(java.util.Locale.ROOT, "%.2f", elapsedSeconds)).append("s, interval ")
                .append(String.format(java.util.Locale.ROOT, "%.2f", intervalSeconds)).append("s): total=")
                .append(currentTotalPackets).append(", delta=").append(deltaTotalPackets)
                .append(", rate=").append(String.format(java.util.Locale.ROOT, "%.1f", packetsPerSecond)).append(" packets/s");

            packetTotals.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().sum()))
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(entry -> entry.getValue() - previousPacketTotals.getOrDefault(entry.getKey(), 0L)).reversed()
                    .thenComparing(Map.Entry::getKey))
                .forEach(entry -> {
                    long delta = entry.getValue() - previousPacketTotals.getOrDefault(entry.getKey(), 0L);
                    if (delta > 0) {
                        output.append(System.lineSeparator()).append("[bedrock-upstream] ")
                            .append(entry.getKey()).append(": total=").append(entry.getValue())
                            .append(", delta=").append(delta)
                            .append(", rate=").append(String.format(java.util.Locale.ROOT, "%.1f", intervalSeconds > 0 ? delta / intervalSeconds : 0));
                    }
                    previousPacketTotals.put(entry.getKey(), entry.getValue());
                });

            previousTotalPackets = currentTotalPackets;
            previousSnapshotNanos = now;
            return output.toString();
        }
    }

    public boolean isClosed() {
        return !session.getPeer().isConnected() && !session.getPeer().isConnecting();
    }

    public InetSocketAddress getAddress() {
        // Will always be an InetSocketAddress. See ProxyChannel#remoteAddress
        return (InetSocketAddress) ((GeyserBedrockPeer) session.getPeer()).getRealAddress();
    }

    public void setInetAddress(InetSocketAddress address) {
        ((GeyserBedrockPeer) session.getPeer()).setProxiedAddress(address);
    }

    /**
     * Gets the session's protocol version.
     *
     * @return the session's protocol version.
     */
    public int getProtocolVersion() {
        return this.session.getCodec().getProtocolVersion();
    }

    /**
     * Gets the codec helper for this session.
     *
     * @return the codec helper for this session
     */
    public BedrockCodecHelper getCodecHelper() {
        return this.session.getPeer().getCodecHelper();
    }

    public void forciblyClose() {
        this.session.getPeer().getChannel().close();
    }
}
