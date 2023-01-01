package com.scudata.server.http;

import com.scudata.app.common.IPAddressUtil;
import com.scudata.app.config.ConfigConsts;
import com.scudata.app.config.ConfigWriter;
import com.scudata.common.ArgumentTokenizer;
import com.scudata.common.Logger;
import com.scudata.common.Logger.FileHandler;
import com.scudata.common.MessageManager;
import com.scudata.common.StringUtils;
import com.scudata.parallel.UnitClient;
import com.scudata.parallel.UnitContext;
import com.scudata.parallel.XmlUtil;
import com.scudata.resources.ParallelMessage;
import com.scudata.server.unit.UnitServer;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Result;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;


/**
 * Http�������Ļ������ò�����
 *
 * @author Joancy
 */
public class HttpContext extends ConfigWriter {
    public static final String HTTP_CONFIG_FILE = "HttpServer.xml";
    public static String dfxHome;

    private String host = UnitContext.getDefaultHost();// "127.0.0.1";
    private int port = 8508;
    private int maxLinks = 50;
    private boolean autoStart = false;

    private ArrayList<String> sapPath = new ArrayList<String>();

    static MessageManager mm = ParallelMessage.get();

    /**
     * ���캯��
     *
     * @param showException �Ƿ񽫹����쳣��ӡ������̨���������
     */
    public HttpContext(boolean showException) {
        try {
            InputStream inputStream = UnitContext
                    .getUnitInputStream(HTTP_CONFIG_FILE);
            if (inputStream != null) {
                load(inputStream);
            }
        } catch (Exception x) {
            if (showException) {
                x.printStackTrace();
            }
        }
    }

    /**
     * ��ȡȱʡ�ķ���url��ַ
     *
     * @return url��ַ
     */
    public String getDefaultUrl() {
        String tmp = host;
        if (IPAddressUtil.isIPv6LiteralAddress(host)) {
            int percentIndex = host.indexOf('%');
            if (percentIndex > 0) {
                tmp = tmp.substring(0, percentIndex);
            }
            tmp = "[" + tmp + "]";
        }

        return "http://" + tmp + ":" + port;
    }

    /**
     * �������ļ������������ػ�������
     *
     * @param is �����ļ�������
     * @throws Exception ��ʽ����ʱ�׳��쳣
     */
    public void load(InputStream is) throws Exception {
        DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory
                .newInstance();
        DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
        Document xmlDocument = docBuilder.parse(is);
        NodeList nl = xmlDocument.getChildNodes();
        Node root = null;
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeName().equalsIgnoreCase("Server")) {
                root = n;
            }
        }
        if (root == null) {
            throw new Exception(mm.getMessage("UnitConfig.errorxml"));
        }

        // Server ����
        String buf = XmlUtil.getAttribute(root, "host");
        if (StringUtils.isValidString(buf)) {
            host = buf;
        }

        buf = XmlUtil.getAttribute(root, "port");
        if (StringUtils.isValidString(buf)) {
            port = Integer.parseInt(buf);
        }

        buf = XmlUtil.getAttribute(root, "autostart");
        if (StringUtils.isValidString(buf)) {
            autoStart = Boolean.parseBoolean(buf);
        }

        // �̶������־������̨�� �� start.home/nodes/[ip_port]/log Ŀ¼��
        String home = UnitServer.getHome();
        String file = "http/" + UnitClient.getHostPath(host) + "_" + port + "/log/log.txt";
        File f = new File(home, file);
        File fp = f.getParentFile();
        if (!fp.exists()) {
            fp.mkdirs();
        }
        String logFile = f.getAbsolutePath();
        FileHandler lfh = Logger.newFileHandler(logFile);
        Logger.addFileHandler(lfh);

        buf = XmlUtil.getAttribute(root, "parallelNum");
        if (StringUtils.isValidString(buf)) {
        }

        buf = XmlUtil.getAttribute(root, "maxlinks");
        if (StringUtils.isValidString(buf)) {
            maxLinks = Integer.parseInt(buf);
        }

        buf = XmlUtil.getAttribute(root, "sapPath");
        if (StringUtils.isValidString(buf)) {
            ArgumentTokenizer at = new ArgumentTokenizer(buf, ',');
            while (at.hasMoreTokens()) {
                sapPath.add(at.nextToken().trim());
            }
        }
    }

    public void save(OutputStream out) throws SAXException {
        Result resultxml = new StreamResult(out);
        handler.setResult(resultxml);
        level = 0;
        handler.startDocument();
        // ���ø��ڵ�Ͱ汾
        String paths = "";
        for (int i = 0; i < sapPath.size(); i++) {
            if (paths.length() > 0)
                paths += ",";
            paths += sapPath.get(i);
        }
        handler.startElement("", "", "Server", getAttributesImpl(new String[]{
                ConfigConsts.VERSION, "1", "host", host, "port", port + "", "autostart", autoStart + "",
                "maxlinks", maxLinks + "",  //parallelNum + "",
                "sapPath", paths}));

        handler.endElement("", "", "Server");
        // �ĵ�����,ͬ��������
        handler.endDocument();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setAutoStart(boolean as) {
        this.autoStart = as;
    }

//	public int getParallelNum() {
//		return parallelNum;
//	}
//
//	public void setParallelNum(int num) {
//		this.parallelNum = num;
//	}

    public int getMaxLinks() {
        return maxLinks;
    }

    public void setMaxLinks(int m) {
        this.maxLinks = m;
    }

    public ArrayList<String> getSapPath() {
        return sapPath;
    }

    public void setSapPath(ArrayList<String> paths) {
        sapPath = paths;
    }

    public String toString() {
        return host + ":" + port;
    }
}