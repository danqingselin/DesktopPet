import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class DesktopPet extends JFrame {

    // 可按需调整
    public static final int SIZE = 128;      // 显示尺寸（宽高像素）
    private static final int TICK_MS = 33;   // 帧间隔 ~30FPS
    private static final int GRAVITY = 2;      // 重力（每帧增加的下落速度）
    private static final int MAX_FALL_SPEED = 24; // 最大下落速度
    private static final int LAND_HOLD_TICKS = 16; // 落地状态停留拍数（越大越久）

    // 动画状态
    public enum State { IDLE, WALK, DRAG, FALL, LAND }

    // 帧序列（左右各一组，全部必备）
    private ImageIcon[] idleLeft, idleRight;
    private ImageIcon[] walkLeft, walkRight;
    private ImageIcon[] dragLeft, dragRight;
    private ImageIcon[] fallLeft, fallRight;
    private ImageIcon[] landLeft, landRight;

    // 运行时
    private State state = State.IDLE;
    private boolean facingRight = true;
    private int xVel = 3;          // 水平速度：正右负左
    private int yVel = 0;          // 垂直速度：正下负上（重力用）
    private int speed = 2;         // 速度倍率 1..10
    private int frameIndex = 0;
    private int tick = 0;
    private int landTicks = 0;     // 落地剩余拍数

    // 位置与拖拽
    private int winX = 200, winY = 200;
    private int dragOffsetX = 0, dragOffsetY = 0;

    private final Timer timer;

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

    // ===== 构造器：初始化窗口、加载素材、启动计时器 =====
    public DesktopPet() {
        // 窗口外观与透明
        setUndecorated(true);
        setAlwaysOnTop(true);
        setBackground(new Color(0,0,0,0));
        setType(Type.UTILITY);
        canvas.setOpaque(false);
        canvas.setBackground(new Color(0,0,0,0));
        setContentPane(canvas);
        pack();

        // 初始位置：贴地
        //Rectangle wa0 = getWorkArea();
        //winX = Math.max(wa0.x, Math.min(winX, wa0.x + wa0.width - SIZE));
        //winY = wa0.y + wa0.height - SIZE;
        //setLocation(winX, winY);

        // 初始位置：工作区左上角，离边缘留出 margin 像素，从空中坠落
        Rectangle wa0 = getWorkArea();
        int margin = 48;                      // 想靠边一点就调小，想更居中就调大
        winX = wa0.x + margin;
        winY = wa0.y + margin;
        setLocation(winX, winY);

        // 启动即下落
        state = State.FALL;                   // 进入“下落”状态
        yVel  = 10;                            // 初始下落速度（可改 1~5，越大一开始掉得越快）
        frameIndex = 0;


        // 拖拽交互
        MouseAdapter ma = new MouseAdapter() {
            private State beforeDrag = State.IDLE;
            @Override public void mousePressed(MouseEvent e) {
                dragOffsetX = e.getX();
                dragOffsetY = e.getY();
                beforeDrag = state;
                state = State.DRAG;
                frameIndex = 0;
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

                // 松手：如果在空中 → 进入 FALL；如果已经在地面 → 回到 IDLE
                if (winY < floorY) {
                    state = State.FALL;
                    yVel = 10;            // yVel = 0; 从静止开始下落（可改成 >0 获得“抛下去”的效果）
                    frameIndex = 0;
                } else {
                    state = State.IDLE;
                    winY = floorY;
                    setLocation(winX, winY);
                }
            }
        };
        canvas.addMouseListener(ma);
        canvas.addMouseMotionListener(ma);

        // —— 素材一次性加载（全部必备，缺则抛异常）——
        idleLeft  = loadIconsFromDir("sprites/idle_left");
        idleRight = loadIconsFromDir("sprites/idle_right");
        walkLeft  = loadIconsFromDir("sprites/walk_left");
        walkRight = loadIconsFromDir("sprites/walk_right");
        dragLeft  = loadIconsFromDir("sprites/drag_left");
        dragRight = loadIconsFromDir("sprites/drag_right");
        fallLeft  = loadIconsFromDir("sprites/fall_left");
        fallRight = loadIconsFromDir("sprites/fall_right");
        landLeft  = loadIconsFromDir("sprites/land_left");
        landRight = loadIconsFromDir("sprites/land_right");

        // 计时器驱动
        timer = new Timer(TICK_MS, e -> onTick());
    }

    // ===== 每帧逻辑 =====
    private void onTick() {
        tick++;

        switch (state) {
            case IDLE: {
                // 始终贴地
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;
                
                // 如果工作区变化导致悬空，则触发落下
                if (winY < floorY) {
                    state = State.FALL;
                    yVel = 0;
                    frameIndex = 0;
                    break;
                }

                // 贴地与播帧
                if (winY != floorY) {
                    winY = floorY;
                    setLocation(winX, winY);
                }
                if (tick % 8 == 0) frameIndex++;

                // X 也限制在工作区内，防止跑出屏外
                int left = wa.x, right = wa.x + wa.width - SIZE;
                winX = Math.max(left, Math.min(winX, right));
                setLocation(winX, winY);

                if (tick % 8 == 0) frameIndex++;

                break;
            }



            case WALK: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;

                // 如果工作区变化导致悬空，先落下
                if (winY < floorY - 1) {
                    state = State.FALL;
                    yVel = 0;
                    frameIndex = 0;
                    break;
                }

                // 速度符号与朝向绑定（走左必播左帧）
                int step = Math.max(1, Math.abs(xVel) * Math.max(1, speed) / 2);
                int move = (xVel >= 0) ? step : -step;
                facingRight = (xVel >= 0);

                // X 移动并限位到工作区
                int left = wa.x;
                int right = wa.x + wa.width - SIZE;

                winX += move;
                if (winX <= left) {
                    winX = left;
                    xVel = Math.abs(xVel);
                    facingRight = true;
                } else if (winX >= right) {
                    winX = right;
                    xVel = -Math.abs(xVel);
                    facingRight = false;
                }

                // 贴地 & 移动
                winY = floorY;
                setLocation(winX, winY);

                if (tick % 4 == 0) frameIndex++;
                break;
            }

            case DRAG: {
                // 拖拽中不贴地，由鼠标控制 setLocation
                if (tick % 6 == 0) frameIndex++;
                break;
            }

            case FALL: {
                Rectangle wa = getWorkArea();
                int floorY = wa.y + wa.height - SIZE;

                // 重力下落
                yVel = Math.min(MAX_FALL_SPEED, yVel + GRAVITY);
                winY += yVel;

                // 落地判定
                if (winY >= floorY) {
                    winY = floorY;
                    yVel = 0;
                    setLocation(winX, winY);

                    // 进入 LAND 过渡
                    state = State.LAND;
                    landTicks = LAND_HOLD_TICKS;
                    frameIndex = 0;
                    break;
                }

                // X 仍保持在工作区内
                int left = wa.x, right = wa.x + wa.width - SIZE;
                winX = Math.max(left, Math.min(winX, right));
                setLocation(winX, winY);

                // 播放下落帧
                if (tick % 3 == 0) frameIndex++;
                break;
            }

            case LAND: {
                // 落地缓冲/过渡，播完几拍回 IDLE
                if (tick % 5 == 0) frameIndex++;
                if (--landTicks <= 0) {
                    state = State.IDLE;
                    frameIndex = 0;
                }
                break;
            }
        }

        canvas.repaint();//用户：麻烦解释一下这是什么
    }

    // 当前使用的帧组
    private ImageIcon[] getCurrentFrames() {
        switch (state) {
            case IDLE: return facingRight ? idleRight : idleLeft;
            case WALK: return facingRight ? walkRight : walkLeft;
            case DRAG: return facingRight ? dragRight : dragLeft;
            case FALL: return facingRight ? fallRight : fallLeft;
            case LAND: return facingRight ? landRight : landLeft;
            default:   return facingRight ? idleRight : idleLeft;
        }
    }

    // ===== 对外控制（面板调用） =====
    public void startRunning() { if (!isVisible()) setVisible(true); if (!timer.isRunning()) timer.start(); }
    public void stopRunning()  { if (timer.isRunning()) timer.stop(); }
    public void setIdle()      { state = State.IDLE; frameIndex = 0; }
    public void setWalk()      { state = State.WALK; frameIndex = 0; if (xVel == 0) xVel = facingRight ? 3 : -3; }
    public void setFacingRight(boolean right) {
        facingRight = right;
        int v = Math.max(1, Math.abs(xVel));
        xVel = right ? v : -v;
    }
    public void setSpeed(int s) { speed = Math.max(1, Math.min(10, s)); }
    public boolean isFacingRight() { return facingRight; }
    // 注意：不要命名为 getState()，会与 java.awt.Frame#getState() 冲突
    public State getPetState() { return state; }

    // ===== 刷新素材（重载 PNG） =====
    public void reloadSprites() {
        flushIcons(idleLeft);  flushIcons(idleRight);
        flushIcons(walkLeft);  flushIcons(walkRight);
        flushIcons(dragLeft);  flushIcons(dragRight);
        flushIcons(fallLeft);  flushIcons(fallRight);
        flushIcons(landLeft);  flushIcons(landRight);

        idleLeft  = loadIconsFromDir("sprites/idle_left");
        idleRight = loadIconsFromDir("sprites/idle_right");
        walkLeft  = loadIconsFromDir("sprites/walk_left");
        walkRight = loadIconsFromDir("sprites/walk_right");
        dragLeft  = loadIconsFromDir("sprites/drag_left");
        dragRight = loadIconsFromDir("sprites/drag_right");
        fallLeft  = loadIconsFromDir("sprites/fall_left");
        fallRight = loadIconsFromDir("sprites/fall_right");
        landLeft  = loadIconsFromDir("sprites/land_left");
        landRight = loadIconsFromDir("sprites/land_right");

        frameIndex = 0;
        tick = 0;
        canvas.repaint();
    }

    // ===== 工具：读取目录 PNG，按文件名排序并缩放到 SIZE×SIZE =====
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
            throw new RuntimeException("Load failed: " + dirPath + "（确认目录与 png 存在）", e);
        }
    }

    // 释放旧图（防缓存）
    private void flushIcons(ImageIcon[] arr) {
        if (arr == null) return;
        for (ImageIcon ic : arr) {
            if (ic != null && ic.getImage() != null) ic.getImage().flush();
        }
    }

    // ===== 关键：获取“工作区”（自动扣任务栏/停靠栏，适配当前显示器）=====
    private Rectangle getWorkArea() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc == null) {
            gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
        }
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(
                b.x + in.left,
                b.y + in.top,
                b.width - in.left - in.right,
                b.height - in.top - in.bottom
        );
    }
}
