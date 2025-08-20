import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

public class PetControlPanel extends JFrame {
    private DesktopPet pet;

    public PetControlPanel() {
        setTitle("桌宠控制面板");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(600, 380);
        setLocationRelativeTo(null);
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 12, 8, 12);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // 行0：启动/关闭
        JButton startBtn = new JButton("启动桌宠");
        JButton closeBtn = new JButton("关闭桌宠");
        gbc.gridx = 0; gbc.gridy = 0; add(startBtn, gbc);
        gbc.gridx = 1; gbc.gridy = 0; add(closeBtn, gbc);

        // 行1：状态
        JButton idleBtn = new JButton("待机");
        JButton walkBtn = new JButton("行走");
        gbc.gridx = 0; gbc.gridy = 1; add(idleBtn, gbc);
        gbc.gridx = 1; gbc.gridy = 1; add(walkBtn, gbc);

        // 行2：朝向
        JButton leftBtn = new JButton("朝左");
        JButton rightBtn = new JButton("朝右");
        gbc.gridx = 0; gbc.gridy = 2; add(leftBtn, gbc);
        gbc.gridx = 1; gbc.gridy = 2; add(rightBtn, gbc);

        // 行3：速度
        JPanel speedPanel = new JPanel(new BorderLayout(8, 8));
        JLabel speedLabel = new JLabel("速度：2");
        JSlider speedSlider = new JSlider(1, 10, 2);
        speedSlider.setMajorTickSpacing(1);
        speedSlider.setPaintTicks(true);
        speedPanel.add(speedLabel, BorderLayout.WEST);
        speedPanel.add(speedSlider, BorderLayout.CENTER);
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; add(speedPanel, gbc);
        gbc.gridwidth = 1;

        // 行4：攀爬
        JButton climbLeftBtn = new JButton("左侧攀");
        JButton climbRightBtn = new JButton("右侧攀");
        gbc.gridx = 0; gbc.gridy = 4; add(climbLeftBtn, gbc);
        gbc.gridx = 1; gbc.gridy = 4; add(climbRightBtn, gbc);

        // 行5：顶部攀爬左右
        JButton ceilingLBtn = new JButton("顶部向左");
        JButton ceilingRBtn = new JButton("顶部向右");
        gbc.gridx = 0; gbc.gridy = 5; add(ceilingLBtn, gbc);
        gbc.gridx = 1; gbc.gridy = 5; add(ceilingRBtn, gbc);

        // 行6：刷新
        JButton reloadBtn = new JButton("刷新素材（重载 PNG）");
        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 2; add(reloadBtn, gbc);
        gbc.gridwidth = 1;

        // 行7：记录动作（新增）
        JCheckBox recCb = new JCheckBox("记录动作到文件", true);
        JButton openLogBtn = new JButton("打开日志文件夹");
        gbc.gridx = 0; gbc.gridy = 7; add(recCb, gbc);
        gbc.gridx = 1; gbc.gridy = 7; add(openLogBtn, gbc);

        // 事件
        startBtn.addActionListener(this::onStart);
        closeBtn.addActionListener(e -> onClose());

        idleBtn.addActionListener(e -> { if (ensurePet()) pet.setIdle(); });
        walkBtn.addActionListener(e -> { if (ensurePet()) pet.setWalk(); });

        leftBtn.addActionListener(e -> { if (ensurePet()) pet.setFacingRight(false); });
        rightBtn.addActionListener(e -> { if (ensurePet()) pet.setFacingRight(true); });

        speedSlider.addChangeListener(e -> {
            int v = speedSlider.getValue();
            speedLabel.setText("速度：" + v);
            if (pet != null) pet.setSpeed(v);
        });

        climbLeftBtn.addActionListener(e -> { if (ensurePet()) pet.startClimbLeft(); });
        climbRightBtn.addActionListener(e -> { if (ensurePet()) pet.startClimbRight(); });
        ceilingLBtn.addActionListener(e -> { if (ensurePet()) pet.startCeiling(false); });
        ceilingRBtn.addActionListener(e -> { if (ensurePet()) pet.startCeiling(true); });

        reloadBtn.addActionListener(e -> { if (ensurePet()) pet.reloadSprites(); });

        // 新增：日志开关 & 打开目录
        recCb.addActionListener(e -> { if (ensurePet()) pet.setRecordingEnabled(recCb.isSelected()); });
        openLogBtn.addActionListener(e -> {
            try {
                java.awt.Desktop.getDesktop().open(new java.io.File("logs"));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "无法打开日志文件夹：logs\n" + ex.getMessage());
            }
        });
    }

    private void onStart(ActionEvent e) {
        if (pet == null) pet = new DesktopPet();
        pet.startRunning();
    }

    private void onClose() {
        if (pet != null) {
            pet.stopRunning();
            pet.setVisible(false);
            pet.dispose();
            pet = null;
        }
    }

    private boolean ensurePet() {
        if (pet == null) {
            JOptionPane.showMessageDialog(this, "请先点击“启动桌宠”");
            return false;
        }
        return true;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PetControlPanel().setVisible(true));
    }
}
