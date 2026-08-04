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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/** 按需收集 JVM 内存与线程快照，不会触发 GC 或遍历对象喵~ */
public record MemoryReport(long heapUsed, long heapCommitted, long heapMax, long nonHeapUsed, int threadCount) {
    /** 读取当前 JVM 的轻量内存状态，失败时返回安全的未知值喵~ */
    public static MemoryReport capture() {
        // 获取 JVM 运行时对象，用于读取堆内存上限和已提交容量喵~
        Runtime runtime = Runtime.getRuntime();
        // 读取堆当前已提交容量与空闲容量，避免执行任何主动 GC 喵~
        long heapCommitted = runtime.totalMemory();
        long heapUsed = heapCommitted - runtime.freeMemory();
        // 喵~防御：某些 JVM 可能返回负数或未知最大堆，报告中统一回退为零喵~
        long heapMax = Math.max(0L, runtime.maxMemory());
        // 获取 JVM 内存管理接口，仅在低频报告时执行，避免污染翻译热路径喵~
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        // 读取非堆使用量，未知值使用零表示喵~
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        long nonHeapUsed = nonHeapUsage == null ? 0L : Math.max(0L, nonHeapUsage.getUsed());
        // 读取线程数量供定位线程泄漏或线程暴涨问题使用喵~
        int threadCount = Math.max(0, ManagementFactory.getThreadMXBean().getThreadCount());
        // 返回不可变快照，供日志和命令线程安全使用喵~
        return new MemoryReport(Math.max(0L, heapUsed), Math.max(0L, heapCommitted), heapMax, nonHeapUsed, threadCount);
    }

    /** 将内存字节转换为易读的 MiB 文本喵~ */
    public String format() {
        return "memory{heapUsedMiB=" + toMiB(heapUsed)
            + ",heapCommittedMiB=" + toMiB(heapCommitted)
            + ",heapMaxMiB=" + toMiB(heapMax)
            + ",nonHeapUsedMiB=" + toMiB(nonHeapUsed)
            + ",threads=" + threadCount + '}';
    }

    /** 将字节数安全转换为 MiB，避免除零并保留整数报告喵~ */
    private static long toMiB(long bytes) {
        return bytes <= 0 ? 0 : bytes / (1024L * 1024L);
    }
}
