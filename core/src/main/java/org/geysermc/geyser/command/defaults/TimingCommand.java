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

package org.geysermc.geyser.command.defaults;

import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.util.TriState;
import org.geysermc.geyser.command.GeyserCommand;
import org.geysermc.geyser.command.GeyserCommandSource;
import org.geysermc.geyser.util.diagnostics.MemoryReport;
import org.geysermc.geyser.util.diagnostics.TimingDiagnostics;
import org.incendo.cloud.context.CommandContext;

/** 输出当前性能计时和 JVM 内存快照的只读命令喵~ */
public final class TimingCommand extends GeyserCommand {
    private final GeyserImpl geyser;

    public TimingCommand(GeyserImpl geyser, String name, String description, String permission) {
        super(name, description, permission, TriState.TRUE, false, false);
        this.geyser = geyser;
    }

    @Override
    public void execute(CommandContext<GeyserCommandSource> context) {
        // 喵~防御：配置关闭时不采集内存，避免命令误触发诊断开销喵~
        if (!geyser.config().debugTiming() || geyser.getTimingDiagnostics() == null) {
            context.sender().sendMessage("Timing diagnostics are disabled. Set debug-timing: true and reload Geyser.");
            return;
        }
        // 在命令线程一次性获取不可变计时快照，避免持有聚合器状态喵~
        TimingDiagnostics.Snapshot timingSnapshot = geyser.getTimingDiagnostics().snapshot();
        // 只在手动查询时采集 JVM 内存信息，不进入网络或翻译热路径喵~
        MemoryReport memoryReport = MemoryReport.capture();
        // 发送单条完整报告，减少控制台输出调用次数喵~
        context.sender().sendMessage(timingSnapshot.format() + " " + memoryReport.format());
    }
}
