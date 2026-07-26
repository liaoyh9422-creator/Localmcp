package com.apkstoapk.app.runtime;

/**
 * Lua→DEX 目标运行时。
 * <ul>
 *   <li>{@link #STOCK}：标准 / AGG 风格 {@code org.luaj.vm2}（现有逻辑）</li>
 *   <li>{@link #MODDED_GG}：魔改 GG（如 Nqfes）{@code luaj.*} + 短方法名</li>
 * </ul>
 */
public enum GgLuajTarget {
    /** 标准 org.luaj.vm2（仅 LuaInteger→LuaLong 等轻量兼容） */
    STOCK,
    /** 魔改 GG：包名 luaj + 方法短名映射（Nqfes 466.3 / 同类） */
    MODDED_GG
}
