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
    public static final int SIZE = 128;               // 显示尺寸
    private static final int TICK_MS = 33;            // ~30FPS
    private static final int GRAVITY = 2;             // 重力加速度
    private static final int MAX_FALL_SPEED = 24;     // 终端速度
    private static final int LAND_HOLD_TICKS = 16;    // 落地缓冲帧数
    private static final int LAND_WAKE_HOLD_TICKS = 24; // 被拖醒落地后“迷糊站稳”时长
    private static final int CLIMB_BASE = 2;          // 墙面攀爬速度基数

    // 抓墙/荡墙/附顶/下墙 过渡节奏
    private static final int GRAB_FRAME_STEP = 5;
    private static final int SWING_FRAME_STEP = 4;    // 每4帧换一次 swing 图
    private static final int CEIL_ATTACH_FRAME_STEP = SWING_FRAME_STEP;
    private static final int DISMOUNT_FRAME_STEP = 5;

    private static final int GRAB_SLIDE = 10;         // 地面滑向墙（靠近减速，最大步长）
    private static final int SWING_SLIDE = 12;        // 顶部滑向墙（靠近减速，最大步长）
    private static final int GRAB_MIN_TICKS  = 36;    // ~1.2s 地→墙抓墙最短停留
    private static final int SWING_MIN_TICKS = 60;    // ~2.0s 顶→墙荡下最短停留
    private static final int CEIL_ATTACH_MIN_TICKS = 60; // ~2.0s 墙→顶附顶最短停留
    private static final int DISMOUNT_MIN_TICKS = 30; // 墙→地 过渡

    // —— 落地弹跳参数 —— //
    private static final double BOUNCE_RESTITUTION = 0.3; // 0.10~0.30 轻弹即可
    private static final int    BOUNCE_MIN_SPEED   = 1;    // 落地速度小于它就不反弹

    // —— 天花板释放回吸允许的纵向偏差 —— //
    private static final int CEILING_DETACH_TOLERANCE = 24; // 贴顶释放<=24px则回吸

    // —— 拖拽“短提起”阈值（不算真正拖拽）—— //
    private static final double DRAG_SHORT_LIFT_RATIO = 0.50; // 抬起 < 身高50% 视作“短提起”

    // —— 运行时标志 —— //
    private boolean bounceArmed = false;    // 是否允许下一次落地触发弹跳
    private boolean didSmallBounce = false; // 本次离地后是否已弹过一次
    private double  preImpactVy = 0;        // 撞地前瞬时垂直速度

    // 拖拽相关
    private int     dragPressWinY = 0;      // 鼠标按下时窗口Y（或角色顶点Y）
    private boolean dragExceededThreshold = false; // 是否抬过阈值
    private boolean dragWasOnGroundAtPress = false; // 按下时是否在地面

    // 走路加减速
    private static final int WALK_ACCEL = 1;
    private static final int WALK_BASE = 2;

    // 闲逛 AI 参数（tick，30tick≈1s）
    private static final int ROAM_COOLDOWN_MIN = 90;
    private static final int ROAM_COOLDOWN_MAX = 270;
    private static final int ROAM_WALK_MIN     = 90;
    private static final int ROAM_WALK_MAX     = 240;
    private static final int ROAM_IDLE_MIN     = 45;
    private static final int ROAM_IDLE_MAX     = 150;
    private static final int ROAM_PAUSE_MIN    = 30;
    private static final int ROAM_PAUSE_MAX    = 120;
    private static final int ROAM_CLIMB_MIN    = 210;
    private static final int ROAM_CLIMB_MAX    = 300;
    private static final int WALL_HANG_MIN     = 180;
    private static final int CEILING_HANG_MIN  = 210;

    // 锁屏/休眠恢复检测（恢复后显示 SLEEP；保持不自动醒）
    private static final long RESUME_GAP_MS = 30_000; // 30s 视作休眠/锁屏

    // —— 新增：鼠标全局空闲阈值（5分钟） —— //
    private static final long MOUSE_IDLE_MS = 300_000L;

    // —— 新增：打哈欠参数 —— //
    private static final int YAWN_MIN_TICKS = 45;     // ~1.5s
    private static final int YAWN_FRAME_STEP = 5;
    private static final int CORNER_APPROACH_MAX_STEP = 10; // 走角落最大步长

    // ======= 健康提醒：常量 =======
    private static final long ACTIVE_IDLE_MS = 60_000L;     // 超过1分钟无鼠标/键盘 → 视为非活跃，暂停计时
    private static final int REMIND_MINUTES = 1;           // 每1分钟一个提醒
    private static final int FPS = Math.max(1, Math.round(1000f / TICK_MS));
    private static final int REMIND_TICKS = REMIND_MINUTES * 60 * FPS;
    private static final int BUBBLE_OFFSET_Y = 12;          // 气泡位于宠物上方的偏移（像素）
    private static final int BUBBLE_OFFSET_X = 0;

    // 健康提醒：枚举
    private enum RemindKind { STAND, SIT }

    // 健康提醒：运行时
    private long lastUserActionTimeMs = System.currentTimeMillis(); // 最近一次用户输入时间
    private int activeUseTicks = 0;                                  // 只在“活跃”时累计
    private boolean reminderEnabled = true;                          // 可被面板开关
    private RemindKind nextRemind = RemindKind.STAND;                // 下一次弹什么
    private boolean waitingClick = false;                            // 正在等待用户点掉气泡？

    // —— 健康提醒：调试可视 —— //
    private boolean dbgUserActiveByInput = false;
    private boolean dbgFullscreen = false;
    private boolean dbgAudioBusy = false;
    private boolean dbgUserActive = false;
    private long    dbgIdleGapMs = 0;

    // 视频场景：检测设置
    private boolean detectFullscreen = true; // 可被面板开关
    private boolean detectAudio = true;      // 可被面板开关（若没有Loopback会自动降级）

    // —— 气泡窗口 —— //
    private JWindow bubbleWin = null;
    private JLabel bubbleLabel = null;
    private ImageIcon standBubbleIcon = null;
    private ImageIcon sitBubbleIcon = null;
    private boolean bubbleVisible = false;

    // —— 避免多次点击导致的抖动 —— //
    private long lastBubbleCloseMs = 0L;

    // —— 音频探测（尽力而为：Loopback，如 Stereo Mix） —— //
    private volatile boolean audioProbeAvailable = false;
    private volatile double audioLevelRms = 0.0;

    // —— 全屏检测（JNA） —— //
    private static class WinRect { int x,y,w,h; }

    // 动画状态
    public enum State {
        IDLE, WALK, DRAG, FALL, LAND,
        WALK_TO_LEFT, WALK_TO_RIGHT,               // 必须先走到边缘
        CLIMB_LEFT, CLIMB_RIGHT, CEILING,
        GRAB_LEFT, GRAB_RIGHT,                     // 地→墙：只播 grab
        SWING_LEFT, SWING_RIGHT,                   // 顶→墙：只播 swing
        CEILING_ATTACH_LEFT, CEILING_ATTACH_RIGHT, // 墙→顶：只播 swing
        DISMOUNT_LEFT, DISMOUNT_RIGHT,             // 墙→地：只播 grab

        // —— 新增 —— //
        SLEEP_WALK_TO_CORNER,                      // 【睡前走角落】只用走路帧，不会抓墙
        YAWN,                                      // 【打哈欠】只用 yawn_* 帧

        SLEEP, WAKE,                               // 睡觉 / 点击醒来
        DRAG_WAKE, FALL_WAKE, LAND_WAKE            // 拖拽叫醒三段：拖拽→掉落→落地
    }

    // 运行模式
    public enum Mode { ROAM, MANUAL }

    // 落地后计划
    private enum AfterLand {
        NONE,
        WALK_TO_LEFT_CLIMB,
        WALK_TO_RIGHT_CLIMB,
        // —— 新增：为“鼠标空闲→回地面→走角落→哈欠→睡觉”的落地衔接 —— //
        SLEEP_PLAN_WALK_TO_CORNER
    }

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
    private ImageIcon[] sleepFrames, wakeFrames;

    // 被拖醒三段帧
    private ImageIcon[] dragWakeLeft, dragWakeRight;
    private ImageIcon[] fallWakeLeft, fallWakeRight;
    private ImageIcon[] landWakeLeft, landWakeRight;

    // —— 新增：打哈欠帧 —— //
    private ImageIcon[] yawnLeft, yawnRight;

    // 运行时
    private State state = State.IDLE;
    private Mode  mode  = Mode.ROAM;
    private boolean facingRight = true;
    private int xVel = 3;
    private int yVel = 0;
    private int speed = 2;
    private int frameIndex = 0;
    private int tick = 0;
    private int landTicks = 0;
    private int landWakeTicks = 0;
    private int wakeShowTicks = 0; // 点击叫醒动画计时

    // 行走平滑速度
    private int walkVx = 0;
    private int walkTarget = 0;

    // 攀爬方向：-1 向上，+1 向下
    private int climbDirY = -1;

    // 位置/拖拽
    private int winX = 200, winY = 200;
    private int dragOffsetX = 0, dragOffsetY = 0;

    // 弹跳控制
    private boolean hasBounced = false; // 本次落地是否已反弹过
    private int     preImpactYVel = 0;  // 落地前一帧的纵向速度

    // 低高度拖起判定
    private int  dragPressWindowY = 0;  // 按下时窗口Y
    private int  dragMaxLiftPx    = 0;  // 拖动过程中抬起的最大高度

    // 拖拽按下瞬间所处位置（用于释放时分流）
    private boolean wasOnGroundAtPress  = false;
    private boolean wasOnCeilingAtPress = false;
    private State   stateAtPress        = State.IDLE;

    // —— 低高度短提起的“idle 下落”动画 —— //
    private boolean softDropActive = false; // 是否在进行“idle姿态慢慢落地”
    private int     softDropVy     = 0;     // 软下落的临时纵向速度

    // 过渡计时
    private int grabTicks  = 0;
    private int swingTicks = 0;
    private int attachTicks = 0;
    private int dismountTicks = 0;
    private int yawnTicks = 0;     // —— 新增：打哈欠计时 —— //

    // AI 状态
    private final Random rng = new Random();
    private int aiCooldown = 45;
    private int aiActionTicks = 0;
    private int wallPauseTicks = 0;
    private int ceilingPauseTicks = 0;
    private int wallHangTicks = 0;
    private int ceilingHangTicks = 0;
    private int aiSuppressTicks = 0;

    private AfterLand afterLand = AfterLand.NONE;

    // 表面锁（过渡防抖）
    private int surfaceLatchTicks = 0;  // 【日志记录】表面锁

    private final Timer timer;

    // 动作记录
    private final PetRecorder recorder = new PetRecorder(Paths.get("logs"));
    private boolean recordingEnabled = true;
    private void logAction(String action, String detail) {
        if (!recordingEnabled) return;
        recorder.log(tick, action, state.name(), winX, winY, detail);
    }
    public void setRecordingEnabled(boolean on) {
        if (on) { this.recordingEnabled = true; logAction("REC_ON",""); }
        else    { logAction("REC_OFF",""); this.recordingEnabled = false; }
    }
    public boolean isRecordingEnabled() { return recordingEnabled; }

    // 系统时间
    private long lastRealMs = System.currentTimeMillis();

    // —— 新增：鼠标空闲检测相关 —— //
    private long lastMouseMoveMs = System.currentTimeMillis(); // 最后一次鼠标活动时间
    private boolean idleSleepPlanActive = false;               // 是否正在执行“睡前走角落”计划
    private int idleTargetX = 0;                               // 目标角落 X
    private boolean idleCornerRight = false;                   // 目标是否右角
    private boolean pressedDuringSleep = false;
    private boolean draggedDuringSleep = false;

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

        // 角色真正离地（即将进入空中/下落）
        bounceArmed = true;
        didSmallBounce = false;


        // 初始：左上角稍离边缘，直接进入下落
        Rectangle wa0 = getWorkArea();
        int margin = 48;
        winX = wa0.x + margin;
        winY = wa0.y + margin;
        setLocation(winX, winY);
        state = State.FALL;
        hasBounced = false;
        yVel  = 2;
        frameIndex = 0;
        logAction("INIT", "spawn"); // 【日志记录】初始化

        // 鼠标交互（含睡眠态下的点击/拖拽叫醒）
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                // —— 低高度拖起：记录起点 ——
                // 记录按下时窗口Y，并重置“最大抬起高度”
                dragPressWindowY = getY();
                dragMaxLiftPx = 0;
                // 记录按下瞬间的状态（用于 mouseReleased 分流）
                stateAtPress        = state;
                wasOnGroundAtPress  = isOnGround();
                wasOnCeilingAtPress = (state == State.CEILING
                        || state == State.CEILING_ATTACH_LEFT
                        || state == State.CEILING_ATTACH_RIGHT
                        || state == State.SWING_LEFT
                        || state == State.SWING_RIGHT);


                // SLEEP 下先标记按下，等待判定是否拖拽
                if (state == State.SLEEP) {
                    pressedDuringSleep = true;
                    draggedDuringSleep = false;
                    dragOffsetX = e.getX();
                    dragOffsetY = e.getY();
                    logAction("SLEEP_PRESS", ""); // 【日志记录】
                    return;
                }
                // 非 SLEEP：正常进入 DRAG
                dragOffsetX = e.getX();
                dragOffsetY = e.getY();
                state = State.DRAG;
                frameIndex = 0;
                aiSuppressTicks = 90;
                logAction("DRAG_START", ""); // 【日志记录】
                repaint();
            }
            @Override public void mouseDragged(MouseEvent e) {
                Point p = e.getLocationOnScreen();
                if (pressedDuringSleep) {
                    // 从 SLEEP 被拖拽叫醒：进入 DRAG_WAKE
                    if (state != State.DRAG_WAKE) {
                        state = State.DRAG_WAKE;
                        frameIndex = 0;
                        aiSuppressTicks = 90;
                        logAction("DRAG_WAKE_START", ""); // 【日志记录】
                    }
                    draggedDuringSleep = true;
                    winX = p.x - dragOffsetX;
                    winY = p.y - dragOffsetY;
                    setLocation(winX, winY);

                    // —— 低高度拖起：跟踪最大抬起高度 —— //
                    int liftedA = Math.max(0, dragPressWindowY - getY());
                    if (liftedA > dragMaxLiftPx) dragMaxLiftPx = liftedA;

                    return;
                }
                // 普通拖拽
                winX = p.x - dragOffsetX;
                winY = p.y - dragOffsetY;
                setLocation(winX, winY);

                // —— 低高度拖起：跟踪最大抬起高度 —— //
                int liftedB = Math.max(0, dragPressWindowY - getY());
                if (liftedB > dragMaxLiftPx) dragMaxLiftPx = liftedB;
            }
            @Override public void mouseReleased(MouseEvent e) {
                // —— 低高度拖起：抬起 < 50% 身高 → 开启“idle姿态慢慢落地”，不瞬移 —— //
                if (dragMaxLiftPx < (int)(SIZE * DRAG_SHORT_LIFT_RATIO) && isOnGround()) {
                    // 开启软下落流程（保持 idle 姿态，不切 FALL）
                    softDropActive = true;
                    softDropVy = 0;          // 从很小速度开始
                    state = State.IDLE;      // 保持 idle 帧
                    frameIndex = 0;

                    logAction("DRAG_SHORT_LIFT_SOFTDROP",""); // 【日志记录四个字】
                    dragMaxLiftPx = 0;
                    return; // 不再走正常的 FALL/LAND
                }

                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;

                if (pressedDuringSleep) {
                    // 点击叫醒（无拖拽）
                    if (!draggedDuringSleep) {
                        state = State.WAKE; frameIndex = 0;
                        wakeShowTicks = 45; // ~1.5s
                        logAction("WAKE_BY_CLICK",""); // 【日志记录】
                    } else {
                        // 拖拽松手：决定走 FALL_WAKE 还是直接 LAND_WAKE
                        if (winY < floorY) {
                            state = State.FALL_WAKE; frameIndex = 0;
                            hasBounced = false;
                            yVel = Math.max(2, yVel);
                            logAction("FALL_WAKE_START",""); // 【日志记录】
                        } else {
                            state = State.LAND_WAKE; frameIndex = 0;
                            landWakeTicks = LAND_WAKE_HOLD_TICKS;
                            winY = floorY; setLocation(winX, winY);
                            logAction("LAND_WAKE_DIRECT",""); // 【日志记录】
                        }
                    }
                    pressedDuringSleep = false;
                    draggedDuringSleep = false;
                    aiSuppressTicks = 90;
                    return;
                }

                // 非 SLEEP 的普通拖拽释放
                if (state == State.DRAG) {
                    // —— 如果“按下时在天花板”，优先回吸到天花板（除非拖得离顶太远） —— //
                    if (wasOnCeilingAtPress) {
                        Rectangle wa2 = getWorkArea();
                        int topY2 = wa2.y;
                        // 距离顶边不超过容差 → 视为仍旧贴顶，回吸；否则走公共逻辑（可能下落）
                        if (getY() - topY2 <= CEILING_DETACH_TOLERANCE) {
                            int left2 = wa2.x, right2 = wa2.x + wa2.width - SIZE;
                            winX = Math.max(left2, Math.min(winX, right2));
                            winY = topY2;
                            setLocation(winX, winY);
                            state = State.CEILING; frameIndex = 0;
                            yVel = 0; surfaceLatchTicks = 20;
                            aiSuppressTicks = 60;
                            logAction("DRAG_RELEASE_BACK_TO_CEILING", "");
                            dragMaxLiftPx = 0;
                            wasOnCeilingAtPress = false; wasOnGroundAtPress = false;
                            return;
                        }
                        // 若已拉离顶部超过容差，就不回吸；继续走下面的公共逻辑（可能 FALL）
                    }

                    // —— 低高度拖起（仅当“按下时在地面”才成立） → 回地面 idle —— //
                    if (dragMaxLiftPx < (int)(SIZE * DRAG_SHORT_LIFT_RATIO) && wasOnGroundAtPress) {
                        winY = floorY; setLocation(winX, winY);
                        yVel = 0;
                        state = State.IDLE; frameIndex = 0;
                        aiSuppressTicks = 60;
                        logAction("DRAG_SHORT_LIFT",""); // 【日志记录】
                        dragMaxLiftPx = 0;
                        wasOnGroundAtPress = false; wasOnCeilingAtPress = false;
                        return;
                    }

                    // —— 其它情况：根据当前位置决定 FALL 或 IDLE —— //
                    if (winY < floorY) {
                        state = State.FALL;
                        hasBounced = false;
                        yVel = Math.max(yVel, 2);
                        frameIndex = 0;
                    } else {
                        state = State.IDLE;
                        winY = floorY;
                        setLocation(winX, winY);
                    }

                    // 重置标志 & 收尾
                    wasOnGroundAtPress = false; 
                    wasOnCeilingAtPress = false;
                    aiSuppressTicks = 90;
                    logAction("DRAG_END", ""); // 【日志记录】
                }
            }
        };
        canvas.addMouseListener(ma);
        canvas.addMouseMotionListener(ma);

        // —— 全局鼠标事件监听：用于“1分钟不动就执行睡前走角落计划” —— //
        Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {
            int id = ev.getID();
            if (id == MouseEvent.MOUSE_MOVED ||
                id == MouseEvent.MOUSE_DRAGGED ||
                id == MouseEvent.MOUSE_PRESSED ||
                id == MouseEvent.MOUSE_RELEASED ||
                id == MouseEvent.MOUSE_WHEEL) {
                lastMouseMoveMs = System.currentTimeMillis();
            }
        }, AWTEvent.MOUSE_EVENT_MASK
         | AWTEvent.MOUSE_MOTION_EVENT_MASK
         | AWTEvent.MOUSE_WHEEL_EVENT_MASK);

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
        sleepFrames  = loadIconsFromDir("sprites/sleep");
        wakeFrames   = loadIconsFromDir("sprites/wake");

        dragWakeLeft = loadIconsFromDir("sprites/drag_wake_left");
        dragWakeRight= loadIconsFromDir("sprites/drag_wake_right");
        fallWakeLeft = loadIconsFromDir("sprites/fall_wake_left");
        fallWakeRight= loadIconsFromDir("sprites/fall_wake_right");
        landWakeLeft = loadIconsFromDir("sprites/land_wake_left");
        landWakeRight= loadIconsFromDir("sprites/land_wake_right");

        // —— 新增：打哈欠 —— //
        yawnLeft     = loadIconsFromDir("sprites/yawn_left");
        yawnRight    = loadIconsFromDir("sprites/yawn_right");

        // === 全局用户输入监听：鼠标 & 键盘，更新 lastUserActionTimeMs ===
        Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {
            int id = ev.getID();
            switch (id) {
                case MouseEvent.MOUSE_MOVED:
                case MouseEvent.MOUSE_DRAGGED:
                case MouseEvent.MOUSE_PRESSED:
                case MouseEvent.MOUSE_RELEASED:
                case MouseEvent.MOUSE_WHEEL:
                case KeyEvent.KEY_PRESSED:
                case KeyEvent.KEY_RELEASED:
                case KeyEvent.KEY_TYPED:
                    lastUserActionTimeMs = System.currentTimeMillis();
                    break;
            }
        }, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK |
           AWTEvent.MOUSE_WHEEL_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);

        // === 加载两张气泡 PNG ===
        standBubbleIcon = new ImageIcon("assets/stand_bubble.png");
        sitBubbleIcon   = new ImageIcon("assets/sit_bubble.png");

        // === 初始化气泡窗口（点击即可关闭） ===
        bubbleWin = new JWindow();
        bubbleWin.setAlwaysOnTop(true);
        bubbleWin.setBackground(new Color(0,0,0,0));
        bubbleLabel = new JLabel();
        bubbleLabel.setOpaque(false);
        bubbleWin.getContentPane().add(bubbleLabel);
        bubbleWin.pack();
        positionBubble();
        bubbleWin.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (bubbleVisible && e.getClickCount() == 1) {
                    hideBubbleAndAdvance();
                }
            }
        });


        // === 尝试启动音频采样（Loopback设备，如 Stereo Mix）。失败则自动降级 ===
        startAudioProbeThread();

        // 计时器
        timer = new Timer(TICK_MS, e -> onTick());
    }

    // ===== 每帧逻辑 =====
    private void onTick() {
        // ===== 健康提醒：活跃判定 + 计时 =====
        long nowMs = System.currentTimeMillis();
        dbgIdleGapMs = nowMs - lastUserActionTimeMs;

        dbgUserActiveByInput = (dbgIdleGapMs <= ACTIVE_IDLE_MS);

        // —— 视频活跃：全屏 + 音频 —— //
        boolean fullscreen = detectFullscreen && isForegroundFullscreen();
        boolean audioBusy  = detectAudio && audioProbeAvailable && (audioLevelRms > 0.01);

        dbgFullscreen = fullscreen;
        dbgAudioBusy  = audioBusy;

        // 若有音频探测能力：全屏 && 音频 才判定活跃；若无音频探测：全屏单独成立
        dbgUserActive = dbgUserActiveByInput || (fullscreen && (audioProbeAvailable ? audioBusy : true));

        boolean userActive = dbgUserActive;

        if (reminderEnabled && !waitingClick) {
            if (userActive) {
                if (activeUseTicks < REMIND_TICKS) activeUseTicks++;
                if (activeUseTicks >= REMIND_TICKS) {
                    showBubbleFor(nextRemind); // 到点弹泡
                    waitingClick = true;       // 等用户点掉
                }
            }
        }
        // 若气泡可见，让它跟随宠物窗口
        if (bubbleVisible) {
            Point p = getLocationOnScreen();
            int bx = p.x + BUBBLE_OFFSET_X;
            int by = p.y - (bubbleWin.getHeight() + BUBBLE_OFFSET_Y);
            bubbleWin.setLocation(bx, by);
        }


        tick++;

        // 表面锁倒计时
        if (surfaceLatchTicks > 0) surfaceLatchTicks--;  // 【日志记录】表面锁递减

        // —— 休眠/恢复检测：一旦检测到大时间间隔，则瞬移到地面随机位置并进入 SLEEP —— //
        long now = System.currentTimeMillis();
        long gap = now - lastRealMs;
        lastRealMs = now;
        if (gap > RESUME_GAP_MS && state != State.SLEEP) {
            Rectangle wa = getWorkArea();
            int floorY = wa.y + wa.height - SIZE;
            int left = wa.x, right = wa.x + wa.width - SIZE;
            winX = left + rng.nextInt(Math.max(1, right - left + 1)); // 地面随机 X
            winY = floorY;
            setLocation(winX, winY);     // —— 瞬移 ——  // 【日志记录】
            state = State.SLEEP; frameIndex = 0;
            idleSleepPlanActive = false; // 退出任何计划
            logAction("ENTER_SLEEP_TELEPORT","gap="+gap); // 【日志记录】
        }

        // —— 鼠标全局空闲检测：超过 MOUSE_IDLE_MS，启动“睡前走角落计划” —— //
        if (state != State.SLEEP && state != State.WAKE
                && state != State.DRAG_WAKE && state != State.FALL_WAKE && state != State.LAND_WAKE) {
            long idleGap = now - lastMouseMoveMs;
            if (idleGap >= MOUSE_IDLE_MS) {
                startIdleSleepPlan();        // 开启计划（到地面→走角落→哈欠→睡）
                lastMouseMoveMs = now;       // 防抖重置
            }
        }

        switch (state) {
            // —— 睡眠：只显示 sleep 帧，不自动醒，直到用户点击/拖拽 —— //
            case SLEEP: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;
                winY = floorY; setLocation(winX, winY);
                if (tick % 6 == 0) frameIndex++;
                // 不自动离开 SLEEP
                break;
            }

            // —— 点击叫醒：播 wake 帧，时间到后转 IDLE —— //
            case WAKE: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;
                winY = floorY; setLocation(winX, winY);
                if (tick % 5 == 0) frameIndex++;
                if (--wakeShowTicks <= 0) { state = State.IDLE; frameIndex = 0; }
                break;
            }

            // —— 新增：睡前走角落 —— //
            case SLEEP_WALK_TO_CORNER: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;
                int left = wa.x, right = wa.x + wa.width - SIZE;

                // 保持在地面
                winY = floorY;

                // 行走到目标 X（不触发抓墙）
                int dx = idleTargetX - winX;
                if (dx != 0) {
                    int step = Math.min(CORNER_APPROACH_MAX_STEP, Math.abs(dx));
                    winX += (dx > 0 ? step : -step);
                    facingRight = dx > 0;
                }
                setLocation(winX, winY);

                if (tick % 4 == 0) frameIndex++;

                // 到达角落：进入打哈欠
                if (winX == left || winX == right || Math.abs(idleTargetX - winX) <= 0) {
                    state = State.YAWN;
                    frameIndex = 0;
                    yawnTicks = YAWN_MIN_TICKS;
                    logAction("ENTER_STATE","SLEEP_WALK_TO_CORNER->YAWN"); // 【日志记录】
                }
                break;
            }

            // —— 新增：打哈欠 —— //
            case YAWN: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;
                winY = floorY; setLocation(winX, winY);

                if (tick % YAWN_FRAME_STEP == 0) frameIndex++;
                if (--yawnTicks <= 0) {
                    state = State.SLEEP; frameIndex = 0;
                    idleSleepPlanActive = false;
                    logAction("ENTER_STATE","YAWN->SLEEP"); // 【日志记录】
                }
                break;
            }

            // —— 被拖醒阶段：拖拽中 —— //
            case DRAG_WAKE: {
                if (tick % 6 == 0) frameIndex++;
                break;
            }

            // —— 被拖醒阶段：掉落 —— //
            case FALL_WAKE: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;

                // 记录落地前一帧速度（用于反弹计算）
                preImpactYVel = yVel;

                yVel = Math.min(MAX_FALL_SPEED, yVel + GRAVITY);
                winY += yVel;

                if (winY >= floorY) {
                    setLocation(winX, floorY);
                    // 仅首次落地且速度足够时，给一次轻微反弹
                    if (!hasBounced && Math.abs(preImpactYVel) > BOUNCE_MIN_SPEED) {
                        yVel = -(int)Math.max(1, Math.round(Math.abs(preImpactYVel) * BOUNCE_RESTITUTION));
                        hasBounced = true; // 本次落地已反弹
                        // 保持在 FALL_WAKE，下一帧再处理（不切 LAND_WAKE）
                    } else {
                        yVel = 0;
                        hasBounced = false; // 重置，等待下一次“新的落地周期”
                        state = State.LAND_WAKE; frameIndex = 0;
                        landWakeTicks = LAND_WAKE_HOLD_TICKS;
                        logAction("ENTER_STATE","FALL_WAKE->LAND_WAKE"); // 【日志记录】
                        break;
                    }
                }

                int left = wa.x, right = wa.x + wa.width - SIZE;
                winX = Math.max(left, Math.min(winX, right));
                setLocation(winX, winY);

                if (tick % 3 == 0) frameIndex++;
                break;
            }

            // —— 被拖醒阶段：落地迷糊 —— //
            case LAND_WAKE: {
                if (tick % 5 == 0) frameIndex++;
                if (--landWakeTicks <= 0) {
                    state = State.IDLE; frameIndex = 0;
                    logAction("ENTER_STATE","LAND_WAKE->IDLE"); // 【日志记录】
                }
                break;
            }

            // ====== 以下保持原有状态机：地面/攀爬/天花板/过渡 ======
            case IDLE: {
                // —— 软下落：idle 姿态缓慢落地 —— //
                if (softDropActive) {
                    Rectangle wa = getWorkArea();
                    int floorY = wa.y + wa.height - SIZE;

                    if (winY < floorY) {
                        // 轻微“伪重力”：速度逐步增加，但不超过 8px/帧
                        int g = Math.max(1, GRAVITY / 2);
                        softDropVy = Math.min(2, softDropVy + g);
                        winY = Math.min(floorY, winY + softDropVy);
                        setLocation(winX, winY);
                    }

                    if (winY >= floorY) {
                        winY = floorY;
                        setLocation(winX, winY);
                        softDropActive = false;
                        softDropVy = 0;
                        yVel = 0; // 清理
                        // 可选：给 AI 一点冷却
                        // aiSuppressTicks = Math.max(aiSuppressTicks, 30);
                    }
                    break; // 正在软下落时，不执行后面的 IDLE 逻辑
                }

                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;
                if (winY < floorY && surfaceLatchTicks <= 0) { state = State.FALL; hasBounced=false; yVel = 0; frameIndex = 0; break; }
                int left = wa.x, right = wa.x + wa.width - SIZE;
                winX = Math.max(left, Math.min(winX, right));
                winY = floorY;
                setLocation(winX, winY);
                walkTarget = 0; walkVx = 0;
                if (tick % 8 == 0) frameIndex++;
                wallHangTicks = 0; ceilingHangTicks = 0;
                break;
            }

            case WALK:
            case WALK_TO_LEFT:
            case WALK_TO_RIGHT: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;
                if (winY < floorY - 1 && surfaceLatchTicks <= 0) { state = State.FALL; hasBounced=false; yVel = 0; frameIndex = 0; break; }

                int base = Math.max(1, WALK_BASE * Math.max(1, speed));
                if (state == State.WALK_TO_LEFT)  { facingRight = false; walkTarget = -base; }
                else if (state == State.WALK_TO_RIGHT) { facingRight = true;  walkTarget =  base; }
                else if (walkTarget == 0) { walkTarget = facingRight ? base : -base; }

                if (walkVx < walkTarget) walkVx = Math.min(walkVx + WALK_ACCEL, walkTarget);
                if (walkVx > walkTarget) walkVx = Math.max(walkVx - WALK_ACCEL, walkTarget);

                winX += walkVx;
                int left = wa.x, right = wa.x + wa.width - SIZE;

                if (winX <= left) {
                    winX = left; winY = floorY; setLocation(winX, winY);
                    state = State.GRAB_LEFT; frameIndex = 0; grabTicks = GRAB_MIN_TICKS;
                    surfaceLatchTicks = 20; yVel = 0;
                    logAction("WALK_EDGE_GRAB","LEFT"); // 【日志记录】
                    break;
                } else if (winX >= right) {
                    winX = right; winY = floorY; setLocation(winX, winY);
                    state = State.GRAB_RIGHT; frameIndex = 0; grabTicks = GRAB_MIN_TICKS;
                    surfaceLatchTicks = 20; yVel = 0;
                    logAction("WALK_EDGE_GRAB","RIGHT"); // 【日志记录】
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

                // 记录落地前一帧速度（用于反弹计算）
                preImpactYVel = yVel;

                yVel = Math.min(MAX_FALL_SPEED, yVel + GRAVITY);
                winY += yVel;

                if (winY >= floorY) {
                    setLocation(winX, floorY);
                    if (!hasBounced && Math.abs(preImpactYVel) > BOUNCE_MIN_SPEED) {
                        yVel = -(int)Math.max(1, Math.round(Math.abs(preImpactYVel) * BOUNCE_RESTITUTION));
                        hasBounced = true; // 已反弹
                        // 继续保持在 FALL，下一帧再处理
                    } else {
                        yVel = 0;
                        hasBounced = false;
                        state = State.LAND; landTicks = LAND_HOLD_TICKS; frameIndex = 0;
                        logAction("ENTER_STATE","FALL->LAND"); // 【日志记录】
                        break;
                    }
                }


                int left = wa.x, right = wa.x + wa.width - SIZE;
                winX = Math.max(left, Math.min(winX, right));
                setLocation(winX, winY);

                if (tick % 3 == 0) frameIndex++;
                break;
            }

            case LAND: {
                if (tick % 5 == 0) frameIndex++;
                if (--landTicks <= 0) {
                    // —— 新增：若是“空闲计划”触发的落地，衔接到“睡前走角落” —— //
                    if (afterLand == AfterLand.SLEEP_PLAN_WALK_TO_CORNER) {
                        afterLand = AfterLand.NONE;
                        state = State.SLEEP_WALK_TO_CORNER; frameIndex = 0;
                        logAction("AFTER_LAND","SLEEP_WALK_TO_CORNER"); // 【日志记录】
                    } else if (afterLand == AfterLand.WALK_TO_LEFT_CLIMB) {
                        state = State.WALK_TO_LEFT; frameIndex = 0;
                        afterLand = AfterLand.NONE;
                        logAction("AFTER_LAND","WALK_TO_LEFT_CLIMB"); // 【日志记录】
                    } else if (afterLand == AfterLand.WALK_TO_RIGHT_CLIMB) {
                        state = State.WALK_TO_RIGHT; frameIndex = 0;
                        afterLand = AfterLand.NONE;
                        logAction("AFTER_LAND","WALK_TO_RIGHT_CLIMB"); // 【日志记录】
                    } else {
                        state = State.IDLE; frameIndex = 0;
                        logAction("ENTER_STATE","LAND->IDLE"); // 【日志记录】
                    }
                }
                break;
            }

            // 地→墙：抓墙（只播 grab）
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
                    surfaceLatchTicks = 20; yVel = 0;
                    logAction("ENTER_STATE","GRAB_LEFT->CLIMB_LEFT"); // 【日志记录】
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
                    surfaceLatchTicks = 20; yVel = 0;
                    logAction("ENTER_STATE","GRAB_RIGHT->CLIMB_RIGHT"); // 【日志记录】
                }
                break;
            }

            // 顶→墙：荡到墙（只播 swing）
            case SWING_LEFT: {
                Rectangle wa = getWorkArea();
                int topY = wa.y;
                int targetX = wa.x;

                winY = topY;
                int dx = targetX - winX;
                int step = Math.max(1, Math.min((int)Math.ceil(Math.abs(dx) * 0.25), SWING_SLIDE));
                winX += (dx < 0 ? -step : (dx > 0 ? step : 0));
                setLocation(winX, winY);

                if (tick % SWING_FRAME_STEP == 0) frameIndex++;
                if (swingTicks > 0) swingTicks--;

                boolean atWall = (winX == targetX);
                boolean animDone = (frameIndex >= swingLeft.length);

                if ((swingTicks <= 0) && (atWall || animDone)) {
                    frameIndex = 0;
                    climbDirY = +1; state = State.CLIMB_LEFT;
                    surfaceLatchTicks = 20; yVel = 0;
                    logAction("ENTER_STATE","SWING_LEFT->CLIMB_LEFT"); // 【日志记录】
                }
                break;
            }
            case SWING_RIGHT: {
                Rectangle wa = getWorkArea();
                int topY = wa.y;
                int targetX = wa.x + wa.width - SIZE;

                winY = topY;
                int dx = targetX - winX;
                int step = Math.max(1, Math.min((int)Math.ceil(Math.abs(dx) * 0.25), SWING_SLIDE));
                winX += (dx < 0 ? -step : (dx > 0 ? step : 0));
                setLocation(winX, winY);

                if (tick % SWING_FRAME_STEP == 0) frameIndex++;
                if (swingTicks > 0) swingTicks--;

                boolean atWall = (winX == targetX);
                boolean animDone = (frameIndex >= swingRight.length);

                if ((swingTicks <= 0) && (atWall || animDone)) {
                    frameIndex = 0;
                    climbDirY = +1; state = State.CLIMB_RIGHT;
                    surfaceLatchTicks = 20; yVel = 0;
                    logAction("ENTER_STATE","SWING_RIGHT->CLIMB_RIGHT"); // 【日志记录】
                }
                break;
            }

            // 墙面攀爬
            case CLIMB_LEFT: {
                Rectangle wa = getWorkArea();
                int topY = wa.y;
                int floorY = wa.y + wa.height - SIZE;

                winX = wa.x; // 吸附
                if (winY < topY)   winY = topY;
                if (winY > floorY) winY = floorY;
                setLocation(winX, winY);

                int climbStep = Math.max(1, speed * CLIMB_BASE);
                if (wallPauseTicks > 0) wallPauseTicks--;
                else winY += (climbDirY > 0 ? climbStep : -climbStep);

                if (winY <= topY) {
                    winY = topY; setLocation(winX, winY);
                    state = State.CEILING_ATTACH_LEFT; frameIndex = 0;
                    attachTicks = CEIL_ATTACH_MIN_TICKS;
                    surfaceLatchTicks = 20; yVel = 0;
                    logAction("ENTER_STATE","CLIMB_LEFT->CEILING_ATTACH_LEFT"); // 【日志记录】
                } else if (winY >= floorY) {
                    winY = floorY; setLocation(winX, winY);
                    state = State.DISMOUNT_LEFT; frameIndex = 0; dismountTicks = DISMOUNT_MIN_TICKS;
                    logAction("ENTER_STATE","CLIMB_LEFT->DISMOUNT_LEFT"); // 【日志记录】
                } else {
                    if (wallPauseTicks > 0) wallHangTicks++; else wallHangTicks = 0;
                }

                if (tick % 5 == 0) frameIndex++;
                break;
            }
            case CLIMB_RIGHT: {
                Rectangle wa = getWorkArea();
                int topY = wa.y;
                int floorY = wa.y + wa.height - SIZE;

                winX = wa.x + wa.width - SIZE; // 吸附
                if (winY < topY)   winY = topY;
                if (winY > floorY) winY = floorY;
                setLocation(winX, winY);

                int climbStep = Math.max(1, speed * CLIMB_BASE);
                if (wallPauseTicks > 0) wallPauseTicks--;
                else winY += (climbDirY > 0 ? climbStep : -climbStep);

                if (winY <= topY) {
                    winY = topY; setLocation(winX, winY);
                    state = State.CEILING_ATTACH_RIGHT; frameIndex = 0;
                    attachTicks = CEIL_ATTACH_MIN_TICKS;
                    surfaceLatchTicks = 20; yVel = 0;
                    logAction("ENTER_STATE","CLIMB_RIGHT->CEILING_ATTACH_RIGHT"); // 【日志记录】
                } else if (winY >= floorY) {
                    winY = floorY; setLocation(winX, winY);
                    state = State.DISMOUNT_RIGHT; frameIndex = 0; dismountTicks = DISMOUNT_MIN_TICKS;
                    logAction("ENTER_STATE","CLIMB_RIGHT->DISMOUNT_RIGHT"); // 【日志记录】
                } else {
                    if (wallPauseTicks > 0) wallHangTicks++; else wallHangTicks = 0;
                }

                if (tick % 5 == 0) frameIndex++;
                break;
            }

            // 墙→顶：附顶过渡（只播 swing）
            case CEILING_ATTACH_LEFT: {
                Rectangle wa = getWorkArea();
                int topY = wa.y;
                winY = topY; setLocation(winX, winY);

                if (tick % CEIL_ATTACH_FRAME_STEP == 0) frameIndex++;
                if (--attachTicks <= 0) {
                    state = State.CEILING; frameIndex = 0;
                    facingRight = true;
                    surfaceLatchTicks = 20; yVel = 0;
                    logAction("ENTER_STATE","CEILING_ATTACH_LEFT->CEILING"); // 【日志记录】
                }
                break;
            }
            case CEILING_ATTACH_RIGHT: {
                Rectangle wa = getWorkArea();
                int topY = wa.y;
                winY = topY; setLocation(winX, winY);

                if (tick % CEIL_ATTACH_FRAME_STEP == 0) frameIndex++;
                if (--attachTicks <= 0) {
                    state = State.CEILING; frameIndex = 0;
                    facingRight = false;
                    surfaceLatchTicks = 20; yVel = 0;
                    logAction("ENTER_STATE","CEILING_ATTACH_RIGHT->CEILING"); // 【日志记录】
                }
                break;
            }

            // 墙→地：过渡（只播 grab）
            case DISMOUNT_LEFT: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;
                winX = wa.x; winY = floorY; setLocation(winX, winY);
                if (tick % DISMOUNT_FRAME_STEP == 0) frameIndex++;
                if (--dismountTicks <= 0) {
                    state = State.IDLE; frameIndex = 0;
                    logAction("ENTER_STATE","DISMOUNT_LEFT->IDLE"); // 【日志记录】
                }
                break;
            }
            case DISMOUNT_RIGHT: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;
                winX = wa.x + wa.width - SIZE; winY = floorY; setLocation(winX, winY);
                if (tick % DISMOUNT_FRAME_STEP == 0) frameIndex++;
                if (--dismountTicks <= 0) {
                    state = State.IDLE; frameIndex = 0;
                    logAction("ENTER_STATE","DISMOUNT_RIGHT->IDLE"); // 【日志记录】
                }
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
                        surfaceLatchTicks = 20; yVel = 0;
                        logAction("CEILING_EDGE_SWING","LEFT"); // 【日志记录】
                        break;
                    }
                    if (winX >= right) {
                        winX = right; setLocation(winX, winY);
                        state = State.SWING_RIGHT; frameIndex = 0; swingTicks = SWING_MIN_TICKS;
                        surfaceLatchTicks = 20; yVel = 0;
                        logAction("CEILING_EDGE_SWING","RIGHT"); // 【日志记录】
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
        if (bubbleVisible) positionBubble();
        canvas.repaint();
    }

    // 显示气泡
    private void showBubbleFor(RemindKind kind) {
        ImageIcon icon = (kind == RemindKind.STAND) ? standBubbleIcon : sitBubbleIcon;
        if (icon == null || icon.getIconWidth() <= 0) {
            // 没图则直接忽略（也可改为 SystemTray 提醒）
            return;
        }
        bubbleLabel.setIcon(icon);
        bubbleWin.pack();

        // 初始位置：统一由 positionBubble() 计算
        positionBubble();

        bubbleWin.setVisible(true);
        bubbleVisible = true;
    }

    // 关闭气泡并推进“站/坐”周期、重置计时
    private void hideBubbleAndAdvance() {
        // 幂等保护：已经不可见就别再切
        if (!bubbleVisible) return;

        // 防抖：250ms 内重复点击不响应（可按需调）
        long now = System.currentTimeMillis();
        if (now - lastBubbleCloseMs < 250) return;
        lastBubbleCloseMs = now;

        // 正常关闭与推进周期
        bubbleWin.setVisible(false);
        bubbleVisible = false;
        waitingClick = false;
        activeUseTicks = 0;

        // 只切换一次“下一次提醒”
        nextRemind = (nextRemind == RemindKind.STAND) ? RemindKind.SIT : RemindKind.STAND;
    }


    // ===== AI 调度（默认 ROAM）=====
    private void aiTick() {
        if (mode != Mode.ROAM) return;
        if (aiSuppressTicks > 0) { aiSuppressTicks--; return; }

        switch (state) {
            // 这些状态不打扰
            case SLEEP:
            case WAKE:
            case DRAG_WAKE:
            case FALL_WAKE:
            case LAND_WAKE:
            case SLEEP_WALK_TO_CORNER:  // 【重要】睡前走角落时不打扰
            case YAWN:                  // 【重要】打哈欠时不打扰
            case DRAG:
            case FALL:
            case LAND:
            case GRAB_LEFT:
            case GRAB_RIGHT:
            case SWING_LEFT:
            case SWING_RIGHT:
            case CEILING_ATTACH_LEFT:
            case CEILING_ATTACH_RIGHT:
            case DISMOUNT_LEFT:
            case DISMOUNT_RIGHT:
                return;

            case IDLE: {
                if (aiCooldown > 0) { aiCooldown--; return; }

                int r = rng.nextInt(100);
                if (r < 55) {
                    setWalk();
                    if (rng.nextBoolean()) setFacingRight(true); else setFacingRight(false);
                    aiActionTicks = rand(ROAM_WALK_MIN, ROAM_WALK_MAX);
                    logAction("AI_DECISION","WALK dir=" + (facingRight?"R":"L")); // 【日志记录】
                } else if (r < 75) {
                    setIdle();
                    aiActionTicks = rand(ROAM_IDLE_MIN, ROAM_IDLE_MAX);
                    logAction("AI_DECISION","IDLE"); // 【日志记录】
                } else if (r < 88) {
                    startClimbLeft();
                    logAction("AI_DECISION","CLIMB_LEFT"); // 【日志记录】
                } else {
                    startClimbRight();
                    logAction("AI_DECISION","CLIMB_RIGHT"); // 【日志记录】
                }
                aiCooldown = rand(ROAM_COOLDOWN_MIN, ROAM_COOLDOWN_MAX);
                return;
            }

            case WALK:
            case WALK_TO_LEFT:
            case WALK_TO_RIGHT: {
                if (aiActionTicks > 0) {
                    aiActionTicks--;
                } else {
                    walkTarget = 0;
                    if (Math.abs(walkVx) <= 0) {
                        setIdle();
                        aiCooldown = rand(ROAM_COOLDOWN_MIN, ROAM_COOLDOWN_MAX);
                        logAction("AI_DECISION","WALK->IDLE"); // 【日志记录】
                    }
                }
                return;
            }

            case CLIMB_LEFT:
            case CLIMB_RIGHT: {
                if (wallPauseTicks <= 0 && rng.nextInt(120) == 0) {
                    wallPauseTicks = rand(ROAM_PAUSE_MIN, ROAM_PAUSE_MAX);
                    logAction("AI_WALL","PAUSE " + wallPauseTicks + " ticks"); // 【日志记录】
                } else if (wallPauseTicks <= 0 && rng.nextInt(180) == 0) {
                    climbDirY = rng.nextBoolean() ? -1 : +1;
                    logAction("AI_WALL","FLIP_DIR " + (climbDirY>0?"DOWN":"UP")); // 【日志记录】
                }

                if (wallHangTicks > WALL_HANG_MIN && tick % 30 == 0 && rng.nextInt(5) == 0) {
                    state = State.FALL; hasBounced=false; yVel = 0; frameIndex = 0;
                    wallHangTicks = 0;
                    logAction("HANG_DROP","WALL"); // 【日志记录】
                }
                return;
            }

            case CEILING: {
                if (ceilingPauseTicks <= 0) {
                    if (rng.nextInt(150) == 0) {
                        ceilingPauseTicks = rand(ROAM_PAUSE_MIN, ROAM_PAUSE_MAX);
                        logAction("AI_TOP","PAUSE " + ceilingPauseTicks + " ticks"); // 【日志记录】
                    } else if (aiActionTicks <= 0) {
                        facingRight = rng.nextBoolean();
                        aiActionTicks = rand(ROAM_CLIMB_MIN, ROAM_CLIMB_MAX);
                        logAction("AI_TOP","FLIP_DIR " + (facingRight?"R":"L")); // 【日志记录】
                    } else {
                        aiActionTicks--;
                    }
                }

                if (ceilingHangTicks > CEILING_HANG_MIN && tick % 30 == 0 && rng.nextInt(5) == 0) {
                    state = State.FALL; hasBounced=false; yVel = 0; frameIndex = 0;
                    ceilingHangTicks = 0;
                    logAction("HANG_DROP","CEILING"); // 【日志记录】
                }
                return;
            }
        }
    }

    private int rand(int a, int b) { return a + rng.nextInt(b - a + 1); }

    // 当前帧组（严格只用对应目录）
    private ImageIcon[] getCurrentFrames() {
        switch (state) {
            case SLEEP:       return sleepFrames;
            case WAKE:        return wakeFrames;

            case DRAG_WAKE:   return facingRight ? dragWakeRight : dragWakeLeft;
            case FALL_WAKE:   return facingRight ? fallWakeRight : fallWakeLeft;
            case LAND_WAKE:   return facingRight ? landWakeRight : landWakeLeft;

            // 睡前走角落：用 walk 帧
            case SLEEP_WALK_TO_CORNER:
            case WALK:
            case WALK_TO_LEFT:
            case WALK_TO_RIGHT:
                              return facingRight ? walkRight   : walkLeft;

            // 打哈欠：只用 yawn
            case YAWN:        return facingRight ? yawnRight    : yawnLeft;

            case IDLE:        return facingRight ? idleRight   : idleLeft;
            case DRAG:        return facingRight ? dragRight   : dragLeft;
            case FALL:        return facingRight ? fallRight   : fallLeft;
            case LAND:        return facingRight ? landRight   : landLeft;
            case CLIMB_LEFT:  return climbLeft;
            case CLIMB_RIGHT: return climbRight;
            case CEILING:     return facingRight ? ceilingRight : ceilingLeft;

            // 只用 grab
            case GRAB_LEFT:   return grabLeft;
            case GRAB_RIGHT:  return grabRight;
            case DISMOUNT_LEFT:  return grabLeft;
            case DISMOUNT_RIGHT: return grabRight;

            // 只用 swing
            case SWING_LEFT:  return swingLeft;
            case SWING_RIGHT: return swingRight;
            case CEILING_ATTACH_LEFT:  return swingLeft;
            case CEILING_ATTACH_RIGHT: return swingRight;

            default:          return facingRight ? idleRight   : idleLeft;
        }
    }

    // 面板可调用
    public void startRunning() { if (!isVisible()) setVisible(true); if (!timer.isRunning()) timer.start(); }
    public void stopRunning()  { if (timer.isRunning()) timer.stop(); }
    public void setIdle()      { state = State.IDLE; frameIndex = 0; aiSuppressTicks = 60; logAction("ENTER_STATE","setIdle"); }
    public void setWalk()      {
        state = State.WALK; frameIndex = 0; aiSuppressTicks = 60;
        int base = Math.max(1, WALK_BASE * Math.max(1, speed));
        walkTarget = facingRight ? base : -base;
        logAction("ENTER_STATE","setWalk");
    }
    public void setFacingRight(boolean right) {
        facingRight = right; int v = Math.max(1, Math.abs(xVel)); xVel = right ? v : -v; aiSuppressTicks = 60;
        int base = Math.max(1, WALK_BASE * Math.max(1, speed));
        walkTarget = facingRight ? base : -base;
        logAction("FACE", right ? "RIGHT" : "LEFT");
    }
    public void setSpeed(int s) { speed = Math.max(1, Math.min(10, s)); logAction("SPEED", String.valueOf(s)); }
    public boolean isFacingRight() { return facingRight; }
    public State getPetState() { return state; }

    // 模式
    public void setModeRoam()   { mode = Mode.ROAM;  logAction("MODE","ROAM"); }
    public void setModeManual() { mode = Mode.MANUAL; aiSuppressTicks = 120; logAction("MODE","MANUAL"); }

    // —— 攀爬触发（先走到边缘，再抓墙 / 从顶则荡墙）——
    public void startClimbLeft()  {
        Rectangle wa = getWorkArea();
        if (state == State.CEILING) {
            state = State.SWING_LEFT;  frameIndex = 0; swingTicks = SWING_MIN_TICKS;
            surfaceLatchTicks = 20; yVel = 0;
        } else {
            int floorY = wa.y + wa.height - SIZE;
            if (winY < floorY && state != State.FALL) {
                state = State.FALL; hasBounced=false; yVel = 0; afterLand = AfterLand.WALK_TO_LEFT_CLIMB;
            } else {
                state = State.WALK_TO_LEFT; frameIndex = 0;
            }
        }
        aiSuppressTicks = 60;
        logAction("CMD","startClimbLeft");
    }
    public void startClimbRight() {
        Rectangle wa = getWorkArea();
        if (state == State.CEILING) {
            state = State.SWING_RIGHT; frameIndex = 0; swingTicks = SWING_MIN_TICKS;
            surfaceLatchTicks = 20; yVel = 0;
        } else {
            int floorY = wa.y + wa.height - SIZE;
            if (winY < floorY && state != State.FALL) {
                state = State.FALL; hasBounced=false; yVel = 0; afterLand = AfterLand.WALK_TO_RIGHT_CLIMB;
            } else {
                state = State.WALK_TO_RIGHT; frameIndex = 0;
            }
        }
        aiSuppressTicks = 60;
        logAction("CMD","startClimbRight");
    }
    public void startCeiling(boolean toRight) {
        Rectangle wa = getWorkArea();
        winY = wa.y;
        winX = Math.max(wa.x, Math.min(winX, wa.x + wa.width - SIZE));
        setLocation(winX, winY);
        state = State.CEILING; facingRight = toRight; frameIndex = 0;
        surfaceLatchTicks = 20; yVel = 0;
        aiSuppressTicks = 60;
        logAction("CMD", toRight ? "startCeiling RIGHT" : "startCeiling LEFT");
    }

    // —— 新增：启动“睡前走角落计划” —— //
    private void startIdleSleepPlan() {
        if (state == State.SLEEP || state == State.YAWN || state == State.SLEEP_WALK_TO_CORNER) return;
        Rectangle wa = getWorkArea();
        int floorY = wa.y + wa.height - SIZE;
        int left = wa.x, right = wa.x + wa.width - SIZE;

        idleCornerRight = rng.nextBoolean();
        idleTargetX = idleCornerRight ? right : left;
        idleSleepPlanActive = true;

        if (winY < floorY) {
            // 不在地面：先落地，落地后走角落
            state = State.FALL;
            hasBounced=false; 
            yVel = Math.max(yVel, 4);
            frameIndex = 0;
            afterLand = AfterLand.SLEEP_PLAN_WALK_TO_CORNER;
            logAction("IDLE_SLEEP_PLAN","FALL then walkToCorner " + (idleCornerRight?"RIGHT":"LEFT")); // 【日志记录】
        } else {
            // 已经在地面：直接走角落
            state = State.SLEEP_WALK_TO_CORNER;
            frameIndex = 0;
            logAction("IDLE_SLEEP_PLAN","walkToCorner " + (idleCornerRight?"RIGHT":"LEFT")); // 【日志记录】
        }
    }

    // 刷新素材
    public void reloadSprites() {
        logAction("RELOAD_SPRITES", ""); // 【日志记录】

        flushIcons(idleLeft);  flushIcons(idleRight);
        flushIcons(walkLeft);  flushIcons(walkRight);
        flushIcons(dragLeft);  flushIcons(dragRight);
        flushIcons(fallLeft);  flushIcons(fallRight);
        flushIcons(landLeft);  flushIcons(landRight);
        flushIcons(climbLeft); flushIcons(climbRight);
        flushIcons(ceilingLeft); flushIcons(ceilingRight);
        flushIcons(grabLeft);  flushIcons(grabRight);
        flushIcons(swingLeft); flushIcons(swingRight);
        flushIcons(sleepFrames); flushIcons(wakeFrames);
        flushIcons(dragWakeLeft); flushIcons(dragWakeRight);
        flushIcons(fallWakeLeft); flushIcons(fallWakeRight);
        flushIcons(landWakeLeft); flushIcons(landWakeRight);
        flushIcons(yawnLeft); flushIcons(yawnRight);

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
        sleepFrames  = loadIconsFromDir("sprites/sleep");
        wakeFrames   = loadIconsFromDir("sprites/wake");

        dragWakeLeft = loadIconsFromDir("sprites/drag_wake_left");
        dragWakeRight= loadIconsFromDir("sprites/drag_wake_right");
        fallWakeLeft = loadIconsFromDir("sprites/fall_wake_left");
        fallWakeRight= loadIconsFromDir("sprites/fall_wake_right");
        landWakeLeft = loadIconsFromDir("sprites/land_wake_left");
        landWakeRight= loadIconsFromDir("sprites/land_wake_right");

        yawnLeft     = loadIconsFromDir("sprites/yawn_left");
        yawnRight    = loadIconsFromDir("sprites/yawn_right");

        frameIndex = 0;
        tick = 0;
        canvas.repaint();
    }

    // 目录加载 PNG（按文件名排序），缩放到 SIZE×SIZE
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
            if (ic.getImage() != null) ic.getImage().flush();
        }
    }

    // 当前显示器工作区
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
    // ========== 全屏检测（JNA：User32 + GetForegroundWindow + GetWindowRect） ==========
    private boolean isForegroundFullscreen() {
        try {
            WinRect r = getForegroundRect();
            if (r == null) return false;

            GraphicsDevice gd = getGraphicsConfiguration() != null
                    ? getGraphicsConfiguration().getDevice()
                    : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();

            Rectangle sb = gd.getDefaultConfiguration().getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gd.getDefaultConfiguration());
            Rectangle work = new Rectangle(
                    sb.x + in.left, sb.y + in.top,
                    sb.width - in.left - in.right,
                    sb.height - in.top - in.bottom
            );
            // 允许2像素容差
            return Math.abs(r.x - work.x) <= 2 &&
                   Math.abs(r.y - work.y) <= 2 &&
                   Math.abs(r.w - work.width) <= 2 &&
                   Math.abs(r.h - work.height) <= 2;
        } catch (Throwable t) {
            return false;
        }
    }

    private WinRect getForegroundRect() {
        com.sun.jna.platform.win32.WinDef.HWND hwnd =
            com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow();
        if (hwnd == null) return null;

        com.sun.jna.platform.win32.WinDef.RECT rc =
            new com.sun.jna.platform.win32.WinDef.RECT();

        boolean ok = com.sun.jna.platform.win32.User32.INSTANCE.GetWindowRect(hwnd, rc);
        if (!ok) return null;

        WinRect wr = new WinRect();
        wr.x = rc.left; wr.y = rc.top;
        wr.w = rc.right - rc.left; wr.h = rc.bottom - rc.top;
        return wr;
    }


    // ========== 音频探测（尽力而为：捕获系统Loopback，如 Stereo Mix / What U Hear） ==========
    private void startAudioProbeThread() {
        new Thread(() -> {
            javax.sound.sampled.Mixer.Info[] infos = javax.sound.sampled.AudioSystem.getMixerInfo();
            javax.sound.sampled.TargetDataLine line = null;
            for (javax.sound.sampled.Mixer.Info mi : infos) {
                String name = mi.getName().toLowerCase();
                String desc = mi.getDescription().toLowerCase();
                if (name.contains("stereo") || name.contains("mix") || name.contains("loopback")
                        || desc.contains("stereo") || desc.contains("mix") || desc.contains("loopback")
                        || name.contains("what u hear") || desc.contains("what u hear")) {
                    try {
                        javax.sound.sampled.Mixer m = javax.sound.sampled.AudioSystem.getMixer(mi);
                        javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(44100, 16, 2, true, false);
                        javax.sound.sampled.DataLine.Info info = new javax.sound.sampled.DataLine.Info(javax.sound.sampled.TargetDataLine.class, fmt);
                        line = (javax.sound.sampled.TargetDataLine) m.getLine(info);
                        line.open(fmt, 44100); // 1秒缓冲
                        line.start();
                        audioProbeAvailable = true;
                        break;
                    } catch (Exception ignore) { line = null; }
                }
            }

            if (!audioProbeAvailable || line == null) {
                audioProbeAvailable = false;
                return; // 没有Loopback设备，降级
            }

            byte[] buf = new byte[4096];
            while (true) {
                try {
                    int n = line.read(buf, 0, buf.length);
                    if (n > 0) {
                        // 简单 RMS
                        long sum = 0;
                        int samples = n / 2; // 16-bit
                        for (int i = 0; i < n; i += 2) {
                            int lo = buf[i] & 0xff;
                            int hi = buf[i+1];
                            int v = (short)((hi << 8) | lo);
                            sum += (long)v * v;
                        }
                        double rms = Math.sqrt(sum / Math.max(1.0, samples));
                        // 归一化到 0..1（粗略）
                        audioLevelRms = Math.min(1.0, rms / 20000.0);
                    }
                } catch (Throwable t) {
                    audioProbeAvailable = false;
                    break;
                }
            }
        }, "AudioProbe").start();
    }

    // 让气泡跟随宠物，并保证在当前显示器工作区内；优先顺序：上→下→左→右；最后兜底夹取到工作区
    private void positionBubble() {
        if (!bubbleVisible) return;

        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc == null) {
            gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
        }
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        Rectangle work = new Rectangle(
                b.x + in.left, b.y + in.top,
                b.width - in.left - in.right,
                b.height - in.top - in.bottom
        );

        int bw = bubbleWin.getWidth();
        int bh = bubbleWin.getHeight();

        int petX = getX();
        int petY = getY();
        int pw   = getWidth();
        int ph   = getHeight();

        int centerX = petX + pw / 2;
        int centerY = petY + ph / 2;

        // 候选位置（上、下、左、右）
        Point top    = new Point(centerX - bw / 2 + BUBBLE_OFFSET_X,  petY - bh - BUBBLE_OFFSET_Y);
        Point bottom = new Point(centerX - bw / 2 + BUBBLE_OFFSET_X,  petY + ph + BUBBLE_OFFSET_Y);
        Point left   = new Point(petX - bw - Math.max(2, BUBBLE_OFFSET_X), centerY - bh / 2);
        Point right  = new Point(petX + pw + Math.max(2, BUBBLE_OFFSET_X), centerY - bh / 2);

        // 适配判断：是否完全放得下
        boolean topFits    = (top.y    >= work.y);
        boolean bottomFits = (bottom.y + bh <= work.y + work.height);
        boolean leftFits   = (left.x   >= work.x);
        boolean rightFits  = (right.x  + bw <= work.x + work.width);

        Point chosen;
        if (topFits) {
            chosen = top;
        } else if (bottomFits) {
            chosen = bottom;
        } else if (leftFits) {
            chosen = left;
        } else if (rightFits) {
            chosen = right;
        } else {
            // 全都不完全适配：选“上”作为基准，再夹取到工作区
            chosen = top;
        }

        // 夹取（兜底，避免出界）
        int bx = Math.max(work.x, Math.min(chosen.x, work.x + work.width  - bw));
        int by = Math.max(work.y, Math.min(chosen.y, work.y + work.height - bh));

        bubbleWin.setLocation(bx, by);
    }

    public void setReminderEnabled(boolean on) { this.reminderEnabled = on; }
    public void resetReminder() {
        this.activeUseTicks = 0;
        this.waitingClick = false;
        if (bubbleVisible) { bubbleWin.setVisible(false); bubbleVisible = false; }
    }
    public void setDetectFullscreen(boolean on) { this.detectFullscreen = on; }
    public void setDetectAudio(boolean on) { this.detectAudio = on; }
    public int  getElapsedActiveMinutes() { return activeUseTicks / (FPS * 60); }
    public String getNextRemindLabel() { return (nextRemind == RemindKind.STAND) ? "起来！" : "坐下！"; }
    public int getElapsedActiveSeconds() { return activeUseTicks / FPS; }
    public String getElapsedActiveHMS() {
        int s = getElapsedActiveSeconds();
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return String.format("%02d:%02d:%02d", h, m, sec);
    }
    public long getIdleGapSeconds() { return dbgIdleGapMs / 1000; }
    public boolean isUserActiveByInput() { return dbgUserActiveByInput; }
    public boolean isFullscreenActive() { return dbgFullscreen; }
    public boolean isAudioBusy() { return dbgAudioBusy; }
    public boolean isUserActive() { return dbgUserActive; }
    public boolean isAudioProbeAvailable() { return audioProbeAvailable; }
    public double getAudioLevelRms() { return audioLevelRms; }
    public int getReminderSecondsTotal() { return REMIND_MINUTES * 60; }
    public int getProgressPercent() {
        return Math.min(100, (int)Math.round(activeUseTicks * 100.0 / REMIND_TICKS));
    }
    private Rectangle getWorkAreaRect() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc == null) gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(b.x + in.left, b.y + in.top,
                b.width - in.left - in.right, b.height - in.top - in.bottom);
    }
    private boolean isOnGround() {
        Rectangle wa = getWorkAreaRect();
        int groundY = wa.y + wa.height - SIZE;
        return getY() >= groundY - 1; // 允许1px容差
    }

    // 低高度抬起后的回地面 idle
    private void trySetIdle() {
        state = State.IDLE;
        frameIndex = 0;
        yVel = 0;
    }

    // 落地后进入 LAND（播放落地动作）
    private void tryEnterLand() {
        state = State.LAND;
        landTicks = LAND_HOLD_TICKS;
        frameIndex = 0;
    }

}
