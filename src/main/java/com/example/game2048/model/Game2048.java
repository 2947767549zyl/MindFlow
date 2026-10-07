package com.example.game2048.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 2048 核心游戏逻辑类（纯逻辑，不依赖任何 UI 框架）。
 */
public class Game2048 {

    /** 棋盘边长（4x4）。 */
    public static final int SIZE = 4;

    /** 达成胜利的目标数值。 */
    public static final int WIN_VALUE = 2048;

    /** 生成 4 的概率（10%），其余为 2。 */
    private static final double PROB_4 = 0.1;

    /** 棋盘：board[row][col]，0 表示空格。 */
    private final int[][] board;

    /** 当前得分。 */
    private int score;

    /** 随机数发生器。 */
    private final Random random;

    /** 是否已经达成过 2048。 */
    private boolean won;

    /** 是否游戏结束。 */
    private boolean gameOver;

    /** 创建一个空棋盘并生成两个初始方块。 */
    public Game2048() {
        this(new Random());
    }

    /** 允许注入 Random 的构造器，便于单元测试。 */
    public Game2048(Random random) {
        this.board = new int[SIZE][SIZE];
        this.random = random;
        this.score = 0;
        this.won = false;
        this.gameOver = false;
        spawnRandomTile();
        spawnRandomTile();
    }

    /** 返回指定格子的值（0 表示空格）。 */
    public int getTile(int row, int col) {
        return board[row][col];
    }

    /** 返回当前得分。 */
    public int getScore() {
        return score;
    }

    /** 是否已达成 2048。 */
    public boolean hasWon() {
        return won;
    }

    /** 是否游戏结束（无法继续移动）。 */
    public boolean isGameOver() {
        return gameOver;
    }

    /** 返回棋盘深拷贝，供渲染层只读使用。 */
    public int[][] snapshot() {
        int[][] copy = new int[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            System.arraycopy(board[r], 0, copy[r], 0, SIZE);
        }
        return copy;
    }

    /** 重置游戏到初始状态。 */
    public void reset() {
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                board[r][c] = 0;
            }
        }
        score = 0;
        won = false;
        gameOver = false;
        spawnRandomTile();
        spawnRandomTile();
    }

    /**
     * 在所有空格中随机选一个，填入 2（90%）或 4（10%）。
     *
     * @return 是否成功生成（棋盘已满时返回 false）
     */
    public boolean spawnRandomTile() {
        List<int[]> empty = new ArrayList<>();
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] == 0) {
                    empty.add(new int[]{r, c});
                }
            }
        }
        if (empty.isEmpty()) {
            return false;
        }
        int[] pos = empty.get(random.nextInt(empty.size()));
        board[pos[0]][pos[1]] = (random.nextDouble() < PROB_4) ? 4 : 2;
        return true;
    }

    /**
     * 按指定方向移动棋盘。
     *
     * @param direction 移动方向
     * @return 棋盘是否发生了变化
     */
    public boolean move(Direction direction) {
        if (gameOver) {
            return false;
        }
        int[][] before = snapshot();

        switch (direction) {
            case LEFT:
                for (int r = 0; r < SIZE; r++) {
                    board[r] = slideLine(board[r]);
                }
                break;
            case RIGHT:
                for (int r = 0; r < SIZE; r++) {
                    int[] reversed = reverse(board[r]);
                    board[r] = reverse(slideLine(reversed));
                }
                break;
            case UP:
                transpose();
                for (int r = 0; r < SIZE; r++) {
                    board[r] = slideLine(board[r]);
                }
                transpose();
                break;
            case DOWN:
                transpose();
                for (int r = 0; r < SIZE; r++) {
                    int[] reversed = reverse(board[r]);
                    board[r] = reverse(slideLine(reversed));
                }
                transpose();
                break;
            default:
                throw new IllegalArgumentException("未知方向: " + direction);
        }

        boolean changed = !equalsBoard(before, board);
        if (changed) {
            updateWinState();
            if (!canMove()) {
                gameOver = true;
            }
        }
        return changed;
    }

    /**
     * 对一行执行「压缩 → 合并 → 再压缩」，方向为向左。
     */
    private int[] slideLine(int[] line) {
        int[] result = new int[SIZE];
        int idx = 0;
        for (int value : line) {
            if (value != 0) {
                result[idx++] = value;
            }
        }
        for (int i = 0; i < SIZE - 1; i++) {
            if (result[i] != 0 && result[i] == result[i + 1]) {
                result[i] *= 2;
                score += result[i];
                result[i + 1] = 0;
                i++;
            }
        }
        int[] compacted = new int[SIZE];
        int j = 0;
        for (int value : result) {
            if (value != 0) {
                compacted[j++] = value;
            }
        }
        return compacted;
    }

    /** 原地转置棋盘（行列互换）。 */
    private void transpose() {
        for (int r = 0; r < SIZE; r++) {
            for (int c = r + 1; c < SIZE; c++) {
                int tmp = board[r][c];
                board[r][c] = board[c][r];
                board[c][r] = tmp;
            }
        }
    }

    /** 返回数组的反转副本。 */
    private int[] reverse(int[] arr) {
        int[] out = new int[arr.length];
        for (int i = 0; i < arr.length; i++) {
            out[i] = arr[arr.length - 1 - i];
        }
        return out;
    }

    /** 检查是否出现 2048，更新胜利标记。 */
    private void updateWinState() {
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] >= WIN_VALUE) {
                    won = true;
                    return;
                }
            }
        }
    }

    /**
     * 判断是否还能继续移动：存在空格，或存在相邻相等方块。
     */
    public boolean canMove() {
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] == 0) {
                    return true;
                }
            }
        }
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                int v = board[r][c];
                if (c + 1 < SIZE && board[r][c + 1] == v) {
                    return true;
                }
                if (r + 1 < SIZE && board[r + 1][c] == v) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 比较两个棋盘是否完全相同。 */
    private boolean equalsBoard(int[][] a, int[][] b) {
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (a[r][c] != b[r][c]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 以文本形式打印棋盘，便于命令行调试。 */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Score: ").append(score).append('\n');
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                sb.append(String.format("%6d", board[r][c]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
