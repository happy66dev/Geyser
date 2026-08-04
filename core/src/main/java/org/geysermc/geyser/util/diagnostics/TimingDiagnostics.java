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
 */

package org.geysermc.geyser.util.diagnostics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 收集翻译层的低开销聚合计时，不在热路径创建日志文本或查询 JVM 状态喵~
 */
public final class TimingDiagnostics {
    /** 固定指标名称，避免运行时按动态字符串分配统计键喵~ */
    public enum Metric {
        CHUNK_CONVERSION,
        CHUNK_JAVA_DATA_READ,
        CHUNK_SECTION_BLOCK_CONVERSION,
        CHUNK_BLOCK_ENTITY_TRANSLATION,
        CHUNK_SKULL_TRANSLATION,
        CHUNK_ITEM_FRAME_SCAN,
        CHUNK_PAYLOAD_ESTIMATE,
        CHUNK_PAYLOAD_ENCODING,
        CHUNK_SECTION_ENCODING,
        MESSAGE_TRANSLATION,
        HEIGHT_MAPPING,
        ENTITY_METADATA_TRANSLATION,
        UPSTREAM_PACKET_SEND,
        DOWNSTREAM_PACKET_SEND
    }

    /** 每个指标的无锁累计器，允许快照在并发写入时保持近似一致喵~ */
    private static final class Accumulator {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();
    }

    /** 预先创建固定指标累计器，启用诊断后避免首次调用产生额外分配喵~ */
    private final Accumulator[] accumulators = new Accumulator[Metric.values().length];
    /** 控制热路径是否真正读取计时器的开关喵~ */
    private volatile boolean enabled;

    public TimingDiagnostics(boolean enabled) {
        // 喵~防御：即使调用方传入异常配置，也只接受明确的 true 作为启用信号喵~
        this.enabled = enabled;
        // 为每个固定指标创建一个独立聚合器，避免不同指标互相竞争喵~
        for (Metric metric : Metric.values()) {
            accumulators[metric.ordinal()] = new Accumulator();
        }
    }

    /** 返回当前是否启用计时，调用方可用它保护 nanoTime 与热路径记录喵~ */
    public boolean enabled() {
        return enabled;
    }

    /** 切换计时状态，不会修改已有累计值喵~ */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 记录一次耗时；关闭时直接返回，避免无意义的并发操作喵~ */
    public void record(Metric metric, long elapsedNanos) {
        if (!enabled || metric == null || elapsedNanos < 0) {
            return;
        }
        Accumulator accumulator = accumulators[metric.ordinal()];
        accumulator.count.increment();
        accumulator.totalNanos.add(elapsedNanos);
        accumulator.maxNanos.accumulateAndGet(elapsedNanos, Math::max);
    }

    /** 读取所有指标的不可变快照，内存采集留给报告线程执行喵~ */
    public Snapshot snapshot() {
        MetricSnapshot[] metricSnapshots = new MetricSnapshot[accumulators.length];
        for (Metric metric : Metric.values()) {
            Accumulator accumulator = accumulators[metric.ordinal()];
            long count = accumulator.count.sum();
            long totalNanos = accumulator.totalNanos.sum();
            metricSnapshots[metric.ordinal()] = new MetricSnapshot(metric, count, totalNanos, accumulator.maxNanos.get());
        }
        return new Snapshot(metricSnapshots);
    }

    /** 清空当前窗口并返回清空前的快照，适合周期报告使用喵~ */
    public Snapshot snapshotAndReset() {
        Snapshot snapshot = snapshot();
        for (Accumulator accumulator : accumulators) {
            accumulator.count.reset();
            accumulator.totalNanos.reset();
            accumulator.maxNanos.set(0);
        }
        return snapshot;
    }

    /** 不可变的单项统计结果喵~ */
    public record MetricSnapshot(Metric metric, long count, long totalNanos, long maxNanos) {
        public long averageNanos() {
            return count == 0 ? 0 : totalNanos / count;
        }
    }

    /** 不可变的计时窗口快照喵~ */
    public record Snapshot(MetricSnapshot[] metrics) {
        public String format() {
            StringBuilder report = new StringBuilder("timing");
            for (MetricSnapshot metric : metrics) {
                if (metric.count() == 0) {
                    continue;
                }
                report.append(" ").append(metric.metric().name())
                    .append("{count=").append(metric.count())
                    .append(",avgUs=").append(metric.averageNanos() / 1_000L)
                    .append(",maxUs=").append(metric.maxNanos() / 1_000L)
                    .append(",totalMs=").append(metric.totalNanos() / 1_000_000L)
                    .append('}');
            }
            return report.toString();
        }
    }
}
