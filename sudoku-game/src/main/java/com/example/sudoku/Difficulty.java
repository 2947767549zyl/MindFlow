package com.example.sudoku;

/**
 * 难度枚举。
 *
 * <p>每档难度提供：
 * <ul>
 *     <li>{@link #getDisplayName()} —— 供 UI 显示的难度名称；</li>
 *     <li>{@link #getHoles()} —— 生成题目时从完整解中挖空的数量（0 表示空格）。</li>
 * </ul>
 *
 * <p>挖空数量越大，题目越难。挖空后仍由 {@link SudokuGenerator} 保证唯一解。
 */
public enum Difficulty {

    /** 简单：挖空 35 格，保留 46 个提示数。 */
    EASY("简单", 35),

    /** 中等：挖空 45 格，保留 36 个提示数。 */
    MEDIUM("中等", 45),

    /** 困难：挖空 52 格，保留 29 个提示数。 */
    HARD("困难", 52);

    private final String displayName;
    private final int holes;

    Difficulty(String displayName, int holes) {
        this.displayName = displayName;
        this.holes = holes;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getHoles() {
        return holes;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
