package com.scudata.app.common;

import org.apache.commons.io.IOUtils;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;


/**
 * FTP帮助类
 */
public class FtpTool {

    private static Logger logger = LoggerFactory.getLogger(FtpTool.class);

    private static FtpTool _instance = new FtpTool();

    public static FtpTool instance() {
        return _instance;
    }


    public FTPClient newFtpClient(String host, int port,
                                  String userName, String password) throws IOException {
        FTPClient ftp = new FTPClient();
        ftp.connect(host, port);
        ftp.login(userName, password);
        ftp.setConnectTimeout(50000);
        ftp.setControlEncoding("UTF-8");

        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
            logger.info("Ftp Connect Failed, userName or password mismatch.");
            ftp.disconnect();
        } else {
            logger.info("FTP Connect succeed");
        }

        return ftp;
    }

    public boolean closeFtpClient(FTPClient ftp) {
        if (ftp == null)
            return false;

        try {
            ftp.logout();
            return true;
        } catch (Exception e) {
            logger.error("FTP Close Failed");
        } finally {
            if (ftp.isConnected()) {
                try {
                    ftp.disconnect();
                } catch (IOException ioe) {
                    logger.error("FTP Close Failed");
                }
            }
        }

        return false;
    }


    public void download(FTPClient ftp, String remoteFile, File localFile) throws IOException {
        FileOutputStream out = null;
        try {
            String path = getFilePath(remoteFile);
            String fileName = StringUtils.getFilename(remoteFile);

            if (path.length() > 0)
                ftp.changeWorkingDirectory(path);
            ftp.enterLocalPassiveMode();

            out = new FileOutputStream(localFile);
            ftp.retrieveFile(fileName, out);
            out.flush();
        } finally {
            IOUtils.closeQuietly(out);
        }
    }

    private String getFilePath(String path) {
        int pos = path.lastIndexOf('/');
        if (pos < 0)
            return "";
        return path.substring(0, pos);
    }

    public boolean uploadFile(FTPClient ftp, File localFile, String remoteFile, int fileType) throws IOException {
        InputStream in = null;
        try {
            ftp.enterLocalPassiveMode();
            // 缺省使用二进制传输
            if (fileType < 0) {
                fileType = FTPClient.BINARY_FILE_TYPE;
            }
            ftp.setFileType(fileType);

            String ftpPath = getFilePath(remoteFile);
            if (!ftp.changeWorkingDirectory(ftpPath)) {
                ftp.makeDirectory(ftpPath);
            }

            ftp.changeWorkingDirectory(ftpPath);

            in = new FileInputStream(localFile);
            String fileName = StringUtils.getFilename(remoteFile);

            return ftp.storeFile(fileName, in);
        } finally {
            IOUtils.closeQuietly(in);
        }
    }
}
