package com.scudata.ide.spl.dialog;

import com.scudata.app.common.FtpTool;
import com.scudata.common.IntArrayList;
import com.scudata.common.MessageManager;
import com.scudata.ide.common.ConfigFile;
import com.scudata.ide.common.FTPInfo;
import com.scudata.ide.common.GM;
import com.scudata.ide.common.GV;
import com.scudata.ide.common.control.TableSelectName;
import com.scudata.ide.common.swing.VFlowLayout;
import com.scudata.ide.spl.resources.IdeSplMessage;
import org.apache.commons.net.ftp.FTPClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.Vector;

/**
 * 上传到FTP对话框
 */
public class DialogFTP extends JDialog implements ActionListener {
    static final Logger LOG = LoggerFactory.getLogger(DialogFTP.class);

    private static final long serialVersionUID = 1L;
    /**
     * 集算器语言资源
     */
    private MessageManager mm = IdeSplMessage.get();
    /**
     * 保存按钮
     */
    private JButton jBSave = new JButton();
    /**
     * 取消按钮
     */
    private JButton jBCancel = new JButton();
    /**
     * 管理按钮
     */
    private JButton jBManage = new JButton();

    /**
     * 主机表控件
     */
    private TableSelectName tableHost = new TableSelectName(
            mm.getMessage("dialoguploadresult.hostname")) {// 主机名
        private static final long serialVersionUID = 1L;

        public void doubleClick(int row, int col) {
            if (col == TableSelectName.COL_NAME)
                hostManager();
        }
    };

    /**
     * ASCII控件
     */
    private JRadioButton jRBAsc = new JRadioButton();
    /**
     * 二进制控件
     */
    private JRadioButton jRBBinary = new JRadioButton();

    /**
     * 退出选项
     */
    private int m_option = JOptionPane.CLOSED_OPTION;
    /**
     * 是否低版本JDK（1.7以下）
     */
    private boolean isLowVersionJDK = false;
    /**
     * FTP服务信息列表
     */
    private FTPInfo[] ftpInfos = null;
    /**
     * FTP服务客户端对象
     */
    private FTPClient ftpClient = null;
    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 构造函数
     */
    public DialogFTP() {
        super(GV.appFrame, "保存到FTP", true);
        try {

            String javaVersion = System.getProperty("java.version");
            if (javaVersion.compareTo("1.7") < 0) {
                isLowVersionJDK = true;
            }
            init();
            setSize(600, 300);
            resetText();
            loadFTPInfo();
            GM.setDialogDefaultButton(this, jBSave, jBCancel);
        } catch (Exception ex) {
            GM.showException(ex);
        }
    }

    /**
     * 设置文件路径
     *
     * @param filePath 文件路径
     */
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /**
     * 加载FTP服务信息
     */
    private void loadFTPInfo() {
        try {
            ftpInfos = ConfigFile.getConfigFile().loadFTP(ConfigFile.APP_DM);
            setFTPInfo();
        } catch (Throwable e) {
            ftpInfos = null;
        }
    }

    /**
     * 设置FTP服务信息
     */
    private void setFTPInfo() {
        if (ftpInfos != null && ftpInfos.length > 0) {
            Vector<String> hostNames = new Vector<String>();
            Vector<String> existNames = new Vector<String>();
            for (int i = 0; i < ftpInfos.length; i++) {
                hostNames.add(ftpInfos[i].getHost());
                if (ftpInfos[i].isSelected())
                    existNames.add(ftpInfos[i].getHost());
            }
            tableHost.setExistColor(false);
            tableHost.setExistNames(existNames);
            tableHost.setNames(hostNames, false, true);
        }
    }

    /**
     * 保存FTP服务信息
     */
    private void saveFTPInfo() {
        if (ftpInfos != null) {
            int[] indexes = tableHost.getSelectedIndexes();
            IntArrayList selectedIndexes = new IntArrayList();
            if (indexes != null)
                selectedIndexes.addAll(indexes);
            for (int i = 0; i < ftpInfos.length; i++) {
                ftpInfos[i].setSelected(selectedIndexes.containsInt(i));
            }
        }
        try {
            ConfigFile.getConfigFile().storeFTP(ConfigFile.APP_DM, ftpInfos);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 重设语言资源
     */
    private void resetText() {
        jBSave.setText(mm.getMessage("button.save"));
        jBCancel.setText(mm.getMessage("button.cancel"));
        setTitle(mm.getMessage("dialogftp.title")); // 保存到FTP
        jBManage.setText(mm.getMessage("dialogftp.manager")); // 主机管理(M)
        jRBBinary.setText(mm.getMessage("dialogftp.binary")); // 二进制
    }

    /**
     * 取退出选项
     *
     * @return
     */
    public int getOption() {
        return m_option;
    }

    /**
     * 保存
     */
    private void save() {
        if (ftpInfos == null)
            return;
        int[] indexes = tableHost.getSelectedIndexes();
        if (indexes == null || indexes.length == 0) {
            // 请选择要保存到的主机。
            JOptionPane.showMessageDialog(GV.appFrame,
                    mm.getMessage("dialogftp.savetohost"));
            return;
        }
        boolean[] successed = new boolean[indexes.length];
        String[] exceptions = new String[indexes.length];
        FTPInfo[] selectedFtps = new FTPInfo[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            FTPInfo ftpInfo = ftpInfos[indexes[i]];
            selectedFtps[i] = ftpInfo;
            String host = ftpInfo.getHost();
            int port = ftpInfo.getPort();
            try {
                String user = ftpInfo.getUser();
                String password = ftpInfo.getPassword();

                int fileType = -1;
                if (jRBAsc.isSelected()) {
                    fileType = FTPClient.ASCII_FILE_TYPE;
                } else if (jRBBinary.isSelected()) {
                    fileType = FTPClient.BINARY_FILE_TYPE;
                }

                FtpTool.instance().closeFtpClient(ftpClient);
                ftpClient = FtpTool.instance().newFtpClient(host, port, user, password);
                upload(fileType);
                successed[i] = true;
            } catch (Exception ex) {
                successed[i] = false;
                exceptions[i] = ex.getMessage();
            }
        }
        if (ftpClient != null) {
            FtpTool.instance().closeFtpClient(ftpClient);
        }

        DialogUploadResult dur = new DialogUploadResult();
        dur.setResult(selectedFtps, successed, exceptions);
        dur.setVisible(true);
    }

    /**
     * 上传文件
     *
     * @throws Exception
     */
    public void upload(int fileType) throws Exception {
        File file = new File(filePath);
        FtpTool.instance().uploadFile(ftpClient, new File(filePath), file.getName(), fileType);
    }

    /**
     * 初始化控件
     *
     * @throws Exception
     */
    private void init() throws Exception {
        JPanel panelEast = new JPanel(new VFlowLayout());
        jBSave.setMnemonic('S');
        jBSave.setText("保存(S)");
        jBSave.addActionListener(this);
        jBCancel.setMnemonic('C');
        jBCancel.setText("取消(C)");
        jBCancel.addActionListener(this);
        jBManage.setMnemonic('M');
        jBManage.setText("主机管理(M)"); // 主机管理(M)
        jBManage.addActionListener(this);
        JLabel labelOpt = new JLabel(mm.getMessage("dialogftp.sendtype")); // 发送类型：
        jRBAsc.setText("ASCII");
        jRBBinary.setText("二进制");
        ButtonGroup bgOpt = new ButtonGroup();
        bgOpt.add(jRBAsc);
        bgOpt.add(jRBBinary);
        jRBBinary.setSelected(true);
        this.getContentPane().add(panelEast, BorderLayout.EAST);
        panelEast.add(jBSave, null);
        panelEast.add(jBCancel, null);
        panelEast.add(new JPanel());
        panelEast.add(jBManage);
        JPanel panelCenter = new JPanel(new GridBagLayout());
        panelCenter.add(tableHost, GM.getGBC(1, 1, true, true));
        JPanel panelOpt = new JPanel(new GridLayout(1, 3));
        panelOpt.add(labelOpt);
        panelOpt.add(jRBAsc);
        panelOpt.add(jRBBinary);
        this.getContentPane().add(panelCenter, BorderLayout.CENTER);
        this.addWindowListener(new DialogFTP_this_windowAdapter(this));
    }

    /**
     * 窗口关闭事件
     *
     * @param e
     */
    void this_windowClosing(WindowEvent e) {
        saveFTPInfo();
        GM.setWindowDimension(this);
        dispose();
    }

    /**
     * 控件事件
     */
    public void actionPerformed(ActionEvent e) {
        Object src = e.getSource();
        if (jBSave.equals(src)) {
            save();
        } else if (jBCancel.equals(src)) {
            saveFTPInfo();
            GM.setWindowDimension(this);
            dispose();
        } else if (jBManage.equals(src)) {
            hostManager();
        }
    }

    /**
     * 主机管理对话框
     */
    private void hostManager() {
        DialogHostManager dhm = new DialogHostManager();
        dhm.setFTPInfo(ftpInfos);
        dhm.setVisible(true);
        if (dhm.getOption() == JOptionPane.OK_OPTION) {
            ftpInfos = dhm.getFTPInfo();
            setFTPInfo();
        }
    }

}

class DialogFTP_this_windowAdapter extends java.awt.event.WindowAdapter {
    DialogFTP adaptee;

    DialogFTP_this_windowAdapter(DialogFTP adaptee) {
        this.adaptee = adaptee;
    }

    public void windowClosing(WindowEvent e) {
        adaptee.this_windowClosing(e);
    }
}
