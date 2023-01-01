package com.scudata.server.unit;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Map;

import com.esproc.jdbc.JDBCUtil;
import com.scudata.cellset.datamodel.PgmCellSet;
import com.scudata.dm.Context;
import com.scudata.dm.Sequence;
import com.scudata.parallel.Request;

/**
 * JDBCִ������
 * 
 * @author Joancy
 *
 */
public class JdbcTask {
	String cmd;
	ArrayList args;
	Context context;
	Map<String, Object> envParams;
	Thread execThread = null;

	/**
	 * ����һ��JDBC����
	 * @param cmd ����ִ�е�����
	 * @param args �����б�
	 * @param ctx ����������
	 * @param envParams ��������
	 */
	public JdbcTask(String cmd, ArrayList args, Context ctx,
			Map<String, Object> envParams) {
		this.cmd = cmd;
		this.args = (ArrayList) args;
		this.context = ctx;
		this.envParams = envParams;
	}

	/**
	 * ִ�е�ǰ����
	 * @return ������
	 * @throws Exception
	 */
	public Sequence execute() throws Exception {
		Object result = executeJDBC();
		if (result == null)
			return null;
		Sequence seq = new Sequence();
		if (result instanceof PgmCellSet) {
			PgmCellSet cs = (PgmCellSet) result;
			while (cs.hasNextResult()) {
				seq.add(cs.nextResult());
			}
		} else {
			seq.add(result);
		}
		return seq;
	}

	/**
	 * ȡ����ǰ����
	 * @return �ɹ�ȡ������true
	 * @throws Exception
	 */
	public boolean cancel() throws Exception {
		if (execThread != null) {
			try {
				execThread.stop();
			} catch (Throwable t1) {
			}

			//@deleted by canonical 2022-12-31
//			try {
//				execThread.destroy();
//			} catch (Throwable t1) {
//			}
			execThread = null;
		}
		return true;
	}

	/**
	 * ��ȡִ���������dfx����
	 * @return ����
	 */
	public String getCmd() {
		return cmd;
	}

	/**
	 * ��ȡ�����б�
	 * @return dfx����
	 */
	public ArrayList getArgs() {
		return args;
	}

	private Exception ex;
	private Boolean execFinished = false;
	private boolean isCanceled = false;
	private Object result;

	private Object executeJDBC() throws Exception {
		try {
			ex = null;
			result = null;
			execFinished = false;
			isCanceled = false;
			execThread = new Thread() {
				public void run() {
					try {
						result = JDBCUtil.execute(cmd, args, context);
					} catch (ThreadDeath td) {
						isCanceled = true;
					} catch (SQLException e) {
						ex = e;
					} catch (Exception e) {
						Throwable t = e;
						while (t != null) {
							if (t instanceof ThreadDeath) {
								ex = new InterruptedException();
								break;
							}
							t = t.getCause();
						}
						if (ex == null)
							ex = new SQLException(e.getMessage(), e);
					} finally {
						execFinished = true;
					}
				}
			};
			execThread.start();
			synchronized (execFinished) {
				while (!execFinished.booleanValue())
					try {
						Thread.sleep(5);
					} catch (ThreadDeath td) {
					} catch (InterruptedException e) {
					}
			}
			if (ex != null) {
				throw ex;
			}
		} catch (ThreadDeath td) {
			isCanceled = true;
		}
		if (isCanceled)
			throw new InterruptedException();
		return result;
	}
}
