import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

public class DesktopPet extends JFrame {

    // ===== 可调参数 =====
    public static final int SIZE = 128;             // 显示尺寸
    private static final int TICK_MS = 33;          // ~30FPS
    private static final int GRAVITY = 2;           // 重力加速度
    private static final int MAX_FALL_SPEED = 24;   // 终端速度
    private static final int LAND_HOLD_TICKS = 16;  // 落地缓冲帧数
    private static final int CLIMB_BASE = 2;        // 墙面攀爬速度基数

    // 抓墙/荡墙过渡节奏
    private static final int GRAB_FRAME_STEP = 5;
    private static final int SWING_FRAME_STEP = 4;
    private static final int GRAB_SLIDE = 10;       // 地面滑向墙（靠近减速，最大步长）
    private static final int SWING_SLIDE = 12;      // 顶部滑向墙（靠近减速，最大步长）
    private static final int GRAB_MIN_TICKS  = 36;  // ~1.2s 最短停留
    private static final int SWING_MIN_TICKS = 40;  // ~1.33s 最短停留

    // ===== 闲逛 AI 参数（以 tick 计，30tick≈1s）=====
    private static final int ROAM_COOLDOWN_MIN = 30;   // 1.0s
    private static final int ROAM_COOLDOWN_MAX = 90;   // 3.0s
    private static final int ROAM_WALK_MIN     = 90;   // 3.0s
    private static final int ROAM_WALK_MAX     = 240;  // 8.0s
    private static final int ROAM_IDLE_MIN     = 45;   // 1.5s
    private static final int ROAM_IDLE_MAX     = 150;  // 5.0s
    private static final int ROAM_PAUSE_MIN    = 30;   // 1.0s（墙/顶暂停）
    private static final int ROAM_PAUSE_MAX    = 120;  // 4.0s
    private static final int ROAM_CLIMB_MIN    = 120;  // 4.0s（顶上行走方向持续时间）
    private static final int ROAM_CLIMB_MAX    = 300;  // 10.0s

    private static final int WALL_HANG_MIN     = 180;  // 6.0s 后开始有概率跳下
    private static final int CEILING_HANG_MIN  = 210;  // 7.0s 后开始有概率跳下

    // 动画状态
    public enum State {
        IDLE, WALK, DRAG, FALL, LAND,
        CLIMB_LEFT, CLIMB_RIGHT, CEILING,
        GRAB_LEFT, GRAB_RIGHT,
        SWING_LEFT, SWING_RIGHT
    }

    // 运行模式
    public enum Mode { ROAM, MANUAL }

    // 帧序列
    private ImageIcon[] idleLeft, idleRight;
    private ImageIcon[] walkLeft, walkRight;
    private ImageIcon[] dragLeft, dragRight;
    private ImageIcon[] fallLeft, fallRight;
    private ImageIcon[] landLeft, landRight;
    private ImageIcon[] climbLeft, climbRight;
    private ImageIcon[] ceilingLeft, ceilingRight;
    private ImageIcon[] grabLeft, grabRight;
    private ImageIcon[] swingLeft, swingRight;

    // 运行时
    private State state = State.IDLE;
    private Mode  mode  = Mode.ROAM;     // 默认：闲逛
    private boolean facingRight = true;
    private int xVel = 3;        // 水平速度（正右负左）
    private int yVel = 0;        // 垂直速度（FALL 用）
    private int speed = 2;       // 速度倍率 1..10
    private int frameIndex = 0;
    private int tick = 0;
    private int landTicks = 0;

    // 攀爬方向：-1 向上，+1 向下（用于 CLIMB_*）
    private int climbDirY = -1;

    // 位置/拖拽
    private int winX = 200, winY = 200;
    private int dragOffsetX = 0, dragOffsetY = 0;

    // 过渡计时
    private int grabTicks  = 0;  // 抓墙最短停留计时
    private int swingTicks = 0;  // 荡墙最短停留计时

    // AI 状态
    private final Random rng = new Random();
    private int aiCooldown = 45;        // 下次决策前冷却
    private int aiActionTicks = 0;      // 当前行为剩余时长
    private int wallPauseTicks = 0;     // 墙面暂停剩余
    private int ceilingPauseTicks = 0;  // 顶部暂停剩余
    private int wallHangTicks = 0;      // 在墙累计悬挂时长
    private int ceilingHangTicks = 0;   // 在顶累计悬挂时长
    private int aiSuppressTicks = 0;    // 手动控制后的短时抑制

    private final Timer timer;

    // —— 动作记录 —— //
    private final PetRecorder recorder = new PetRecorder(Paths.get("logs"));
    private boolean recordingEnabled = true;

    // 统一记录入口
    private void logAction(String action, String detail) {
        if (!recordingEnabled) return;
        recorder.log(tick, action, state.name(), winX, winY, detail);
    }
    public void setRecordingEnabled(boolean on) {
        this.recordingEnabled = on;
        logAction(on ? "REC_ON" : "REC_OFF", ""); // 【日志记录】开关记录
    }

    // 画布
    private final JPanel canvas = new JPanel() {
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            ImageIcon[] arr = getCurrentFrames();
            if (arr != null && arr.length > 0) {
                g.drawImage(arr[frameIndex % arr.length].getImage(), 0, 0, SIZE, SIZE, null);
            }
        }
        @Override public Dimension getPreferredSize() { return new Dimension(SIZE, SIZE); }
    };

    // ===== 构造器 =====
    public DesktopPet() {
        // 窗口基设
        setUndecorated(true);
        setAlwaysOnTop(true);
        setBackground(new Color(0,0,0,0));
        setType(Type.UTILITY);
        canvas.setOpaque(false);
        canvas.setBackground(new Color(0,0,0,0));
        setContentPane(canvas);
        pack();

        // 初始：左上角稍离边缘，直接进入下落
        Rectangle wa0 = getWorkArea();
        int margin = 48;
        winX = wa0.x + margin;
        winY = wa0.y + margin;
        setLocation(winX, winY);
        state = State.FALL;
        yVel  = 2;
        frameIndex = 0;
        logAction("INIT", "spawn"); // 【日志记录】初始化

        // 鼠标拖拽（手动时抑制 AI 一会儿）
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragOffsetX = e.getX();
                dragOffsetY = e.getY();
                state = State.DRAG;
                frameIndex = 0;
                aiSuppressTicks = 90;
                logAction("DRAG_START", ""); // 【日志记录】开始拖拽
                repaint();
            }
            @Override public void mouseDragged(MouseEvent e) {
                Point p = e.getLocationOnScreen();
                winX = p.x - dragOffsetX;
                winY = p.y - dragOffsetY;
                setLocation(winX, winY);
            }
            @Override public void mouseReleased(MouseEvent e) {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;
                if (winY < floorY) {
                    state = State.FALL;
                    yVel = Math.max(yVel, 2);
                    frameIndex = 0;
                } else {
                    state = State.IDLE;
                    winY = floorY;
                    setLocation(winX, winY);
                }
                aiSuppressTicks = 90;
                logAction("DRAG_END", ""); // 【日志记录】结束拖拽
            }
        };
        canvas.addMouseListener(ma);
        canvas.addMouseMotionListener(ma);

        // —— 素材加载（全部必备）——
        idleLeft     = loadIconsFromDir("sprites/idle_left");
        idleRight    = loadIconsFromDir("sprites/idle_right");
        walkLeft     = loadIconsFromDir("sprites/walk_left");
        walkRight    = loadIconsFromDir("sprites/walk_right");
        dragLeft     = loadIconsFromDir("sprites/drag_left");
        dragRight    = loadIconsFromDir("sprites/drag_right");
        fallLeft     = loadIconsFromDir("sprites/fall_left");
        fallRight    = loadIconsFromDir("sprites/fall_right");
        landLeft     = loadIconsFromDir("sprites/land_left");
        landRight    = loadIconsFromDir("sprites/land_right");
        climbLeft    = loadIconsFromDir("sprites/climb_left");
        climbRight   = loadIconsFromDir("sprites/climb_right");
        ceilingLeft  = loadIconsFromDir("sprites/ceiling_left");
        ceilingRight = loadIconsFromDir("sprites/ceiling_right");
        grabLeft     = loadIconsFromDir("sprites/grab_left");
        grabRight    = loadIconsFromDir("sprites/grab_right");
        swingLeft    = loadIconsFromDir("sprites/swing_left");
        swingRight   = loadIconsFromDir("sprites/swing_right");

        // 计时器
        timer = new Timer(TICK_MS, e -> onTick());
    }

    // ===== 每帧逻辑 =====
    private void onTick() {
        tick++;

        switch (state) {
            case IDLE: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;

                if (winY < floorY) { state = State.FALL; yVel = 0; frameIndex = 0; break; }

                int left = wa.x, right = wa.x + wa.width - SIZE;
                winX = Math.max(left, Math.min(winX, right));
                winY = floorY;
                setLocation(winX, winY);

                if (tick % 8 == 0) frameIndex++;
                wallHangTicks = 0;
                ceilingHangTicks = 0;
                break;
            }

            case WALK: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;
                if (winY < floorY - 1) { state = State.FALL; yVel = 0; frameIndex = 0; break; }

                int step = Math.max(1, Math.abs(xVel) * Math.max(1, speed) / 2);
                int move = (xVel >= 0) ? step : -step;
                facingRight = (xVel >= 0);

                int left = wa.x, right = wa.x + wa.width - SIZE;
                winX += move;

                // 撞边：顺滑转入抓墙
                if (winX <= left) {
                    winX = left; winY = floorY; setLocation(winX, winY);
                    state = State.GRAB_LEFT; frameIndex = 0; grabTicks = GRAB_MIN_TICKS;
                    logAction("WALK_EDGE_GRAB","LEFT"); // 【日志记录】走路撞边抓左墙
                    break;
                } else if (winX >= right) {
                    winX = right; winY = floorY; setLocation(winX, winY);
                    state = State.GRAB_RIGHT; frameIndex = 0; grabTicks = GRAB_MIN_TICKS;
                    logAction("WALK_EDGE_GRAB","RIGHT"); // 【日志记录】走路撞边抓右墙
                    break;
                }

                winY = floorY;
                setLocation(winX, winY);

                if (tick % 4 == 0) frameIndex++;
                wallHangTicks = 0; ceilingHangTicks = 0;
                break;
            }

            case DRAG: {
                if (tick % 6 == 0) frameIndex++;
                wallHangTicks = 0; ceilingHangTicks = 0;
                break;
            }

            case FALL: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;

                yVel = Math.min(MAX_FALL_SPEED, yVel + GRAVITY);
                winY += yVel;

                if (winY >= floorY) {
                    winY = floorY; yVel = 0; setLocation(winX, winY);
                    state = State.LAND; landTicks = LAND_HOLD_TICKS; frameIndex = 0;
                    logAction("ENTER_STATE","FALL->LAND"); // 【日志记录】落地
                    break;
                }

                int left = wa.x, right = wa.x + wa.width - SIZE;
                winX = Math.max(left, Math.min(winX, right));
                setLocation(winX, winY);

                if (tick % 3 == 0) frameIndex++;
                break;
            }

            case LAND: {
                if (tick % 5 == 0) frameIndex++;
                if (--landTicks <= 0) { state = State.IDLE; frameIndex = 0; logAction("ENTER_STATE","LAND->IDLE"); } // 【日志记录】落地→待机
                break;
            }

            // 地面 → 墙：抓墙过渡
            case GRAB_LEFT: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;
                int targetX = wa.x;

                int dx = targetX - winX;
                int step = Math.max(1, Math.min((int)Math.ceil(Math.abs(dx) * 0.25), GRAB_SLIDE));
                winX += (dx < 0 ? -step : (dx > 0 ? step : 0));
                winY = floorY;
                setLocation(winX, winY);

                if (tick % GRAB_FRAME_STEP == 0) frameIndex++;
                if (grabTicks > 0) grabTicks--;

                boolean atWall = (winX == targetX);
                boolean animDone = (frameIndex >= grabLeft.length);
                if ( (grabTicks <= 0 && atWall) || animDone ) {
                    frameIndex = 0;
                    climbDirY = -1; state = State.CLIMB_LEFT;
                    logAction("ENTER_STATE","GRAB_LEFT->CLIMB_LEFT"); // 【日志记录】抓左墙→上攀
                }
                break;
            }
            case GRAB_RIGHT: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;
                int targetX = wa.x + wa.width - SIZE;

                int dx = targetX - winX;
                int step = Math.max(1, Math.min((int)Math.ceil(Math.abs(dx) * 0.25), GRAB_SLIDE));
                winX += (dx < 0 ? -step : (dx > 0 ? step : 0));
                winY = floorY;
                setLocation(winX, winY);

                if (tick % GRAB_FRAME_STEP == 0) frameIndex++;
                if (grabTicks > 0) grabTicks--;

                boolean atWall = (winX == targetX);
                boolean animDone = (frameIndex >= grabRight.length);
                if ( (grabTicks <= 0 && atWall) || animDone ) {
                    frameIndex = 0;
                    climbDirY = -1; state = State.CLIMB_RIGHT;
                    logAction("ENTER_STATE","GRAB_RIGHT->CLIMB_RIGHT"); // 【日志记录】抓右墙→上攀
                }
                break;
            }

            // 顶部 → 墙：荡到墙过渡
            case SWING_LEFT: {
                Rectangle wa = getWorkArea();
                int topY = wa.y;
                int targetX = wa.x;

                int dx = targetX - winX;
                int step = Math.max(1, Math.min((int)Math.ceil(Math.abs(dx) * 0.25), SWING_SLIDE));
                winX += (dx < 0 ? -step : (dx > 0 ? step : 0));
                winY = topY;
                setLocation(winX, winY);

                if (tick % SWING_FRAME_STEP == 0) frameIndex++;
                if (swingTicks > 0) swingTicks--;

                boolean atWall = (winX == targetX);
                boolean animDone = (frameIndex >= swingLeft.length);

                if ((swingTicks <= 0) && (atWall || animDone)) {
                    frameIndex = 0;
                    climbDirY = +1; state = State.CLIMB_LEFT;
                    logAction("ENTER_STATE","SWING_LEFT->CLIMB_LEFT"); // 【日志记录】荡到左墙→下攀
                }
                break;
            }
            case SWING_RIGHT: {
                Rectangle wa = getWorkArea();
                int topY = wa.y;
                int targetX = wa.x + wa.width - SIZE;

                int dx = targetX - winX;
                int step = Math.max(1, Math.min((int)Math.ceil(Math.abs(dx) * 0.25), SWING_SLIDE));
                winX += (dx < 0 ? -step : (dx > 0 ? step : 0));
                winY = topY;
                setLocation(winX, winY);

                if (tick % SWING_FRAME_STEP == 0) frameIndex++;
                if (swingTicks > 0) swingTicks--;

                boolean atWall = (winX == targetX);
                boolean animDone = (frameIndex >= swingRight.length);

                if ((swingTicks <= 0) && (atWall || animDone)) {
                    frameIndex = 0;
                    climbDirY = +1; state = State.CLIMB_RIGHT;
                    logAction("ENTER_STATE","SWING_RIGHT->CLIMB_RIGHT"); // 【日志记录】荡到右墙→下攀
                }
                break;
            }

            // 墙面攀爬（支持向上/向下）
            case CLIMB_LEFT: {
                Rectangle wa = getWorkArea();
                int topY = wa.y;
                int floorY = wa.y + wa.height - SIZE;

                winX = wa.x;
                int climbStep = Math.max(1, speed * CLIMB_BASE);
                if (wallPauseTicks > 0) {
                    wallPauseTicks--;
                } else {
                    winY += (climbDirY > 0 ? climbStep : -climbStep);
                }

                if (winY <= topY) {
                    winY = topY; facingRight = true; state = State.CEILING; frameIndex = 0;
                    wallHangTicks = 0;
                    logAction("ENTER_STATE","CLIMB_LEFT->CEILING"); // 【日志记录】左墙→顶
                } else if (winY >= floorY) {
                    winY = floorY; state = State.IDLE; frameIndex = 0;
                    wallHangTicks = 0;
                    logAction("ENTER_STATE","CLIMB_LEFT->IDLE"); // 【日志记录】左墙→地面待机
                } else {
                    if (wallPauseTicks > 0) wallHangTicks++; else wallHangTicks = 0;
                }

                setLocation(winX, winY);
                if (tick % 5 == 0) frameIndex++;
                break;
            }
            case CLIMB_RIGHT: {
                Rectangle wa = getWorkArea();
                int topY = wa.y;
                int floorY = wa.y + wa.height - SIZE;

                winX = wa.x + wa.width - SIZE;
                int climbStep = Math.max(1, speed * CLIMB_BASE);
                if (wallPauseTicks > 0) {
                    wallPauseTicks--;
                } else {
                    winY += (climbDirY > 0 ? climbStep : -climbStep);
                }

                if (winY <= topY) {
                    winY = topY; facingRight = false; state = State.CEILING; frameIndex = 0;
                    wallHangTicks = 0;
                    logAction("ENTER_STATE","CLIMB_RIGHT->CEILING"); // 【日志记录】右墙→顶
                } else if (winY >= floorY) {
                    winY = floorY; state = State.IDLE; frameIndex = 0;
                    wallHangTicks = 0;
                    logAction("ENTER_STATE","CLIMB_RIGHT->IDLE"); // 【日志记录】右墙→地面待机
                } else {
                    if (wallPauseTicks > 0) wallHangTicks++; else wallHangTicks = 0;
                }

                setLocation(winX, winY);
                if (tick % 5 == 0) frameIndex++;
                break;
            }

            case CEILING: {
                Rectangle wa = getWorkArea();
                int topY = wa.y;
                int left = wa.x, right = wa.x + wa.width - SIZE;

                winY = topY;

                if (ceilingPauseTicks > 0) {
                    ceilingPauseTicks--;
                } else {
                    int step = Math.max(1, Math.max(1, speed) * 2);
                    int move = facingRight ? step : -step;
                    winX += move;

                    if (winX <= left)  {
                        winX = left;  setLocation(winX, winY);
                        state = State.SWING_LEFT;  frameIndex = 0; swingTicks = SWING_MIN_TICKS;
                        logAction("CEILING_EDGE_SWING","LEFT"); // 【日志记录】顶到左边→荡左墙
                        break;
                    }
                    if (winX >= right) {
                        winX = right; setLocation(winX, winY);
                        state = State.SWING_RIGHT; frameIndex = 0; swingTicks = SWING_MIN_TICKS;
                        logAction("CEILING_EDGE_SWING","RIGHT"); // 【日志记录】顶到右边→荡右墙
                        break;
                    }
                }

                setLocation(winX, winY);
                if (tick % 4 == 0) frameIndex++;

                ceilingHangTicks++;
                break;
            }
        }

        // ===== 闲逛 AI 调度 =====
        aiTick();

        canvas.repaint();
    }

    // ===== AI 调度（默认 ROAM）=====
    private void aiTick() {
        if (mode != Mode.ROAM) return;
        if (aiSuppressTicks > 0) { aiSuppressTicks--; return; }

        switch (state) {
            case DRAG:
            case FALL:
            case LAND:
            case GRAB_LEFT:
            case GRAB_RIGHT:
            case SWING_LEFT:
            case SWING_RIGHT:
                return;

            case IDLE: {
                if (aiCooldown > 0) { aiCooldown--; return; }

                int r = rng.nextInt(100);
                if (r < 55) {
                    setWalk();
                    if (rng.nextBoolean()) setFacingRight(true); else setFacingRight(false);
                    aiActionTicks = rand(ROAM_WALK_MIN, ROAM_WALK_MAX);
                    logAction("AI_DECISION","WALK dir=" + (facingRight?"R":"L")); // 【日志记录】AI决定走路
                } else if (r < 75) {
                    setIdle();
                    aiActionTicks = rand(ROAM_IDLE_MIN, ROAM_IDLE_MAX);
                    logAction("AI_DECISION","IDLE"); // 【日志记录】AI决定发呆
                } else if (r < 88) {
                    startClimbLeft();
                    logAction("AI_DECISION","CLIMB_LEFT"); // 【日志记录】AI决定去左墙
                } else {
                    startClimbRight();
                    logAction("AI_DECISION","CLIMB_RIGHT"); // 【日志记录】AI决定去右墙
                }
                aiCooldown = rand(ROAM_COOLDOWN_MIN, ROAM_COOLDOWN_MAX);
                return;
            }

            case WALK: {
                if (aiActionTicks > 0) {
                    aiActionTicks--;
                } else {
                    setIdle();
                    aiCooldown = rand(ROAM_COOLDOWN_MIN, ROAM_COOLDOWN_MAX);
                    logAction("AI_DECISION","WALK->IDLE"); // 【日志记录】AI停止走路
                }
                return;
            }

            case CLIMB_LEFT:
            case CLIMB_RIGHT: {
                if (wallPauseTicks <= 0 && rng.nextInt(120) == 0) {
                    wallPauseTicks = rand(ROAM_PAUSE_MIN, ROAM_PAUSE_MAX);
                    logAction("AI_WALL","PAUSE " + wallPauseTicks + " ticks"); // 【日志记录】AI墙上暂停
                } else if (wallPauseTicks <= 0 && rng.nextInt(180) == 0) {
                    climbDirY = rng.nextBoolean() ? -1 : +1;
                    logAction("AI_WALL","FLIP_DIR " + (climbDirY>0?"DOWN":"UP")); // 【日志记录】AI变换攀爬方向
                }

                if (wallHangTicks > WALL_HANG_MIN && tick % 30 == 0 && rng.nextInt(5) == 0) {
                    state = State.FALL; yVel = 0; frameIndex = 0;
                    wallHangTicks = 0;
                    logAction("HANG_DROP","WALL"); // 【日志记录】墙上挂太久→跳下
                }
                return;
            }

            case CEILING: {
                if (ceilingPauseTicks <= 0) {
                    if (rng.nextInt(150) == 0) {
                        ceilingPauseTicks = rand(ROAM_PAUSE_MIN, ROAM_PAUSE_MAX);
                        logAction("AI_TOP","PAUSE " + ceilingPauseTicks + " ticks"); // 【日志记录】AI顶上暂停
                    } else if (aiActionTicks <= 0) {
                        facingRight = rng.nextBoolean();
                        aiActionTicks = rand(ROAM_CLIMB_MIN, ROAM_CLIMB_MAX);
                        logAction("AI_TOP","FLIP_DIR " + (facingRight?"R":"L")); // 【日志记录】AI顶上换向
                    } else {
                        aiActionTicks--;
                    }
                }

                if (ceilingHangTicks > CEILING_HANG_MIN && tick % 30 == 0 && rng.nextInt(5) == 0) {
                    state = State.FALL; yVel = 0; frameIndex = 0;
                    ceilingHangTicks = 0;
                    logAction("HANG_DROP","CEILING"); // 【日志记录】顶上挂太久→跳下
                }
                return;
            }
        }
    }

    private int rand(int a, int b) { return a + rng.nextInt(b - a + 1); }

    // 当前帧组
    private ImageIcon[] getCurrentFrames() {
        switch (state) {
            case IDLE:        return facingRight ? idleRight   : idleLeft;
            case WALK:        return facingRight ? walkRight   : walkLeft;
            case DRAG:        return facingRight ? dragRight   : dragLeft;
            case FALL:        return facingRight ? fallRight   : fallLeft;
            case LAND:        return facingRight ? landRight   : landLeft;
            case CLIMB_LEFT:  return climbLeft;
            case CLIMB_RIGHT: return climbRight;
            case CEILING:     return facingRight ? ceilingRight : ceilingLeft;
            case GRAB_LEFT:   return grabLeft;
            case GRAB_RIGHT:  return grabRight;
            case SWING_LEFT:  return swingLeft;
            case SWING_RIGHT: return swingRight;
            default:          return facingRight ? idleRight   : idleLeft;
        }
    }

    // ===== 面板可调用的方法（手动时短暂抑制 AI，并写日志）=====
    public void startRunning() { if (!isVisible()) setVisible(true); if (!timer.isRunning()) timer.start(); }
    public void stopRunning()  { if (timer.isRunning()) timer.stop(); }
    public void setIdle()      { state = State.IDLE; frameIndex = 0; aiSuppressTicks = 60; logAction("ENTER_STATE","setIdle"); } // 【日志记录】手动待机
    public void setWalk()      { state = State.WALK; frameIndex = 0; if (xVel == 0) xVel = facingRight ? 3 : -3; aiSuppressTicks = 60; logAction("ENTER_STATE","setWalk"); } // 【日志记录】手动行走
    public void setFacingRight(boolean right) {
        facingRight = right; int v = Math.max(1, Math.abs(xVel)); xVel = right ? v : -v; aiSuppressTicks = 60;
        logAction("FACE", right ? "RIGHT" : "LEFT"); // 【日志记录】手动改朝向
    }
    public void setSpeed(int s) { speed = Math.max(1, Math.min(10, s)); logAction("SPEED", String.valueOf(speed)); } // 【日志记录】手动改速度
    public boolean isFacingRight() { return facingRight; }
    public State getPetState() { return state; }

    // 切换模式（预留接口）
    public void setModeRoam()   { mode = Mode.ROAM;  logAction("MODE","ROAM"); }   // 【日志记录】改模式
    public void setModeManual() { mode = Mode.MANUAL; aiSuppressTicks = 120; logAction("MODE","MANUAL"); } // 【日志记录】改模式

    // —— 攀爬触发（自动选择“抓墙”或“荡墙”过渡）——
    public void startClimbLeft()  {
        Rectangle wa = getWorkArea();
        if (state == State.CEILING) {
            state = State.SWING_LEFT;  frameIndex = 0; swingTicks = SWING_MIN_TICKS;
        } else {
            winY = wa.y + wa.height - SIZE; setLocation(winX, winY);
            state = State.GRAB_LEFT;   frameIndex = 0; grabTicks = GRAB_MIN_TICKS;
        }
        aiSuppressTicks = 60;
        logAction("CMD","startClimbLeft"); // 【日志记录】手动开始左侧攀
    }
    public void startClimbRight() {
        Rectangle wa = getWorkArea();
        if (state == State.CEILING) {
            state = State.SWING_RIGHT; frameIndex = 0; swingTicks = SWING_MIN_TICKS;
        } else {
            winY = wa.y + wa.height - SIZE; setLocation(winX, winY);
            state = State.GRAB_RIGHT;  frameIndex = 0; grabTicks = GRAB_MIN_TICKS;
        }
        aiSuppressTicks = 60;
        logAction("CMD","startClimbRight"); // 【日志记录】手动开始右侧攀
    }
    public void startCeiling(boolean toRight) {
        Rectangle wa = getWorkArea();
        winY = wa.y;
        winX = Math.max(wa.x, Math.min(winX, wa.x + wa.width - SIZE));
        setLocation(winX, winY);
        state = State.CEILING; facingRight = toRight; frameIndex = 0;
        aiSuppressTicks = 60;
        logAction("CMD", toRight ? "startCeiling RIGHT" : "startCeiling LEFT"); // 【日志记录】手动挂顶
    }

    // ===== 刷新素材（重载 PNG） =====
    public void reloadSprites() {
        logAction("RELOAD_SPRITES", ""); // 【日志记录】刷新素材

        flushIcons(idleLeft);  flushIcons(idleRight);
        flushIcons(walkLeft);  flushIcons(walkRight);
        flushIcons(dragLeft);  flushIcons(dragRight);
        flushIcons(fallLeft);  flushIcons(fallRight);
        flushIcons(landLeft);  flushIcons(landRight);
        flushIcons(climbLeft); flushIcons(climbRight);
        flushIcons(ceilingLeft); flushIcons(ceilingRight);
        flushIcons(grabLeft);  flushIcons(grabRight);
        flushIcons(swingLeft); flushIcons(swingRight);

        idleLeft     = loadIconsFromDir("sprites/idle_left");
        idleRight    = loadIconsFromDir("sprites/idle_right");
        walkLeft     = loadIconsFromDir("sprites/walk_left");
        walkRight    = loadIconsFromDir("sprites/walk_right");
        dragLeft     = loadIconsFromDir("sprites/drag_left");
        dragRight    = loadIconsFromDir("sprites/drag_right");
        fallLeft     = loadIconsFromDir("sprites/fall_left");
        fallRight    = loadIconsFromDir("sprites/fall_right");
        landLeft     = loadIconsFromDir("sprites/land_left");
        landRight    = loadIconsFromDir("sprites/land_right");
        climbLeft    = loadIconsFromDir("sprites/climb_left");
        climbRight   = loadIconsFromDir("sprites/climb_right");
        ceilingLeft  = loadIconsFromDir("sprites/ceiling_left");
        ceilingRight = loadIconsFromDir("sprites/ceiling_right");
        grabLeft     = loadIconsFromDir("sprites/grab_left");
        grabRight    = loadIconsFromDir("sprites/grab_right");
        swingLeft    = loadIconsFromDir("sprites/swing_left");
        swingRight   = loadIconsFromDir("sprites/swing_right");

        frameIndex = 0;
        tick = 0;
        canvas.repaint();
    }

    // ===== 工具：目录加载 PNG（按文件名排序），缩放到 SIZE×SIZE =====
    private ImageIcon[] loadIconsFromDir(String dirPath) {
        try {
            Path dir = Paths.get(dirPath);
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                 .sorted()
                 .forEach(files::add);
            }
            if (files.isEmpty()) throw new IllegalStateException("No png in " + dirPath);

            List<ImageIcon> icons = new ArrayList<>();
            for (Path p : files) {
                BufferedImage bi = javax.imageio.ImageIO.read(p.toFile());
                Image scaled = bi.getScaledInstance(SIZE, SIZE, Image.SCALE_SMOOTH);
                icons.add(new ImageIcon(scaled));
            }
            return icons.toArray(new ImageIcon[0]);
        } catch (Exception e) {
            throw new RuntimeException("Load failed: " + dirPath, e);
        }
    }

    // 释放图像缓存
    private void flushIcons(ImageIcon[] arr) {
        if (arr == null) return;
        for (ImageIcon ic : arr) {
            if (ic != null && ic.getImage() != null) ic.getImage().flush();
        }
    }

    // 当前显示器的工作区（扣掉任务栏/停靠栏）
    private Rectangle getWorkArea() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc == null) {
            gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
        }
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(
                b.x + in.left, b.y + in.top,
                b.width - in.left - in.right,
                b.height - in.top - in.bottom
        );
    }
}
