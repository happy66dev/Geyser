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
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.command.defaults;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.geyser.api.util.TriState;
import org.geysermc.geyser.command.GeyserCommand;
import org.geysermc.geyser.command.GeyserCommandSource;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.text.GeyserLocale;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;

import java.util.Locale;

import static org.incendo.cloud.parser.standard.StringParser.greedyStringParser;

/**
 * 让玩家查询自己在 Java 服务端的真实坐标喵~
 * <p>
 * 用途：启用水平或垂直坐标映射后，Bedrock 客户端显示的是映射后的坐标，
 * 玩家看到的位置和服务端实际位置不一致，容易误判自己在哪里喵~
 * 本命令提供三种查看方式：
 * <ul>
 *     <li>{@code status} —— 查看当前映射状态与真实坐标（默认行为）喵~</li>
 *     <li>{@code toggle} —— 开关底部常驻的真实坐标快捷栏提示喵~</li>
 *     <li>{@code bar} —— 一次性推送一行快捷栏提示，便于快速核对喵~</li>
 * </ul>
 *
 * <h2>坐标来源</h2>
 * <p>
 * Bedrock 客户端上报的移动坐标属于 Bedrock 坐标系，需要先经过映射层反向还原才是 Java 真实坐标喵~
 * 命令只在执行时读取一次位置，不注册任何每帧回调，因此没有性能开销喵~
 */
public final class CoordinateCommand extends GeyserCommand {

    /** 可选参数名，用于接收子命令关键字喵~ */
    private static final String ARGUMENTS = "arguments";

    /**
     * 构造命令实例喵~
     *
     * @param name 命令名，不带斜杠
     * @param description 国际化描述键
     * @param permission 需要的权限节点
     */
    public CoordinateCommand(String name, String description, String permission) {
        // 第一个 true 表示仅玩家可用：控制台没有 Bedrock 会话，无法查询个人坐标喵~
        // 第二个 true 表示仅 Bedrock 玩家：Java 玩家走原版 F3 即可，不重复提供喵~
        super(name, description, permission, TriState.TRUE, true, true);
    }

    @Override
    public void register(CommandManager<GeyserCommandSource> manager) {
        // 无参调用等价于 status，方便玩家直接敲 /geyser coordinate 喵~
        manager.command(baseBuilder(manager).handler(this::status));
        // 显式 status 子命令，行为与无参一致喵~
        manager.command(baseBuilder(manager).literal("status").handler(this::status));
        // 开关底部常驻的坐标提示喵~
        manager.command(baseBuilder(manager).literal("toggle").handler(this::toggle));
        // 一次性推送一行提示，不改变开关状态喵~
        manager.command(baseBuilder(manager).literal("bar").handler(this::bar));
        // 兜底分支：把未知参数当作子命令解析，从而给出用法提示而不是静默失败喵~
        manager.command(baseBuilder(manager).optional(ARGUMENTS, greedyStringParser()).handler(this::status));
    }

    @Override
    public void execute(CommandContext<GeyserCommandSource> context) {
        // 直接执行时等同于查询状态喵~
        status(context);
    }

    /**
     * 输出当前映射状态与真实坐标喵~
     *
     * @param context 命令上下文，必须携带 Bedrock 会话
     */
    private void status(CommandContext<GeyserCommandSource> context) {
        GeyserCommandSource source = context.sender();
        GeyserSession session = source.connection();
        // 喵~防御：控制台或未连接的来源没有会话，给出提示而不是抛空指针喵~
        if (session == null) {
            source.sendMessage(message(session, "geyser.commands.coordinate.player_only"));
            return;
        }

        // 读取一次反向映射后的真实坐标，字符串化后再拼接，避免浮点显示抖动喵~
        String realCoordinates = describeRealCoordinates(session);
        // 说明当前是否启用了水平/垂直映射，让玩家理解为什么客户端坐标不同喵~
        boolean horizontalMapped = session.getHorizontalOffsetMapper().needsMapping();
        boolean verticalMapped = session.getWorldHeightMapper().needsMapping();

        source.sendMessage(message(session, "geyser.commands.coordinate.real_position", realCoordinates));
        source.sendMessage(message(session, "geyser.commands.coordinate.mapping_status",
            horizontalMapped ? "active" : "inactive", verticalMapped ? "active" : "inactive"));
        if (!horizontalMapped && !verticalMapped) {
            // 没有任何映射时，客户端坐标就是真实坐标，提示玩家不必担心喵~
            source.sendMessage(message(session, "geyser.commands.coordinate.no_mapping"));
        }
    }

    /**
     * 开关底部常驻的真实坐标提示喵~
     *
     * @param context 命令上下文，必须携带 Bedrock 会话
     */
    private void toggle(CommandContext<GeyserCommandSource> context) {
        GeyserCommandSource source = context.sender();
        GeyserSession session = source.connection();
        // 喵~防御：无会话来源直接拒绝，避免空指针喵~
        if (session == null) {
            source.sendMessage(message(null, "geyser.commands.coordinate.player_only"));
            return;
        }

        // 翻转开关并立即应用，避免玩家要等下一次移动才看到效果喵~
        boolean enabled = !session.isCoordinateBarEnabled();
        session.setCoordinateBarEnabled(enabled);
        source.sendMessage(message(session, enabled
            ? "geyser.commands.coordinate.toggle.enabled"
            : "geyser.commands.coordinate.toggle.disabled"));
        if (enabled) {
            // 打开时立刻推一次，让玩家马上知道长什么样喵~
            pushCoordinateBar(session);
        }
    }

    /**
     * 一次性推送一行真实坐标提示，不改变常驻开关喵~
     *
     * @param context 命令上下文，必须携带 Bedrock 会话
     */
    private void bar(CommandContext<GeyserCommandSource> context) {
        GeyserCommandSource source = context.sender();
        GeyserSession session = source.connection();
        // 喵~防御：无会话来源直接拒绝喵~
        if (session == null) {
            source.sendMessage(message(null, "geyser.commands.coordinate.player_only"));
            return;
        }

        // 推一行提示，同时告诉玩家可以用 toggle 让它常驻喵~
        pushCoordinateBar(session);
        source.sendMessage(message(session, "geyser.commands.coordinate.bar.pushed"));
    }

    /**
     * 按玩家区域设置取国际化文案喵~
     * <p>
     * 输入：会话与文案键，可变参数为占位符替换值；
     * 输出：本地化后的中性文案，绝不包含任何猫娘用语喵~
     * 边界：会话为 null 时回落到英文默认区域，保证控制台也能拿到文案喵~
     *
     * @param session 目标玩家会话，可以为 null
     * @param key 国际化文案键，不能为 null
     * @param values 占位符替换值，可以为空
     * @return 本地化后的文案喵~
     */
    private static String message(@Nullable GeyserSession session, String key, Object... values) {
        // 喵~防御：会话缺失时用英文区域，避免空指针导致命令无输出喵~
        String locale = session != null ? session.locale() : "en_US";
        return GeyserLocale.getPlayerLocaleString(key, locale, values);
    }

    /**
     * 把会话当前真实坐标推送到底部快捷栏喵~
     *
     * @param session 目标玩家会话，不能为 null
     */
    private void pushCoordinateBar(GeyserSession session) {
        // 交给会话统一处理，命令层不直接拼数据包，方便后续复用到其他入口喵~
        session.sendCoordinateBar();
    }

    /**
     * 把会话的真实坐标格式化成可读字符串喵~
     *
     * @param session 目标玩家会话，不能为 null
     * @return 形如 "X: 123.45, Y: 67.89, Z: -10.11" 的字符串喵~
     */
    static String describeRealCoordinates(GeyserSession session) {
        // 使用会话暴露的真实坐标访问器，命令层不关心内部用的是哪套映射喵~
        Vector3d realPosition = session.realPlayerPosition();
        // 保留两位小数，避免 float32 噪声导致尾数很长影响阅读喵~
        return String.format(Locale.ROOT, "X: %.2f, Y: %.2f, Z: %.2f",
            realPosition.getX(), realPosition.getY(), realPosition.getZ());
    }
}
