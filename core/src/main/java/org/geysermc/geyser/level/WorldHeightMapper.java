/*
 * Copyright (c) 2019-2024 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.level;

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;

import java.util.Objects;

public class WorldHeightMapper {

    // 预登录占位符，仅用于 session 初始化前的默认值，不应调用 createBedrockDimension() 喵~
    // bedrockMinY/MaxY 使用主世界默认值，needsMapping=false 保证 mapY 原样返回，不依赖这两个字段喵~
    private static final WorldHeightMapper IDENTITY = new WorldHeightMapper(0, Integer.MIN_VALUE, Integer.MAX_VALUE, false);

    private final int offset;
    private final int bedrockMinY;
    private final int bedrockMaxY;
    private final boolean needsMapping;

    private WorldHeightMapper(int offset, int bedrockMinY, int bedrockMaxY, boolean needsMapping) {
        this.offset = offset;
        this.bedrockMinY = bedrockMinY;
        this.bedrockMaxY = bedrockMaxY;
        this.needsMapping = needsMapping;
    }

    public static WorldHeightMapper create(JavaDimension javaDimension) {
        Objects.requireNonNull(javaDimension, "javaDimension must not be null");
        int javaMinY = javaDimension.minY();
        int javaHeight = javaDimension.height();
        int javaMaxY = javaMinY + javaHeight;

        if (javaMinY >= -512 && javaMaxY <= 512) {
            return new WorldHeightMapper(0, javaMinY, javaMaxY, false);
        }

        int span = javaMaxY - javaMinY;
        int mappedBedrockMinY = -512;
        int mappedSpan = Math.min(span, 1024);
        int mappedBedrockMaxY = -512 + mappedSpan;
        int calculatedOffset = mappedBedrockMinY - javaMinY;

        return new WorldHeightMapper(calculatedOffset, mappedBedrockMinY, mappedBedrockMaxY, true);
    }

    public static WorldHeightMapper identity() {
        return IDENTITY;
    }

    public boolean needsMapping() {
        return needsMapping;
    }

    public int offset() {
        return offset;
    }

    public int bedrockMinY() {
        return bedrockMinY;
    }

    public int bedrockMaxY() {
        return bedrockMaxY;
    }

    public int mapY(int javaY) {
        if (!needsMapping) return javaY;
        int bedrockY = javaY + offset;
        if (bedrockY < bedrockMinY) return bedrockMinY;
        if (bedrockY >= bedrockMaxY) return bedrockMaxY - 1;
        return bedrockY;
    }

    public float mapY(float javaY) {
        if (!needsMapping) return javaY;
        float bedrockY = javaY + offset;
        if (bedrockY < bedrockMinY) return bedrockMinY;
        if (bedrockY >= bedrockMaxY) return bedrockMaxY - 1;
        return bedrockY;
    }

    public double mapY(double javaY) {
        if (!needsMapping) return javaY;
        double bedrockY = javaY + offset;
        if (bedrockY < bedrockMinY) return bedrockMinY;
        if (bedrockY >= bedrockMaxY) return bedrockMaxY - 1;
        return bedrockY;
    }

    public Vector3f mapPosition(Vector3f pos) {
        if (!needsMapping) return pos;
        return Vector3f.from(pos.getX(), clampMapYFloat(pos.getY() + offset), pos.getZ());
    }

    public Vector3i mapPosition(Vector3i pos) {
        if (!needsMapping) return pos;
        return Vector3i.from(pos.getX(), clampMapYInt(pos.getY() + offset), pos.getZ());
    }

    private int clampMapYInt(int bedrockY) {
        if (bedrockY < bedrockMinY) return bedrockMinY;
        if (bedrockY >= bedrockMaxY) return bedrockMaxY - 1;
        return bedrockY;
    }

    private float clampMapYFloat(float bedrockY) {
        if (bedrockY < bedrockMinY) return bedrockMinY;
        if (bedrockY >= bedrockMaxY) return bedrockMaxY - 1;
        return bedrockY;
    }

    // 仅加 offset，不做 clamp，用于实体/声音/粒子等允许超出维度边界的坐标喵~
    public float mapYUnclamped(float javaY) {
        return needsMapping ? javaY + offset : javaY;
    }

    // double 版本，仅加 offset，不做 clamp喵~
    public double mapYUnclamped(double javaY) {
        return needsMapping ? javaY + offset : javaY;
    }

    // 仅加 offset，不做 clamp，Vector3f 版本喵~
    public Vector3f mapPositionUnclamped(Vector3f pos) {
        return needsMapping ? Vector3f.from(pos.getX(), pos.getY() + offset, pos.getZ()) : pos;
    }

    public int inverseMapY(int bedrockY) {
        return needsMapping ? bedrockY - offset : bedrockY;
    }

    public float inverseMapY(float bedrockY) {
        return needsMapping ? bedrockY - offset : bedrockY;
    }

    public double inverseMapY(double bedrockY) {
        return needsMapping ? bedrockY - offset : bedrockY;
    }

    public Vector3f inverseMapPosition(Vector3f pos) {
        return needsMapping ? Vector3f.from(pos.getX(), pos.getY() - offset, pos.getZ()) : pos;
    }

    public Vector3i inverseMapPosition(Vector3i pos) {
        return needsMapping ? Vector3i.from(pos.getX(), pos.getY() - offset, pos.getZ()) : pos;
    }

    public BedrockDimension createBedrockDimension(int bedrockId) {
        return new BedrockDimension(bedrockMinY, bedrockMaxY - bedrockMinY, false, bedrockId, true);
    }
}
