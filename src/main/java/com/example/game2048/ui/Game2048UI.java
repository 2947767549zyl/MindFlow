package com.example.game2048.ui;

import com.example.game2048.model.Direction;
import com.example.game2048.model.Game2048;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyAdapter;

/**
 * 2048 游戏的 Swing 界面与交互类。
 *
 * <p>职责：
 * <ul>
 *   <li>绘制棋盘与数字方块（按数值配色）</li>
 *   <li>监听键盘方向键（及 WASD）触发移动</li>
 *   <li>显示当前分数与最高分</li>
 *   <li>游戏结束 / 达成 2048 的提示与重新开始</li>
 * </ul>
 *
 * <p>逻辑层复用 {@link Game2048}（纯逻辑，无 UI 依赖），本类只负责渲染与交互。
 */
public class Game2048UI extends JPanel {

    // ------------------------------------------------------------------
    // 尺寸与配色常量
    // ------------------------------------------------------------------

    /** 棋盘边长（4x4）。 */
    private static final int SIZE = Game2048.SIZE;

    /** 单个格子边长（像素）。 */
    private static final int CELL_SIZE = 100;

    /** 格子间距（像素）。 */
    private static final int GAP = 12;

    /** 棋盘内边距（像素）。 */
    private static final int PADDING = 12;

    /** 棋盘绘制区域边长。 */
    private static final int BOARD_PX = PADDING * 2 + CELL_SIZE * SIZE + GAP * (SIZE - 1);

    /** 棋盘背景色。 */
    private static final Color BOARD_BG = new Color(0xBB, 0xAD, 0xA0);

    /** 空格子颜色。 */
    private static final Color EMPTY_CELL = new Color(0xCD, 0xC1, 0xB4);

    /** 深色数字（2、4 用深字）。 */
    private static final Color DARK_TEXT = new Color(0x77, 0x6E, 0x65);

    /** 浅色数字（8 及以上用白字）。 */
    private static final Color LIGHT_TEXT = new Color(0xF9, 0xF6, 0xF2);

    // ------------------------------------------------------------------
    // 游戏状态
    // ------------------------------------------------------------------

    /** 核心逻辑对象。 */
    private final Game2048 game;

    /** 最高分（本次运行内累计）。 */
    private int bestScore;

    /** 是否已经提示过胜利（避免重复弹窗）。 */
    private boolean winNotified;

    /** 分数标签。 */
    private JLabel scoreLabel;

    /** 最高分标签。 */
    private JLabel bestLabel;

    // ------------------------------------------------------------------
    // 构造与初始化
    // ------------------------------------------------------------------

    /** 创建界面并初始化游戏。 */
    public Game2048UI() {
        this.game = new Game2048();
        this.bestScore = 0;
        this.winNotified = false;

        setLayout(new BorderLayout());
        setBackground(BOARD_BG);
        setFocusable(true);

        add(buildHeader(), BorderLayout.NORTH);
        add(buildBoardPanel(), BorderLayout.CENTER);

        installKeyBindings();
        refreshLabels();
    }

    // ------------------------------------------------------------------
    // 界面构建
    // ------------------------------------------------------------------

    /** 构建顶部区域：标题、分数、最高分、重新开始按钮。 */
    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setBackground(BOARD_BG);
        header.setBorder(BorderFactory.createEmptyBorder(PADDING, PADDING, PADDING, PADDING));

        JLabel title = new JLabel("2048");
        title.setFont(new Font("SansSerif", Font.BOLD, 40));
        title.setForeground(new Color(0x77, 0x6E, 0x65));
        header.add(title, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setBackground(BOARD_BG);

        scoreLabel = new JLabel();
        bestLabel = new JLabel();
        scoreLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        bestLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        scoreLabel.setForeground(Color.WHITE);
        bestLabel.setForeground(Color.WHITE);
        scoreLabel.setOpaque(true);
        bestLabel.setOpaque(true);
        scoreLabel.setBackground(new Color(0x8F, 0x7A, 0x66));
        bestLabel.setBackground(new Color(0x8F, 0x7A, 0x66));
        scoreLabel.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        bestLabel.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));

        JButton restart = new JButton("重新开始");
        restart.setFont(new Font("SansSerif", Font.BOLD, 14));
        restart.setFocusable(false);
        restart.addActionListener((ActionEvent e) -> restart());

        right.add(scoreLabel);
        right.add(bestLabel);
        right.add(restart);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    /** 构建棋盘绘制面板。 */
    private JPanel buildBoardPanel() {
        JPanel boardPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Game2048UI.this.paintComponent(g);
            }
        };
        boardPanel.setBackground(BOARD_BG);
        boardPanel.setPreferredSize(new Dimension(BOARD_PX, BOARD_PX));
        return boardPanel;
    }

    /** 安装键盘方向键与 WASD 绑定。 */
    private void installKeyBindings() {
        bindKey(KeyEvent.VK_UP, "up", Direction.UP);
        bindKey(KeyEvent.VK_DOWN, "down", Direction.DOWN);
        bindKey(KeyEvent.VK_LEFT, "left", Direction.LEFT);
        bindKey(KeyEvent.VK_RIGHT, "right", Direction.RIGHT);
        bindKey(KeyEvent.VK_W, "w", Direction.UP);
        bindKey(KeyEvent.VK_S, "s", Direction.DOWN);
        bindKey(KeyEvent.VK_A, "a", Direction.LEFT);
        bindKey(KeyEvent.VK_D, "d", Direction.RIGHT);
    }

    /** 把某个按键绑定到指定方向的移动动作。 */
    private void bindKey(int keyCode, String name, Direction direction) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(keyCode, 0), name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handleMove(direction);
            }
        });
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        // 棋盘背景
        g2.setColor(BOARD_BG);
        g2.fillRect(0, 0, getWidth(), getHeight());

        // 先画所有空格子
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                int x = PADDING + c * (CELL_SIZE + GAP);
                int y = PADDING + r * (CELL_SIZE + GAP);
                g2.setColor(EMPTY_CELL);
                g2.fillRoundRect(x, y, CELL_SIZE, CELL_SIZE, 12, 12);
            }
        }

        // 再画有数字的方块
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                int value = game.getTile(r, c);
                if (value != 0) {
                    drawTile(g2, r, c, value);
                }
            }
        }
        g2.dispose();
    }

    /** 绘制单个方块（含圆角背景与数字）。 */
    private void drawTile(Graphics2D g2, int row, int col, int value) {
        int x = PADDING + col * (CELL_SIZE + GAP);
        int y = PADDING + row * (CELL_SIZE + GAP);

        g2.setColor(tileColor(value));
        g2.fillRoundRect(x, y, CELL_SIZE, CELL_SIZE, 12, 12);

        String text = String.valueOf(value);
        g2.setColor(textColor(value));
        g2.setFont(new Font("SansSerif", Font.BOLD, fontSize(value)));

        FontMetrics fm = g2.getFontMetrics();
        int tx = x + (CELL_SIZE - fm.stringWidth(text)) / 2;
        int ty = y + (CELL_SIZE - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(text, tx, ty);
    }

    /** 返回指定数值对应的方块背景色。 */
    private Color tileColor(int value) {
        switch (value) {
            case 2:    return new Color(0xEE, 0xE4, 0xDA);
            case 4:    return new Color(0xED, 0xE0, 0xC8);
            case 8:    return new Color(0xF2, 0xB1, 0x79);
            case 16:   return new Color(0xF5, 0x95, 0x63);
            case 32:   return new Color(0xF5, 0x7C, 0x5F);
            case 64:   return new Color(0xF6, 0x5E, 0x3B);
            case 128:  return new Color(0xED, 0xCF, 0x72);
            case 256:  return new Color(0xED, 0xCC, 0x61);
            case 512:  return new Color(0xED, 0xC8, 0x50);
            case 1024: return new Color(0xED, 0xC5, 0x3F);
            case 2048: return new Color(0xED, 0xC2, 0x2E);
            default:   return new Color(0x3C, 0x3A, 0x32);
        }
    }

    /** 返回指定数值对应的数字颜色。 */
    private Color textColor(int value) {
        return value <= 4 ? DARK_TEXT : LIGHT_TEXT;
    }

    /** 返回指定数值对应的字号。 */
    private int fontSize(int value) {
        if (value < 100) {
            return 40;
        } else if (value < 1000) {
            return 34;
        } else {
            return 28;
        }
    }

    // ------------------------------------------------------------------
    // 交互逻辑
    // ------------------------------------------------------------------

    /** 处理一次移动。 */
    private void handleMove(Direction direction) {
        if (game.isGameOver()) {
            return;
        }
        boolean changed = game.move(direction);
        if (changed) {
            game.spawnRandomTile();
            if (game.getScore() > bestScore) {
                bestScore = game.getScore();
            }
            refreshLabels();
            repaint();
            if (game.hasWon() && !winNotified) {
                winNotified = true;
                showWin();
            }
            if (game.isGameOver()) {
                showGameOver();
            }
        }
    }

    /** 重新开始游戏。 */
    private void restart() {
        game.reset();
        winNotified = false;
        refreshLabels();
        repaint();
        requestFocusInWindow();
    }

    /** 刷新分数标签。 */
    private void refreshLabels() {
        if (scoreLabel != null) {
            scoreLabel.setText("分数: " + game.getScore());
        }
        if (bestLabel != null) {
            bestLabel.setText("最高分: " + bestScore);
        }
    }

    /** 弹出游戏结束提示。 */
    private void showGameOver() {
        int choice = JOptionPane.showConfirmDialog(
                this,
                "游戏结束！\n最终得分: " + game.getScore() + "\n是否重新开始？",
                "游戏结束",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            restart();
        }
    }

    /** 弹出胜利提示。 */
    private void showWin() {
        int choice = JOptionPane.showConfirmDialog(
                this,
                "恭喜！你达成了 2048！\n当前得分: " + game.getScore() + "\n是否继续挑战？",
                "胜利",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE);
        if (choice == JOptionPane.NO_OPTION) {
            restart();
        }
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /** 独立启动入口（便于直接运行本类调试）。 */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("2048");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            frame.setContentPane(new Game2048UI());
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
