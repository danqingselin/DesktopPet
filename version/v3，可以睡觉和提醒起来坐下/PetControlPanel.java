import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;

public class PetControlPanel extends JFrame {

    private DesktopPet pet; // 桌宠窗口

    // —— 健康提醒控件 —— //
    private JCheckBox remindCb  = new JCheckBox("启用健康提醒（30分钟）", true);
    private JCheckBox fullCb    = new JCheckBox("视频检测：全屏", true);
    private JCheckBox audioCb   = new JCheckBox("视频检测：音频", true);
    private JButton  resetBtn   = new JButton("重置计时");
    private JLabel   statusLbl  = new JLabel("活跃用时：0 分钟；下次提醒：起来！");
    private JLabel hmsLbl   = new JLabel("活跃用时：00:00:00");
    private JLabel detailLbl= new JLabel("—");

    // 其它常用控件
    private JButton startBtn = new JButton("启动宠物");
    private JButton stopBtn  = new JButton("停止宠物");
    private JButton reloadBtn = new JButton("刷新素材");
    private JCheckBox recCb  = new JCheckBox("记录动作日志", true);
    private JSlider speedSlider = new JSlider(1, 10, 3);
    private JButton idleBtn = new JButton("Idle");
    private JButton walkBtn = new JButton("Walk");
    private JButton faceLBtn = new JButton("面向左");
    private JButton faceRBtn = new JButton("面向右");
    private JButton climbLBtn = new JButton("攀左墙");
    private JButton climbRBtn = new JButton("攀右墙");
    private JButton ceilLBtn  = new JButton("到顶向左");
    private JButton ceilRBtn  = new JButton("到顶向右");
    private JRadioButton roamRb   = new JRadioButton("闲逛模式", true);
    private JRadioButton manualRb = new JRadioButton("手动模式", false);

    public PetControlPanel() {
        super("桌宠控制面板");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 520));
        setLocationByPlatform(true);

        // 根布局：中间控件区 + 底部健康条
        getContentPane().setLayout(new BorderLayout());

        //把 hmsLbl 字体加粗一点
        hmsLbl.setFont(hmsLbl.getFont().deriveFont(Font.BOLD, 14f));

        // ===== 中间控件区（滚动容器）=====
        JPanel controlsPanel = new JPanel();
        controlsPanel.setLayout(new BoxLayout(controlsPanel, BoxLayout.Y_AXIS));
        controlsPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // 行1：启动/停止/刷新素材/日志记录
        JPanel row1 = row();
        row1.add(startBtn);
        row1.add(stopBtn);
        row1.add(reloadBtn);
        row1.add(Box.createHorizontalStrut(10));
        row1.add(recCb);
        controlsPanel.add(row1);

        // 行2：模式切换
        JPanel row2 = row();
        ButtonGroup grp = new ButtonGroup();
        grp.add(roamRb); grp.add(manualRb);
        row2.add(new JLabel("模式："));
        row2.add(roamRb);
        row2.add(manualRb);
        controlsPanel.add(row2);

        // 行3：基础动作
        JPanel row3 = row();
        row3.add(idleBtn);
        row3.add(walkBtn);
        row3.add(faceLBtn);
        row3.add(faceRBtn);
        controlsPanel.add(row3);

        // 行4：攀爬/天花板
        JPanel row4 = row();
        row4.add(climbLBtn);
        row4.add(climbRBtn);
        row4.add(ceilLBtn);
        row4.add(ceilRBtn);
        controlsPanel.add(row4);

        // 行5：速度
        JPanel row5 = row();
        speedSlider.setPaintTicks(true);
        speedSlider.setMajorTickSpacing(1);
        speedSlider.setPaintLabels(true);
        row5.add(new JLabel("速度："));
        row5.add(speedSlider);
        controlsPanel.add(row5);

        // 加入滚动面板
        JScrollPane sp = new JScrollPane(controlsPanel);
        sp.setBorder(null);
        getContentPane().add(sp, BorderLayout.CENTER);

        // ===== 底部健康提醒条（两行）=====
        JPanel healthPanel = new JPanel(new GridLayout(2, 1));
        JPanel rowA = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel rowB = new JPanel(new FlowLayout(FlowLayout.LEFT));

        hmsLbl.setFont(hmsLbl.getFont().deriveFont(Font.BOLD, 14f));

        rowA.add(remindCb);
        rowA.add(Box.createHorizontalStrut(12));
        rowA.add(fullCb);
        rowA.add(Box.createHorizontalStrut(12));
        rowA.add(audioCb);
        rowA.add(Box.createHorizontalStrut(12));
        rowA.add(resetBtn);
        rowA.add(Box.createHorizontalStrut(16));
        rowA.add(new JLabel("下次提醒："));
        rowA.add(statusLbl);

        rowB.add(hmsLbl);
        rowB.add(Box.createHorizontalStrut(16));
        rowB.add(detailLbl);

        healthPanel.add(rowA);
        healthPanel.add(rowB);
        getContentPane().add(healthPanel, BorderLayout.SOUTH);

        // —— 每秒刷新显示 —— //
        new javax.swing.Timer(1000, e -> {
            if (pet != null) {
                statusLbl.setText(pet.getNextRemindLabel());          // 站起来 / 坐下
                hmsLbl.setText("活跃用时：" + pet.getElapsedActiveHMS()); // 00:00:00

                String details = String.format(
                    "最近输入: %ds | 全屏: %s | 音频: %s%s | 判定活跃: %s | 进度: %d%%",
                    pet.getIdleGapSeconds(),
                    pet.isFullscreenActive() ? "✓" : "✗",
                    pet.isAudioProbeAvailable() ? (pet.isAudioBusy() ? "✓" : "✗") : "—",
                    pet.isAudioProbeAvailable() ? String.format(" (rms=%.2f)", pet.getAudioLevelRms()) : "",
                    pet.isUserActive() ? "✓" : "✗",
                    pet.getProgressPercent()
                );
                detailLbl.setText(details);
            }
        }).start();



        // ===== 事件绑定 =====
        startBtn.addActionListener(e -> onStart());
        stopBtn.addActionListener(e -> { if (pet != null) pet.stopRunning(); });
        reloadBtn.addActionListener(e -> { if (ensurePet()) pet.reloadSprites(); });

        recCb.addActionListener(e -> { if (ensurePet()) pet.setRecordingEnabled(recCb.isSelected()); });

        roamRb.addActionListener(e -> { if (ensurePet()) pet.setModeRoam(); });
        manualRb.addActionListener(e -> { if (ensurePet()) pet.setModeManual(); });

        idleBtn.addActionListener(e -> { if (ensurePet()) pet.setIdle(); });
        walkBtn.addActionListener(e -> { if (ensurePet()) pet.setWalk(); });
        faceLBtn.addActionListener(e -> { if (ensurePet()) pet.setFacingRight(false); });
        faceRBtn.addActionListener(e -> { if (ensurePet()) pet.setFacingRight(true); });

        climbLBtn.addActionListener(e -> { if (ensurePet()) pet.startClimbLeft(); });
        climbRBtn.addActionListener(e -> { if (ensurePet()) pet.startClimbRight(); });
        ceilLBtn.addActionListener(e -> { if (ensurePet()) pet.startCeiling(false); });
        ceilRBtn.addActionListener(e -> { if (ensurePet()) pet.startCeiling(true); });

        speedSlider.addChangeListener(e -> {
            if (ensurePet() && !speedSlider.getValueIsAdjusting()) {
                pet.setSpeed(speedSlider.getValue());
            }
        });

        remindCb.addActionListener(e -> { if (ensurePet()) pet.setReminderEnabled(remindCb.isSelected()); });
        fullCb.addActionListener(e -> { if (ensurePet()) pet.setDetectFullscreen(fullCb.isSelected()); });
        audioCb.addActionListener(e -> { if (ensurePet()) pet.setDetectAudio(audioCb.isSelected()); });
        resetBtn.addActionListener(e -> { if (ensurePet()) pet.resetReminder(); });

        // 每秒刷新健康提醒状态文字
        new javax.swing.Timer(1000, e -> {
            if (pet != null) {
                // 行A：下次提醒
                statusLbl.setText(pet.getNextRemindLabel());

                // 行B：时分秒
                hmsLbl.setText("活跃用时：" + pet.getElapsedActiveHMS());

                // 行B：详情
                String details = String.format(
                    "最近输入: %ds | 全屏: %s | 音频: %s%s | 判定活跃: %s | 进度: %d%%",
                    pet.getIdleGapSeconds(),
                    pet.isFullscreenActive() ? "✓" : "✗",
                    pet.isAudioProbeAvailable() ? (pet.isAudioBusy() ? "✓" : "✗") : "—",
                    pet.isAudioProbeAvailable() ? String.format(" (rms=%.2f)", pet.getAudioLevelRms()) : "",
                    pet.isUserActive() ? "✓" : "✗",
                    pet.getProgressPercent()
                );
                detailLbl.setText(details);
            }
        }).start();


        pack();
    }

    private JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private boolean ensurePet() {
        if (pet == null) {
            pet = new DesktopPet();
            pet.setVisible(true);
            pet.startRunning();
            // 把面板上的初始状态同步到宠物
            pet.setRecordingEnabled(recCb.isSelected());
            pet.setReminderEnabled(remindCb.isSelected());
            pet.setDetectFullscreen(fullCb.isSelected());
            pet.setDetectAudio(audioCb.isSelected());
            pet.setSpeed(speedSlider.getValue());
        }
        return true;
        }

    private void onStart() {
        if (ensurePet()) {
            pet.startRunning();
            pet.setVisible(true);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PetControlPanel().setVisible(true));
    }
}
