package com.scudata.util;

import java.sql.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.scudata.common.DBConfig;
import com.scudata.common.DBInfo;
import com.scudata.common.DBSession;
import com.scudata.common.DBTypes;
import com.scudata.common.ISessionFactory;
import com.scudata.common.Logger;
import com.scudata.common.MessageManager;
import com.scudata.common.RQException;
import com.scudata.common.SQLTool;
import com.scudata.common.Sentence;
import com.scudata.dm.*;
import com.scudata.dm.Record;
import com.scudata.dm.cursor.ICursor;
import com.scudata.expression.Expression;
import com.scudata.resources.DataSetMessage;

public class DatabaseUtil {
	public static int TYPE_ORACLE_TIMESTAMP = 1;
	public static int TYPE_ORACLE_DATE = 2;
	public static int TYPE_SYBASE_TIMESTAMP = 3;

	// added by bdl, �洢�����У������ġ�ģʽ�����ֱ�Ϊ�����������������������������
	// �����α꣬��Ҫ���á�����������������ڷ��α������������������������������ֵ�����ָ�����ƵĲ���
	public static byte PROC_MODE_IN = (byte) 1;
	public static byte PROC_MODE_OUT = (byte) 2;
	public static byte PROC_MODE_INOUT = (byte) 3;

	private static Class<?> oracleTIMESTAMP = null;
	private static Class<?> oracleDATE = null;
	private static Class<?> sybaseTIMESTAMP = null;
	
	private static final byte Col_AutoIncrement = 0x01; // ���Զ���������, moved from DataStruct by bd, 2017.1.13

	/**
	 * ��ָ�������ݿ�����ִ��sql��䣬���ؽ�����ɵ�����
	 * @param sql	String sql���
	 * @param params	Object[] ����ֵ�б�
	 * @param types	byte[] ���������б��ɿգ��������Ͳμ�com.scudata.common.Types�������������ַ����������
	 * 						���������Ϳ�ʱ����Ϊ��ͬ�ڡ�Ĭ�ϡ����ͣ���ʱע�����ֵ����Ϊnull
	 * @param dbs	DBSession
	 * @return Sequence
	 */
	private static Table retrieve(String sql, Object[] params, byte[] types, DBSession dbs, String opt,
			int recordLimit) {
		ResultSet rs = null;
		Statement st = null;
		PreparedStatement pst = null;
		Connection con = null;
		String dbCharset = null;
		String toCharset = null;
		boolean tranSQL = false;
		boolean tranContent = true;
		int dbType = DBTypes.UNKNOWN;
		boolean isSolid = opt != null && opt.indexOf("s") > -1;
		try {
			DBConfig dsConfig = null;
			MessageManager mm = DataSetMessage.get();
			if (dbs != null && dbs.getInfo() instanceof DBConfig) {
				dsConfig = (DBConfig) dbs.getInfo();
			}
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}

			if (con == null || con.isClosed()) {
				String name = "";
				DBInfo info = dbs.getInfo();
				if (info != null) {
					name = info.getName();
				}
				throw new RQException(mm.getMessage("error.conClosed", name));
			}

			if (dsConfig != null) {
				dbCharset = dsConfig.getDBCharset();
				tranSQL = dsConfig.getNeedTranSentence();
				tranContent = dsConfig.getNeedTranContent();
				if ((tranContent || tranSQL) && dbCharset == null) {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.fromCharset", name));
				}

				toCharset = dsConfig.getClientCharset();
				if ((tranContent || tranSQL) && toCharset == null) {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.toCharset", name));
				}

				dbType = dsConfig.getDBType();
			} else {
				tranContent = false;
			}
			boolean bb = true;
			if (toCharset != null) {
				bb = toCharset.equalsIgnoreCase(dbCharset) || dbCharset == null;
			}

			if (tranSQL) {
				sql = new String(sql.getBytes(), dbCharset);
			}

			int paramCount = (params == null ? 0 : params.length);
			Object[] args = null;
			byte[] argTypes = null;
			if (paramCount > 0) {
				args = new Object[paramCount];
				argTypes = new byte[paramCount];
				int pos = 0;
				for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
					pos = Sentence.indexOf(sql, "?", pos + 1, Sentence.IGNORE_PARS + Sentence.IGNORE_QUOTE);
					args[paramIndex] = params[paramIndex];
					if (types == null || types.length <= paramIndex) {
						argTypes[paramIndex] = com.scudata.common.Types.DT_DEFAULT;
					} else {
						argTypes[paramIndex] = types[paramIndex];
					}
					if (args[paramIndex] == null) {
						continue;
					}
					if (args[paramIndex] instanceof Sequence && tranContent) {
						Sequence l = (Sequence) args[paramIndex];
						for (int i = 1, size = l.length(); i <= size; i++) {
							Object o = l.get(i);
							if (o instanceof String && tranSQL) {
								o = new String(((String) o).getBytes(), dbCharset);
								l.set(i, o);
							}
						}
					} else if (args[paramIndex] instanceof String && tranSQL) {
						args[paramIndex] = new String(((String) args[paramIndex]).getBytes(), dbCharset);
					}
					if (args[paramIndex] instanceof Sequence) {
						Object[] objs = ((Sequence) args[paramIndex]).toArray();
						int objCount = objs.length;
						StringBuffer sb = new StringBuffer(2 * objCount);
						for (int iObj = 0; iObj < objCount; iObj++) {
							sb.append("?,");
						}
						if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
							sb.deleteCharAt(sb.length() - 1);
						}
						if (sb.length() > 1) {
							sql = sql.substring(0, pos) + sb.toString() + sql.substring(pos + 1);
						}
						pos = pos + sb.length();
					}
				}
			}

			if (isSolid) {
				if (args != null && args.length > 0) {
					isSolid = false;
				}
			}

			try {
				if (isSolid) {
					st = con.createStatement();
				} else {
					pst = con.prepareStatement(sql);
				}
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
				}
			}

			if (args != null && args.length > 0) {
				int pos = 0;
				for (int iArg = 0; iArg < args.length; iArg++) {
					pos++;
					try {
						byte type = argTypes[iArg];
						if (args[iArg] != null && args[iArg] instanceof Sequence) {
							Object[] objs = ((Sequence) args[iArg]).toArray();
							for (int iObj = 0; iObj < objs.length; iObj++) {
								SQLTool.setObject(dbType, pst, pos, objs[iObj], type);
								pos++;
							}
							pos--;
						} else {
							SQLTool.setObject(dbType, pst, pos, args[iArg], type);
						}
					} catch (Exception e) {
						String name = "";
						DBInfo info = dbs.getInfo();
						if (info != null) {
							name = info.getName();
						}
						throw new RQException(mm.getMessage("error.argIndex", name, Integer.toString(iArg + 1)));
					}
				}
			}

			try {
				if (isSolid) {
					rs = st.executeQuery(sql);
				} else {
					rs = pst.executeQuery();
				}
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
				}
			}
			boolean addTable = false;
			if (opt != null && opt.indexOf("f") > -1) {
				addTable = true;
			}
			Table table = populate(rs, dbCharset, tranContent, toCharset, dbType, addTable, null, false, recordLimit,
					opt);
			if (opt != null && opt.indexOf("t") > -1) {
				String[] fields = null;
				ResultSetMetaData rsmd = rs.getMetaData();
				int colCount = rsmd.getColumnCount();
				String tableName = rsmd.getTableName(1);
				tableName = tranName(tableName, tranContent, dbCharset, toCharset, bb, opt);
				if (tableName == null || tableName.trim().length() < 1) {
					tableName = com.scudata.common.SQLParser.getClause(sql, com.scudata.common.SQLParser.KEY_FROM);
					tableName = removeTilde(tableName, dbs);
				}
				if (sql.indexOf(" as ") < 0) {
					fields = new String[colCount];
					String[][] tableCols = new String[colCount][];
					for (int c = 1; c <= colCount; ++c) {
						String colName = tranName(rsmd.getColumnLabel(c), tranContent, dbCharset, toCharset, bb, opt);
						fields[c - 1] = colName;
						String[] tCol = { colName };
						tableCols[c - 1] = tCol;
					}
				} else {
					String selCols = sql.substring(sql.indexOf("select") + 6, sql.indexOf("from")).trim();
					String[] cols = selCols.split(",");
					if (cols != null && cols.length > 0) {
						int length = cols.length;
						String[][] tableCols = new String[length][];
						fields = new String[length];
						for (int i = 0; i < length; i++) {
							String col = cols[i];
							if (col.indexOf(" as ") < 0) {
								fields[i] = col;
								String[] tCol = { col };
								tableCols[i] = tCol;
							} else {
								String[] sets = col.split(" ");
								fields[i] = sets[sets.length - 1];
								String[] tCol = { sets[0] };
								tableCols[i] = tCol;
							}

						}
					}
				}
				if (opt != null && opt.indexOf("u") > -1) {
					DatabaseMetaData dmd = con.getMetaData();
					try {
						rs = dmd.getPrimaryKeys(con.getCatalog(), null, tableName);
						int count = 0;
						ArrayList<String> nameList = new ArrayList<String>();
						while (rs.next()) {
							String keyName = rs.getString("COLUMN_NAME");
							if (keyName != null && keyName.trim().length() > 0) {
								nameList.add(keyName);
								count++;
							}
						}
						if (count > 0) {
							String[] pks = new String[count];
							for (int i = 0; i < count; i++) {
								pks[i] = (String) nameList.get(i);
							}
							table.setPrimary(pks);
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
			return table;
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (rs != null) {
					rs.close();
				}
				if (pst != null) {
					pst.close();
				}
				if (st != null) {
					st.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
	}

	/**
	 * ��ָ�������ݿ�����ִ��sql��䣬���ؽ�����еĵ�һ����¼
	 * 
	 * @param sql
	 *            String sql���
	 * @param params
	 *            Object[] ����ֵ�б�
	 * @param types
	 *            byte[] ���������б��ɿգ��������Ͳμ�com.scudata.common.Types�������������ַ����������
	 *            ���������Ϳ�ʱ����Ϊ��ͬ�ڡ�Ĭ�ϡ����ͣ���ʱע�����ֵ����Ϊnull
	 * @param dbs
	 *            DBSession
	 * @return Sequence
	 */
	private static Table retrieveOne(String sql, Object[] params, byte[] types, DBSession dbs, Context ctx,
			String opt) {
		ResultSet rs = null;
		PreparedStatement pst = null;
		Connection con = null;
		String dbCharset = null;
		String toCharset = null;
		boolean tranSQL = false;
		boolean tranContent = true;
		int dbType = DBTypes.UNKNOWN;
		try {
			DBConfig dsConfig = null;
			MessageManager mm = DataSetMessage.get();
			if (dbs != null && dbs.getInfo() instanceof DBConfig) {
				dsConfig = (DBConfig) dbs.getInfo();
			}
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}

			if (con == null || con.isClosed()) {
				String name = "";
				DBInfo info = dbs.getInfo();
				if (info != null) {
					name = info.getName();
				}
				throw new RQException(mm.getMessage("error.conClosed", name));
			}

			if (dsConfig != null) {
				dbCharset = dsConfig.getDBCharset();
				tranSQL = dsConfig.getNeedTranSentence();
				tranContent = dsConfig.getNeedTranContent();
				if ((tranContent || tranSQL) && dbCharset == null) {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.fromCharset", name));
				}

				toCharset = dsConfig.getClientCharset();
				if ((tranContent || tranSQL) && toCharset == null) {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.toCharset", name));
				}

				dbType = dsConfig.getDBType();
			} else {
				tranContent = false;
			}

			if (tranSQL) {
				sql = new String(sql.getBytes(), dbCharset);
			}

			int paramCount = (params == null ? 0 : params.length);
			Object[] args = null;
			byte[] argTypes = null;
			if (paramCount > 0) {
				args = new Object[paramCount];
				argTypes = new byte[paramCount];
				int pos = 0;
				for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
					pos = Sentence.indexOf(sql, "?", pos + 1, Sentence.IGNORE_PARS + Sentence.IGNORE_QUOTE);
					args[paramIndex] = params[paramIndex];
					if (types == null || types.length <= paramIndex) {
						argTypes[paramIndex] = com.scudata.common.Types.DT_DEFAULT;
					} else {
						argTypes[paramIndex] = types[paramIndex];
					}
					if (args[paramIndex] == null) {
						continue;
					}
					if (args[paramIndex] instanceof Sequence && tranContent) {
						Sequence l = (Sequence) args[paramIndex];
						for (int i = 1, size = l.length(); i <= size; i++) {
							Object o = l.get(i);
							if (o instanceof String && tranSQL) {
								o = new String(((String) o).getBytes(), dbCharset);
								l.set(i, o);
							}
						}
					} else if (args[paramIndex] instanceof String && tranSQL) {
						args[paramIndex] = new String(((String) args[paramIndex]).getBytes(), dbCharset);
					}
					if (args[paramIndex] instanceof Sequence) {
						Object[] objs = ((Sequence) args[paramIndex]).toArray();
						int objCount = objs.length;
						StringBuffer sb = new StringBuffer(2 * objCount);
						for (int iObj = 0; iObj < objCount; iObj++) {
							sb.append("?,");
						}
						if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
							sb.deleteCharAt(sb.length() - 1);
						}
						if (sb.length() > 1) {
							sql = sql.substring(0, pos) + sb.toString() + sql.substring(pos + 1);
						}
						pos = pos + sb.length();
					}
				}
			}

			try {
				pst = con.prepareStatement(sql);
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
				}
			}

			if (args != null && args.length > 0) {
				int pos = 0;
				for (int iArg = 0; iArg < args.length; iArg++) {
					pos++;
					try {
						byte type = argTypes[iArg];
						if (args[iArg] != null && args[iArg] instanceof Sequence) {
							Object[] objs = ((Sequence) args[iArg]).toArray();
							for (int iObj = 0; iObj < objs.length; iObj++) {
								SQLTool.setObject(dbType, pst, pos, objs[iObj], type);
								pos++;
							}
							pos--;
						} else {
							SQLTool.setObject(dbType, pst, pos, args[iArg], type);
						}
					} catch (Exception e) {
						String name = "";
						DBInfo info = dbs.getInfo();
						if (info != null) {
							name = info.getName();
						}
						throw new RQException(mm.getMessage("error.argIndex", name, Integer.toString(iArg + 1)));
					}
				}
			}

			try {
				rs = pst.executeQuery();
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
				}
			}

			return populateOne(rs, dbCharset, tranContent, toCharset, dbType, opt);
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (rs != null) {
					rs.close();
				}
				if (pst != null) {
					pst.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
	}

	/**
	 * ��ָ�������ݿ�����ִ��sql��䣬�����ؽ����
	 * @param sql	String sql���
	 * @param params	Object[] ����ֵ�б�
	 * @param types	byte[]
	 * @param dbs	DBSession ������ edited by bdl, 2012.9.21, ���@sѡ��趨֮��ʹ�ù̻�sql
	 * @param opt	String ѡ�� edited by bdl, 2012.9.18, ���ӷ���ֵ
	 * @return ���н�� ������䷵��Integer����ͨsql����Boolean
	 */
	private static Object runSQL(String sql, Object[] params, byte[] types, DBSession dbs, boolean isupdate,
			String opt) {
		PreparedStatement pst = null;
		Statement st = null;
		Connection con = null;
		String dbCharset = null;
		String toCharset = null;
		boolean tranSQL = false;
		boolean tranContent = true;
		boolean isSolid = opt != null && opt.indexOf("s") > -1;
		int dbType = DBTypes.UNKNOWN;
		try {
			DBConfig dsConfig = null;
			MessageManager mm = DataSetMessage.get();
			if (dbs != null && dbs.getInfo() instanceof DBConfig) {
				dsConfig = (DBConfig) dbs.getInfo();
			}
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}

			if (con == null || con.isClosed()) {
				String name = "";
				DBInfo info = dbs.getInfo();
				if (info != null) {
					name = info.getName();
				}
				throw new RQException(mm.getMessage("error.conClosed", name));
			}

			if (dsConfig != null) {
				dbCharset = dsConfig.getDBCharset();
				tranSQL = dsConfig.getNeedTranSentence();
				tranContent = dsConfig.getNeedTranContent();
				if ((tranContent || tranSQL) && dbCharset == null) {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.fromCharset", name));
				}

				toCharset = dsConfig.getClientCharset();
				if ((tranContent || tranSQL) && toCharset == null) {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.toCharset", name));
				}

				dbType = dsConfig.getDBType();
			} else {
				tranContent = false;
			}

			if (tranSQL) {
				sql = new String(sql.getBytes(), dbCharset);
			}

			int paramCount = (params == null ? 0 : params.length);
			Object[] args = null;
			byte[] argTypes = null;
			if (paramCount > 0) {
				args = new Object[paramCount];
				argTypes = new byte[paramCount];
				int pos = 0;
				for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
					pos = Sentence.indexOf(sql, "?", pos + 1, Sentence.IGNORE_PARS + Sentence.IGNORE_QUOTE);
					args[paramIndex] = params[paramIndex];
					if (types == null || types.length <= paramIndex) {
						argTypes[paramIndex] = com.scudata.common.Types.DT_DEFAULT;
					} else {
						argTypes[paramIndex] = types[paramIndex];
					}
					if (args[paramIndex] == null) {
						continue;
					}
					if (args[paramIndex] instanceof Sequence && tranContent) {
						Sequence l = (Sequence) args[paramIndex];
						for (int i = 1, size = l.length(); i <= size; i++) {
							Object o = l.get(i);
							if (o instanceof String && tranSQL) {
								o = new String(((String) o).getBytes(), dbCharset);
								l.set(i, o);
							}
						}
					} else if (args[paramIndex] instanceof String && tranSQL) {
						args[paramIndex] = new String(((String) args[paramIndex]).getBytes(), dbCharset);
					}
					if (args[paramIndex] instanceof Sequence) {
						Object[] objs = ((Sequence) args[paramIndex]).toArray();
						int objCount = objs.length;
						StringBuffer sb = new StringBuffer(2 * objCount);
						for (int iObj = 0; iObj < objCount; iObj++) {
							sb.append("?,");
						}
						if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
							sb.deleteCharAt(sb.length() - 1);
						}
						if (sb.length() > 1) {
							sql = sql.substring(0, pos) + sb.toString() + sql.substring(pos + 1);
						}
						pos = pos + sb.length();
					}
				}
			}

			if (isSolid) {
				if (args != null && args.length > 0) {
					isSolid = false;
				}
			}

			try {
				if (isSolid) {
					st = con.createStatement();
				} else {
					pst = con.prepareStatement(sql);
				}
			} catch (SQLException e) {
				//e.printStackTrace();
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
				}
			}

			if (args != null && args.length > 0) {
				int pos = 0;
				for (int iArg = 0; iArg < args.length; iArg++) {
					pos++;
					try {
						byte type = argTypes[iArg];
						if (args[iArg] != null && args[iArg] instanceof Sequence) {
							Object[] objs = ((Sequence) args[iArg]).toArray();
							for (int iObj = 0; iObj < objs.length; iObj++) {
								SQLTool.setObject(dbType, pst, pos, objs[iObj], type);
								pos++;
							}
							pos--;
						} else {
							SQLTool.setObject(dbType, pst, pos, args[iArg], type);
						}
					} catch (SQLException e) {
						if (dbs.getErrorMode()) {
							dbs.setError(e);
						} else {
							String name = "";
							DBInfo info = dbs.getInfo();
							if (info != null) {
								name = info.getName();
							}
							throw new RQException(mm.getMessage("error.argIndex", name, Integer.toString(iArg + 1)), e);
						}
					} catch (Exception e) {
						String name = "";
						DBInfo info = dbs.getInfo();
						if (info != null) {
							name = info.getName();
						}
						throw new RQException(mm.getMessage("error.argIndex", name, Integer.toString(iArg + 1)), e);
					}
				}
			}
			Object result = null;

			try {
				if (isupdate) {
					int number = 0;
					if (isSolid) {
						number = st.executeUpdate(sql);
					} else {
						number = pst.executeUpdate();
					}
					result = new Integer(number);
				} else {
					String begin = (sql == null || sql.length() < 6) ? "" : sql.substring(0, 6);
					if (begin.equalsIgnoreCase("insert") || begin.equalsIgnoreCase("update")
							|| begin.equalsIgnoreCase("delete")) {
						int number = 0;
						if (isSolid) {
							number = st.executeUpdate(sql);
						} else {
							number = pst.executeUpdate();
						}
						result = new Integer(number);
					} else {
						boolean success = false;
						if (isSolid) {
							success = st.execute(sql);
						} else {
							success = pst.execute();
						}
						result = Boolean.valueOf(success);
					}
				}
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
				}
			}
			return result;
		} catch (RQException re) {
			if (dbs.getErrorMode() && dbs.error() != null) {
				return null;
			}
			else {
				throw re;
			}
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (pst != null) {
					pst.close();
				}
				if (st != null) {
					st.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
	}

	/**
	 * added by bdl, 2008.11.17������������е����� ��ָ���Ĳ���ִ��ָ����PreparedStatement�������ؽ����
	 * @param pst	PreparedStatement
	 * @param params	Object[]	����
	 * @param types	byte[]	��������
	 * @param dbCharset	String	���ݿ��ַ���
	 * @param toCharset	String	�ն��ַ���
	 * @param tranSQL	boolean	SQL�Ƿ���ת��
	 * @param tranContent	boolean	�����ַ��Ƿ���ת��
	 * @param dbType	int ����ֵ
	 * @return �Ƿ�ɹ� Boolean
	 */
	private static Boolean runSQL2(PreparedStatement pst, Object[] params, byte[] types, String dbCharset,
			boolean tranSQL, int dbType, String dsName, DBSession dbs) {
		try {
			int paramCount = (params == null ? 0 : params.length);
			Object[] args = null;
			byte[] argTypes = null;
			if (paramCount > 0) {
				args = new Object[paramCount];
				argTypes = new byte[paramCount];
				for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
					args[paramIndex] = params[paramIndex];
					if (types == null || types.length <= paramIndex) {
						argTypes[paramIndex] = com.scudata.common.Types.DT_DEFAULT;
					} else {
						argTypes[paramIndex] = types[paramIndex];
					}
					if (args[paramIndex] == null) {
						continue;
					}
					if (args[paramIndex] instanceof String && tranSQL) {
						args[paramIndex] = new String(((String) args[paramIndex]).getBytes(), dbCharset);
					}
				}
			}
			if (args != null && args.length > 0) {
				int pos = 0;
				for (int iArg = 0; iArg < args.length; iArg++) {
					pos++;
					try {
						byte type = argTypes[iArg];
						SQLTool.setObject(dbType, pst, pos, args[iArg], type);
					} catch (SQLException e) {
						if (dbs.getErrorMode()) {
							dbs.setError(e);
						} else {
							MessageManager mm = DataSetMessage.get();
							throw new RQException(mm.getMessage("error.argIndex", dsName, Integer.toString(iArg + 1)), e);
						}
					} catch (Exception e) {
						MessageManager mm = DataSetMessage.get();
						throw new RQException(mm.getMessage("error.argIndex", dsName, Integer.toString(iArg + 1)), e);
					}
				}
			}
			Boolean result = Boolean.FALSE;

			try {
				boolean success = pst.execute();
				result = Boolean.valueOf(success);
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					MessageManager mm = DataSetMessage.get();
					throw new RQException(mm.getMessage("error.sqlException", dsName, "") + " : " + e.getMessage(), e);
				}
			}
			return result;
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		}
	}

	/**
	 * ����������е����� ��ָ���Ĳ���ִ��ָ����PreparedStatement�������ؽ����
	 * 
	 * @param pst
	 *            PreparedStatement
	 * @param params
	 *            Object[]
	 * @param types
	 *            byte[]
	 * @param dbCharset
	 *            String
	 * @param toCharset
	 *            String
	 * @param tranSQL
	 *            boolean
	 * @param tranContent
	 *            boolean
	 * @param dbType
	 *            int
	 */
	private static void addBatch(PreparedStatement pst, Object[] params, byte[] types, String dbCharset,
			boolean tranSQL, int dbType, String dsName, DBSession dbs) {
		try {
			int paramCount = (params == null ? 0 : params.length);
			Object[] args = null;
			byte[] argTypes = null;
			if (paramCount > 0) {
				args = new Object[paramCount];
				argTypes = new byte[paramCount];
				for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
					args[paramIndex] = params[paramIndex];
					if (types == null || types.length <= paramIndex) {
						argTypes[paramIndex] = com.scudata.common.Types.DT_DEFAULT;
					} else {
						argTypes[paramIndex] = types[paramIndex];
					}
					if (args[paramIndex] == null) {
						continue;
					}
					if (args[paramIndex] instanceof String && tranSQL) {
						args[paramIndex] = new String(((String) args[paramIndex]).getBytes(), dbCharset);
					}
				}
			}
			if (args != null && args.length > 0) {
				int pos = 0;
				for (int iArg = 0; iArg < args.length; iArg++) {
					pos++;
					try {
						byte type = argTypes[iArg];
						SQLTool.setObject(dbType, pst, pos, args[iArg], type);
					} catch (SQLException e) {
						if (dbs.getErrorMode()) {
							dbs.setError(e);
						} else {
							MessageManager mm = DataSetMessage.get();
							e.printStackTrace();
							throw new RQException(mm.getMessage("error.argIndex", dsName, Integer.toString(iArg + 1)), e);
						}
					} catch (Exception e) {
						MessageManager mm = DataSetMessage.get();
						e.printStackTrace();
						throw new RQException(mm.getMessage("error.argIndex", dsName, Integer.toString(iArg + 1)), e);
					}
				}
			}
			pst.addBatch();
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		}
	}

	/**
	 * ȡ��������ݼ�rs��ÿһ���������ɼ�¼���������з���
	 * 
	 * @param rs
	 *            ResultSet ������ݼ�
	 * @param dbCharset
	 *            String
	 * @param needTranContent
	 *            boolean
	 * @param toCharset
	 *            String
	 * @param dbType
	 *            int DBTypes�ж��������
	 * @param addTable
	 *            boolean �Ƿ����ֶ�������ӱ�����added by bdl, 2010.9.9
	 * @param recordLimit
	 *            added by bdl, 2012.2.27, ��������¼��
	 * @throws SQLException
	 * @throws UnsupportedEncodingException
	 * @return Sequence
	 */
	private static Table populate(ResultSet rs, String dbCharset, boolean needTranContent, String toCharset, int dbType,
			boolean addTable, Table table, boolean oneRecord, int recordLimit, String opt)
			throws SQLException, UnsupportedEncodingException {
		if (rs == null) {
			return null;
		}

		ResultSetMetaData rsmd = null;
		try {
			rsmd = rs.getMetaData();
		} catch (Exception e) {
		}
		if (rsmd == null) {
			return null;
		}

		int colCount = rsmd.getColumnCount();

		if (needTranContent && (toCharset == null || toCharset.trim().length() == 0)) {
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.toCharset"));
		}

		boolean bb = true;
		if (toCharset != null) {
			bb = toCharset.equalsIgnoreCase(dbCharset) || dbCharset == null;
		}

		if (table == null) {
			int[] colTypes = new int[colCount];
			String[] colNames = new String[colCount];

			for (int c = 1; c <= colCount; ++c) {
				try {
					if (addTable) {
						String tn = rsmd.getTableName(c);
						tn = tranName(tn, needTranContent, dbCharset, toCharset, bb, opt);
						if (tn == null) {
							tn = "";
						} else {
							tn += "_";
						}
						colNames[c - 1] = tn
								+ tranName(rsmd.getColumnLabel(c), needTranContent, dbCharset, toCharset, bb, opt);
					} else {
						colNames[c - 1] = tranName(rsmd.getColumnLabel(c), needTranContent, dbCharset, toCharset, bb, opt);
					}
					colTypes[c - 1] = rsmd.getColumnType(c);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			table = new Table(colNames);
		}
		if (recordLimit == 0) {
			return table;
		}
		boolean nolimit = recordLimit < 0;
		while (rs.next()) {
			Record record = table.newLast();
			for (int n = 1; n <= colCount; ++n) {
				int type = 0;
				if (dbType == DBTypes.ORACLE) {
					type = rsmd.getColumnType(n);
				}
				try {
					Object obj = tranData(type, dbType, rs, n, needTranContent, dbCharset, toCharset, bb, opt);
					record.set(n - 1, obj);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			if (!nolimit) {
				recordLimit--;
				if (recordLimit == 0) {
					return table;
				}
			}

			if (oneRecord) {
				return table;
			}
		}
		return table;
	}

	/**
	 * ȡ��������ݼ�rs��ÿһ���������ɼ�¼���������з��أ�����������queryGroup�У����ִ��sql���ص����ݽṹ��ͬ
	 * 
	 * @param rs
	 *            ResultSet ������ݼ�
	 * @param dbCharset
	 *            String
	 * @param needTranContent
	 *            boolean
	 * @param toCharset
	 *            String
	 * @param dbType
	 *            int DBTypes�ж��������
	 * @param ctx
	 *            Context ������
	 * @param ds
	 *            DataStruct ���ݽṹ����һ��ִ��ʱ����
	 * @throws SQLException
	 * @throws UnsupportedEncodingException
	 * @return Sequence
	 */
	private static Sequence populateGroup(ResultSet rs, String dbCharset, boolean needTranContent, String toCharset,
			int dbType, Table table, String opt) throws SQLException, UnsupportedEncodingException {
		if (rs == null) {
			return null;
		}

		if (needTranContent && (toCharset == null || toCharset.trim().length() == 0)) {
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.toCharset"));
		}
		boolean bb = true;
		if (toCharset != null) {
			bb = toCharset.equalsIgnoreCase(dbCharset) || dbCharset == null;
		}

		ResultSetMetaData rsmd = rs.getMetaData();
		int colCount = rsmd.getColumnCount();
		if (table == null) {
			int[] colTypes = new int[colCount];
			String[] colNames = new String[colCount];
			for (int c = 1; c <= colCount; ++c) {
				try {
					colNames[c - 1] = tranName(rsmd.getColumnLabel(c), needTranContent, dbCharset, toCharset, bb, opt);
				} catch (Exception e) {
					e.printStackTrace();
				}
				colTypes[c - 1] = rsmd.getColumnType(c);
			}
			table = new Table(colNames);
		}
		Sequence series = new Sequence();
		while (rs.next()) {
			Record record = table.newLast();
			series.add(record);
			for (int n = 1; n <= colCount; ++n) {
				int type = 0;
				if (dbType == DBTypes.ORACLE) {
					type = rsmd.getColumnType(n);
				}
				try {
					Object obj = tranData(type, dbType, rs, n, needTranContent, dbCharset, toCharset, bb, opt);
					record.set(n - 1, obj);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		return series;
	}

	/**
	 * ȡ��������ݼ�rs�ĵ�һ���������ɼ�¼����
	 * 
	 * @param rs
	 *            ResultSet ������ݼ�
	 * @param dbCharset
	 *            String
	 * @param needTranContent
	 *            boolean
	 * @param toCharset
	 *            String
	 * @param dbType
	 *            int DBTypes�ж��������
	 * @throws SQLException
	 * @throws UnsupportedEncodingException
	 * @return Sequence
	 */
	private static Table populateOne(ResultSet rs, String dbCharset, boolean needTranContent, String toCharset,
			int dbType, String opt) throws SQLException, UnsupportedEncodingException {
		if (rs == null) {
			return null;
		}
		if (needTranContent && (toCharset == null || toCharset.trim().length() == 0)) {
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.toCharset"));
		}

		boolean bb = true;
		if (toCharset != null) {
			bb = toCharset.equalsIgnoreCase(dbCharset) || dbCharset == null;
		}

		ResultSetMetaData rsmd = rs.getMetaData();

		int colCount = rsmd.getColumnCount();
		int[] colTypes = new int[colCount];
		String[] colNames = new String[colCount];

		try {
			for (int c = 1; c <= colCount; ++c) {
				colNames[c - 1] = tranName(rsmd.getColumnLabel(c), needTranContent, dbCharset, toCharset, bb, opt);
				colTypes[c - 1] = rsmd.getColumnType(c);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		Table table = new Table(colNames);
		if (rs.next()) {
			Record record = table.newLast();
			for (int n = 1; n <= colCount; ++n) {
				int type = 0;
				if (dbType == DBTypes.ORACLE) {
					type = rsmd.getColumnType(n);
				}
				try {
					Object obj = tranData(type, dbType, rs, n, needTranContent, dbCharset, toCharset, bb, opt);
					record.set(n - 1, obj);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			return table;
		}
		return null;
	}

	/**
	 * ��dbName��ִ��sql���
	 * @param sql	String sql���
	 * @param params	Object[] �����б��ɿ�
	 * @param types	byte[] ���������б��ɿգ��������Ͳμ�com.scudata.common.Types�������������ַ����������
	 *            			���������Ϳ�ʱ����Ϊ��ͬ�ڡ�Ĭ�ϡ����ͣ���ʱע�����ֵ����Ϊnull
	 * @param dbs	DBSession edited by bdl, 2012.9.18, ���ӷ���ֵ
	 * @return	���н�� ������䷵��Integer����ͨsql����Boolean
	 */
	public static Object execute(String sql, Object[] params, byte[] types, DBSession dbs, String opt) {
		return runSQL(sql, params, types, dbs, false, opt);
	}

	/**
	 * ��dbName��ִ��sql��䣬�ö������ִ��ͬһSQL��䣬Ϊ���Ч�ʣ���ִ��ʱ��ȥ�û�����е�?��������Ҫ������в���������
	 * @param sql	String sql���
	 * @param params	Object[][] �����б���б��ɿ�
	 * @param types	byte[] ���������б��ɿգ��������Ͳμ�com.scudata.common.Types�������������ַ����������
	 *            			���������Ϳ�ʱ����Ϊ��ͬ�ڡ�Ĭ�ϡ����ͣ���ʱע�����ֵ����Ϊnull
	 * @param dbs	DBSession
	 * @param interrupt	boolean �Ƿ��ж�
	 */
	public static void execute2old(String sql, Object[][] paramsGroup, byte[] types, DBSession dbs, boolean interrupt) {
		PreparedStatement pst = null;
		Connection con = null;
		String dbCharset = null;
		String toCharset = null;
		boolean tranSQL = false;
		boolean tranContent = true;
		int dbType = DBTypes.UNKNOWN;
		String name = "";
		DBInfo info = dbs.getInfo();
		if (info != null) {
			name = info.getName();
		}
		try {
			DBConfig dsConfig = null;
			MessageManager mm = DataSetMessage.get();
			if (dbs != null && dbs.getInfo() instanceof DBConfig) {
				dsConfig = (DBConfig) dbs.getInfo();
			}
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}

			if (con == null || con.isClosed()) {
				throw new RQException(mm.getMessage("error.conClosed", name));
			}

			if (dsConfig != null) {
				dbCharset = dsConfig.getDBCharset();
				tranSQL = dsConfig.getNeedTranSentence();
				tranContent = dsConfig.getNeedTranContent();
				if ((tranContent || tranSQL) && dbCharset == null) {
					throw new RQException(mm.getMessage("error.fromCharset", name));
				}

				toCharset = dsConfig.getClientCharset();
				if ((tranContent || tranSQL) && toCharset == null) {
					throw new RQException(mm.getMessage("error.toCharset", name));
				}

				dbType = dsConfig.getDBType();
			} else {
				tranContent = false;
			}

			if (tranSQL) {
				sql = new String(sql.getBytes(), dbCharset);
			}

			try {
				pst = con.prepareStatement(sql);
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
				}
			}
			int count = paramsGroup.length;
			for (int i = 0; i < count; i++) {
				Object[] params = paramsGroup[i];
				try {
					runSQL2(pst, params, types, dbCharset, tranSQL, dbType, name, dbs);
				} catch (Exception e) {
					if (interrupt) {
						throw e;
					}
					e.printStackTrace();
				}
			}
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (pst != null) {
					pst.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
	}

	/**
	 * ��dbName��ִ��sql��䣬�ö������ִ��ͬһSQL��䣬Ϊ���Ч�ʣ���ִ��ʱ��ȥ�û�����е�?��������Ҫ������в���������
	 * @param sql	String sql���
	 * @param params	Object[][] �����б���б��ɿ�
	 * @param types	byte[] ���������б��ɿգ��������Ͳμ�com.scudata.common.Types�������������ַ����������
	 *            			���������Ϳ�ʱ����Ϊ��ͬ�ڡ�Ĭ�ϡ����ͣ���ʱע�����ֵ����Ϊnull
	 * @param dbs	DBSession
	 * @param interrupt	boolean �Ƿ��ж�
	 */
	public static void execute2(String sql, Object[][] paramsGroup, byte[] types, DBSession dbs, boolean interrupt) {
		PreparedStatement pst = null;
		Connection con = null;
		String dbCharset = null;
		String toCharset = null;
		boolean tranSQL = false;
		boolean tranContent = true;
		int dbType = DBTypes.UNKNOWN;
		String name = "";
		DBInfo info = dbs.getInfo();
		if (info != null) {
			name = info.getName();
		}
		int batchSize = 1000;

		try {
			DBConfig dsConfig = null;
			MessageManager mm = DataSetMessage.get();
			if (dbs != null && dbs.getInfo() instanceof DBConfig) {
				dsConfig = (DBConfig) dbs.getInfo();
			}
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}

			if (con == null || con.isClosed()) {
				throw new RQException(mm.getMessage("error.conClosed", name));
			}

			if (dsConfig != null) {
				batchSize = dsConfig.getBatchSize();
				if (batchSize < 1) {
					batchSize = 1;
				}
				dbCharset = dsConfig.getDBCharset();
				tranSQL = dsConfig.getNeedTranSentence();
				tranContent = dsConfig.getNeedTranContent();
				if ((tranContent || tranSQL) && dbCharset == null) {
					throw new RQException(mm.getMessage("error.fromCharset", name));
				}

				toCharset = dsConfig.getClientCharset();
				if ((tranContent || tranSQL) && toCharset == null) {
					throw new RQException(mm.getMessage("error.toCharset", name));
				}

				dbType = dsConfig.getDBType();
			} else {
				tranContent = false;
			}

			if (tranSQL) {
				sql = new String(sql.getBytes(), dbCharset);
			}

			try {
				pst = con.prepareStatement(sql);
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
				}
			}
			int count = paramsGroup.length;
			int batch = 1;
			for (int i = 0; i < count; i++) {
				Object[] params = paramsGroup[i];
				if (batchSize <= 1) {
					try {
						runSQL2(pst, params, types, dbCharset, tranSQL, dbType, name, dbs);
					} catch (Exception e) {
						if (interrupt) {
							throw e;
						}
						e.printStackTrace();
					}
				} else if (batch >= batchSize || i == count - 1) {
					try {
						addBatch(pst, params, types, dbCharset, tranSQL, dbType, name, dbs);
						pst.executeBatch();
						pst.clearBatch();
					} catch (SQLException e) {
						if (dbs.getErrorMode()) {
							dbs.setError(e);
						} else {
							if (interrupt) {
								throw e;
							}
							e.printStackTrace();
						}
					} catch (Exception e) {
						if (interrupt) {
							throw e;
						}
						e.printStackTrace();
					}
					batch = 1;
				} else {
					try {
						addBatch(pst, params, types, dbCharset, tranSQL, dbType, name, dbs);
					} catch (Exception e) {
						if (interrupt) {
							throw e;
						}
						e.printStackTrace();
					}
					batch++;
				}
			}
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (pst != null) {
					pst.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
	}

	/**
	 * ��dbName��ִ��sql��䣬�ö������ִ��ͬһSQL��䣬Ϊ���Ч�ʣ� ��ִ��ʱ��ȥ�û�����е�?��������Ҫ������в���������
	 * ����һЩ���ݿ���Ϣ���ϲ��ã�ͬʱsqlҪ���Ѿ������������ֶ���ת��
	 * 
	 * @param sql
	 *            String sql���
	 * @param paramsGroup
	 *            Object[][] �����б���б��ɿ�
	 * @param types
	 *            byte[] ���������б��ɿգ��������Ͳμ�com.scudata.common.Types����������
	 *            ���ַ���������͡����������Ϳ�ʱ����Ϊ��ͬ�ڡ�Ĭ�ϡ����ͣ���ʱע�����ֵ����Ϊnull
	 * @param dbs
	 *            DBSession ���ݿ���Ϣ����¼����״̬��
	 * @param con
	 *            Connection ���ݿ����Ӷ���
	 * @param dbCharset
	 *            ���ݿ���룬���ڽ��ַ�������ת��
	 * @param tranSQL
	 *            �Ƿ���Ҫת�룬ֻ��Ϊtrueʱ����
	 * @param dbType
	 *            ���ݿ����ͣ�����ĳЩ���ݿ����趨����ʱ������Ҫ�������
	 * @param dbn
	 *            ���ݿ����ƣ����ڴ�����ʾ
	 * @param batchSize
	 *            int ��������ֵ�����ϲ���
	 * @param interrupt
	 *            �Ƿ��ڳ���ʱ�ж�
	 */
	private static void executeBatch(String sql, Object[][] paramsGroup, byte[] types, DBSession dbs, Connection con,
			String dbCharset, boolean tranSQL, int dbType, String dbn, int batchSize, boolean interrupt) {
		PreparedStatement pst = null;
		MessageManager mm = DataSetMessage.get();
		try {
			try {
				pst = con.prepareStatement(sql);
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					throw new RQException(mm.getMessage("error.sqlException", dbn, sql) + " : " + e.getMessage(), e);
				}
			}
			int count = paramsGroup.length;
			int batch = 1;
			for (int i = 0; i < count; i++) {
				Object[] params = paramsGroup[i];
				if (batchSize <= 1) {
					try {
						runSQL2(pst, params, types, dbCharset, tranSQL, dbType, dbn, dbs);
					} catch (Exception e) {
						if (interrupt) {
							throw e;
						}
						e.printStackTrace();
					}
				} else if (batch >= batchSize || i == count - 1) {
					try {
						addBatch(pst, params, types, dbCharset, tranSQL, dbType, dbn, dbs);
						pst.executeBatch();
						pst.clearBatch();
					} catch (SQLException e) {
						e.printStackTrace();
						if (dbs.getErrorMode()) {
							dbs.setError(e);
						} else {
							if (interrupt) {
								throw e;
							}
							e.printStackTrace();
						}
					} catch (Exception e) {
						if (interrupt) {
							throw e;
						}
						e.printStackTrace();
					}
					batch = 1;
				} else {
					try {
						addBatch(pst, params, types, dbCharset, tranSQL, dbType, dbn, dbs);
					} catch (Exception e) {
						if (interrupt) {
							throw e;
						}
						e.printStackTrace();
					}
					batch++;
				}
			}
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (pst != null) {
					pst.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
	}

	/**
	 * �ö������ִ��ͬһpst��Ϊ���Ч�ʣ���ִ��ʱ��ȥ�û�����е�?��������Ҫ������в��������� ��ɺ�ִ��һ��executeBatch��pst���ر�
	 * 
	 * @param pst
	 *            PreparedStatement pst
	 * @param params
	 *            Object[][] �����б���б��ɿ�
	 * @param types
	 *            byte[] ���������б��ɿգ��������Ͳμ�com.scudata.common.Types�������������ַ����������
	 *            ���������Ϳ�ʱ����Ϊ��ͬ�ڡ�Ĭ�ϡ����ͣ���ʱע�����ֵ����Ϊnull
	 * @param dbs
	 *            DBSession
	 * @param interrupt
	 *            boolean �Ƿ��ж�
	 */
	private static void executeBatch(PreparedStatement pst, Object[][] paramsGroup, byte[] types, DBSession dbs,
			String dbCharset, boolean tranSQL, int dbType, String name, boolean interrupt) {
		try {
			int count = paramsGroup.length;
			for (int i = 0; i < count; i++) {
				Object[] params = paramsGroup[i];
				try {
					addBatch(pst, params, types, dbCharset, tranSQL, dbType, name, dbs);
				} catch (Exception e) {
					if (interrupt) {
						throw e;
					}
					e.printStackTrace();
				}
			}
			try {
				// edited by bdl, 2014.7.8 �쳣����DBSession���趨��@kʱ���ܲ��׳�
				pst.executeBatch();
				pst.clearBatch();
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					if (interrupt) {
						throw e;
					}
					e.printStackTrace();
				}
			} catch (Exception e) {
				if (interrupt) {
					throw e;
				}
				e.printStackTrace();
			}
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		}
	}

	/**
	 * ��dbName��ִ��sql���
	 * @param sql	String sql���
	 * @param params	Object[] �����б��ɿ�
	 * @param types	byte[] ���������б��ɿգ��������Ͳμ�com.scudata.common.Types�������������ַ����������
	 *           			 ���������Ϳ�ʱ����Ϊ��ͬ�ڡ�Ĭ�ϡ����ͣ���ʱע�����ֵ����Ϊnull
	 * @param dbs	DBSession
	 * @return Sequence
	 */
	public static Sequence query(String sql, Object[] params, byte[] types, DBSession dbs, String opt) {
		// edited by bdl, 2015.7.28, ֧��@iѡ�����ʱ��������
		Table tbl = retrieve(sql, params, types, dbs, opt, -1);
		if (tbl != null && tbl.dataStruct().getFieldCount() == 1 && opt != null && opt.indexOf('i') != -1) {
			return tbl.fieldValues(0);
		} else {
			return tbl;
		}
	}

	/**
	 * ��dbName��ִ��sql��䣬�������
	 * @param sql	String sql���
	 * @param params	Object[] �����б� * @param types byte[] �������ͣ���ָ������Ϊ��ʱ������
	 * @param dbs	DBSession
	 * @param opt	String ѡ��
	 * @param ctx	Context �����Ļ���
	 * @return Table �����ý�������ɵ����
	 */
	public static Sequence query(String sql, Object[] params, byte[] types, DBSession dbs, String opt, Context ctx) {
		return query(sql, params, types, dbs, opt, -1, ctx);
	}

	/**
	 * added by bdl, 2012.2.27 ��dbName��ִ��sql��䣬�������
	 * @param sql	String sql���
	 * @param params	Object[] �����б�
	 * @param types	byte[] �������ͣ���ָ������Ϊ��ʱ������
	 * @param dbs	DBSession
	 * @param opt	String ѡ��
	 * @param recordLimit	int
	 * @param ctx	Context �����Ļ���
	 * @return Table	�����ý�������ɵ����
	 */
	public static Sequence query(String sql, Object[] params, byte[] types, DBSession dbs, String opt, int recordLimit,
			Context ctx) {
		// edited by bdl, 2015.7.28, ֧��@iѡ�����ʱ��������
		Table tbl = retrieve(sql, params, types, dbs, opt, recordLimit);
		if (tbl != null && tbl.dataStruct().getFieldCount() == 1 && opt != null && opt.indexOf('i') != -1) {
			return tbl.fieldValues(0);
		} else {
			return tbl;
		}
	}

	/**
	 * �Զ��������ִ��ͬһsql��䣬���ؽ�����е����У�added by bdl, 2009.1.8
	 * @param sql	String sql���
	 * @param params	Object[][] �����б����ɿգ���Ϊ���򷵻�null�������б��еĲ��������������飬�����п��ܻ����
	 * @param types	byte[] ���������б��ɿգ��������Ͳμ�com.scudata.common.Types�������������ַ����������
	 *            			���������Ϳ�ʱ����Ϊ��ͬ�ڡ�Ĭ�ϡ����ͣ���ʱע�����ֵ����Ϊnull
	 * @param dbs	DBSession
	 * @return Sequence
	 */
	public static Sequence queryGroup(String sql, Object[][] params, byte[] types, DBSession dbs, Context ctx,
			String opt) throws RQException {
		if (params == null || params.length < 1) {
			return query(sql, null, types, dbs, null);
		}
		PreparedStatement pst = null;
		Connection con = null;
		String dbCharset = null;
		String toCharset = null;
		boolean tranSQL = false;
		boolean tranContent = true;
		int dbType = DBTypes.UNKNOWN;
		try {
			DBConfig dsConfig = null;
			MessageManager mm = DataSetMessage.get();
			if (dbs != null && dbs.getInfo() instanceof DBConfig) {
				dsConfig = (DBConfig) dbs.getInfo();
			}
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}

			if (con == null || con.isClosed()) {
				String name = "";
				DBInfo info = dbs.getInfo();
				if (info != null) {
					name = info.getName();
				}
				throw new RQException(mm.getMessage("error.conClosed", name));
			}

			if (dsConfig != null) {
				dbCharset = dsConfig.getDBCharset();
				tranSQL = dsConfig.getNeedTranSentence();
				tranContent = dsConfig.getNeedTranContent();
				if ((tranContent || tranSQL) && dbCharset == null) {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.fromCharset", name));
				}

				toCharset = dsConfig.getClientCharset();
				if ((tranContent || tranSQL) && toCharset == null) {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.toCharset", name));
				}

				dbType = dsConfig.getDBType();
			} else {
				tranContent = false;
			}

			if (tranSQL) {
				sql = new String(sql.getBytes(), dbCharset);
			}

			Object[] modeParams = params[0];

			int paramCount = (modeParams == null ? 0 : modeParams.length);
			byte[] argTypes = null;
			if (paramCount > 0) {
				argTypes = new byte[paramCount];
				for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
					if (types == null || types.length <= paramIndex) {
						argTypes[paramIndex] = com.scudata.common.Types.DT_DEFAULT;
					} else {
						argTypes[paramIndex] = types[paramIndex];
					}
				}
			}

			try {
				pst = con.prepareStatement(sql);
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
				}
			}

			Sequence resultSeries = new Sequence();

			Object[] args = new Object[paramCount];
			Table table = null;
			for (int i = 0, iCount = params.length; i < iCount; i++) {
				Object[] thisParams = params[i];
				if (paramCount > 0) {
					for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
						args[paramIndex] = thisParams[paramIndex];
						if (args[paramIndex] == null) {
							continue;
						}
						if (args[paramIndex] instanceof String && tranSQL) {
							args[paramIndex] = new String(((String) args[paramIndex]).getBytes(), dbCharset);
						}
					}
				}
				if (args != null && args.length > 0) {
					for (int iArg = 0; iArg < args.length; iArg++) {
						try {
							byte type = argTypes[iArg];
							SQLTool.setObject(dbType, pst, iArg + 1, args[iArg], type);
						} catch (Exception e) {
							String name = "";
							DBInfo info = dbs.getInfo();
							if (info != null) {
								name = info.getName();
							}
							throw new RQException(mm.getMessage("error.argIndex", name, Integer.toString(iArg + 1)));
						}
					}
				}
				ResultSet rs = null;
				if (tranContent && (toCharset == null || toCharset.trim().length() == 0)) {
					throw new RQException(mm.getMessage("error.toCharset"));
				}
				boolean bb = true;
				if (toCharset != null) {
					bb = toCharset.equalsIgnoreCase(dbCharset) || dbCharset == null;
				}

				try {
					rs = pst.executeQuery();
					if (table == null) {
						ResultSetMetaData rsmd = rs.getMetaData();
						int colCount = rsmd.getColumnCount();
						int[] colTypes = new int[colCount];
						String[] colNames = new String[colCount];
						// added by bdl, 2012.10.18, �������ֶ�����ת��
						for (int c = 1; c <= colCount; ++c) {
							colNames[c - 1] = tranName(rsmd.getColumnLabel(c), tranContent, dbCharset, toCharset, bb, opt);
							colTypes[c - 1] = rsmd.getColumnType(c);
						}
						table = new Table(colNames);
					}
					resultSeries.add(populateGroup(rs, dbCharset, tranContent, toCharset, dbType, table, opt));
				} catch (SQLException e) {
					if (dbs.getErrorMode()) {
						dbs.setError(e);
					} else {
						String name = "";
						DBInfo info = dbs.getInfo();
						if (info != null) {
							name = info.getName();
						}
						throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
					}
				} finally {
					if (rs != null) {
						rs.close();
					}
				}
			}
			return resultSeries;
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (pst != null) {
					pst.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
	}

	/**
	 * ���ݶ��������Ҽ�¼�������з���
	 * @param tableName	String ��/��ͼ��
	 * @param fields	String[] ����ѯ���ֶ�
	 * @param keyNames	String[] ������
	 * @param keyValues	Object[][] ���¼������ֵ���ɵ�����
	 * @param dbs	DBSession
	 * @return Record
	 */
	public static Table query(String tableName, String[] fields, String[] keyNames, Object[][] keyValues, DBSession dbs,
			Context ctx) {
		String sql = "select ";

		int size = fields.length;
		String fieldAll = null;
		String field = null;
		for (int i = 0; i < size; i++) {
			field = addTilde(fields[i], dbs);
			if (field != null && field.trim().length() > 0) {
				if (fieldAll == null) {
					fieldAll = field;
				} else {
					fieldAll += ", " + field;
				}
			}
		}
		if (fieldAll == null || fieldAll.trim().length() < 1) {
			// throw new RQException("Field names is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidField"));
		}

		size = keyNames.length;
		int colCount = keyValues.length;
		String conditions = null;
		String key = "";
		Object v = null;

		ArrayList<Object> params = new ArrayList<Object>();

		for (int n = 0; n < colCount; n++) {
			Object[] rValues = keyValues[n];
			String condition = null;
			for (int i = 0; i < size; i++) {
				key = keyNames[i];
				if (key != null && key.trim().length() > 0) {
					v = null;
					if (i < rValues.length) {
						v = rValues[i];
					}
					if (condition == null) {
						condition = "(" + key + " = ?)";
						params.add(v);
					} else {
						condition += " and (" + key + " = ?)";
						params.add(v);
					}
				}
			}
			if (condition != null && condition.trim().length() > 0) {
				if (conditions == null) {
					conditions = "(" + condition + ")";
				} else {
					conditions += " or (" + condition + ")";
				}
			}
		}
		if (conditions == null || conditions.trim().length() < 1) {
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidCondition"));
			//throw new RQException("Conditions is Invalid!");
		}
		sql += fieldAll + " from " + addTilde(tableName, dbs) + " where " + conditions;
		return retrieve(sql, params.toArray(), null, dbs, null, -1);
	}

	/**
	 * �����������Ҽ�¼����ָ���ֶ�
	 * @param tableName	String
	 * @param fields	String[] ����ѯ���ֶ�
	 * @param keyNames	String[] ������
	 * @param keyValues	Object[]����ֵ
	 * @param dbs	DBSession
	 * @return Record
	 */
	public static Sequence query(String tableName, String[] fields, String[] keyNames, Object[] keyValues,
			DBSession dbs, Context ctx, String opt) {
		String sql = "select ";

		int size = fields.length;
		String fieldAll = null;
		String field = null;
		for (int i = 0; i < size; i++) {
			field = addTilde(fields[i], dbs);
			if (field != null && field.trim().length() > 0) {
				if (fieldAll == null) {
					fieldAll = field;
				} else {
					fieldAll += ", " + field;
				}
			}
		}
		if (fieldAll == null || fieldAll.trim().length() < 1) {
			//throw new RQException("Field names is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidField"));
		}

		size = keyNames.length;
		String key = "";
		Object v = null;
		String condition = null;
		ArrayList<Object> params = new ArrayList<Object>();
		for (int i = 0; i < size; i++) {
			key = keyNames[i];
			if (key != null && key.trim().length() > 0) {
				v = null;
				if (i < keyValues.length) {
					v = keyValues[i];
				}
				if (condition == null) {
					condition = "(" + key + " = ?)";
					params.add(v);
				} else {
					condition += " and (" + key + " = ?)";
					params.add(v);
				}
			}
		}
		if (condition == null || condition.trim().length() < 1) {
			//throw new RQException("Condition is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidCondition"));
		}
		sql += fieldAll + " from " + addTilde(tableName, dbs) + " where " + condition;
		Table tbl = retrieveOne(sql, params.toArray(), null, dbs, ctx, opt);
		if (tbl != null && tbl.dataStruct().getFieldCount() == 1 && opt != null && opt.indexOf('i') != -1) {
			return tbl.fieldValues(0);
		} else {
			return tbl;
		}
	}

	/**
	 * �����������Ҽ�¼���������ֶ�
	 * @param tableName	String ����
	 * @param keyNames	String[] ������
	 * @param keyValues	Object[]����ֵ
	 * @param dbs	DBSession
	 * @return Record
	 */
	public static Sequence query(String tableName, String[] keyNames, Object[] keyValues, DBSession dbs, Context ctx,
			String opt) {
		String[] fields = { "*" };
		return query(tableName, fields, keyNames, keyValues, dbs, ctx, opt);
	}

	/**
	 * ���ݶ����������Ҷ��¼���������ֶ�
	 * @param tableName	String ����
	 * @param keyNames	String[] ������
	 * @param keyValues	Object[][] ���¼������ֵ���ɵ�����
	 * @param dbs	DBSession
	 * @return Sequence
	 */
	public static Table query(String tableName, String[] keyNames, Object[][] keyValues, DBSession dbs, Context ctx) {
		String[] fields = { "*" };
		return query(tableName, fields, keyNames, keyValues, dbs, ctx);
	}

	/**
	 * ������������update��insert����������
	 * @param tableName	String ��/��ͼ��
	 * @param keyValues	Object[] ����ֵ
	 * @param fields	String[] Ҫ���µ��ֶ���
	 * @param values	Object[] Ҫ���µ��ֶ�ֵ
	 * @param dbs	DBSession
	 */
	public static void update(String tableName, Object[] keyValues, String[] fields, Object[] values, DBSession dbs) {
		update(tableName, keyValues, fields, values, null, dbs);
	}

	/**
	 * ������������update��insert����������
	 * @param tableName	String ��/��ͼ��
	 * @param keyValues	Object[] ����ֵ
	 * @param fields	String[] Ҫ���µ��ֶ���
	 * @param values	Object[] Ҫ���µ��ֶ�ֵ
	 * @param opt	String u: ֻ����UPDATE, i: ֻ����INSERT
	 * @param dbs	DBSession edited by bdl, 2012.9.18, ���ӷ���ֵ
	 * @return	���н�� ������䷵��Integer����ͨsql����Boolean
	 */
	public static Object update(String tableName, Object[] keyValues, String[] fields, Object[] values, String opt,
			DBSession dbs) {
		if (tableName == null || tableName.trim().length() < 1) {
			//throw new RQException("Table Name is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidTable"));
		}
		if (fields == null || fields.length < 1) {
			//throw new RQException("Field Names is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidField"));
		}
		if (values == null || values.length < 1) {
			//throw new RQException("Field Values is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidFieldValue"));
		}

		Connection con = null;
		String[] keyNames = null;
		try {
			MessageManager mm = DataSetMessage.get();
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}
			if (con == null || con.isClosed()) {
				String dbName = "";
				DBInfo info = dbs.getInfo();
				if (info != null) {
					dbName = info.getName();
				}
				throw new RQException(mm.getMessage("error.conClosed", dbName));
			}
			keyNames = getKeyNames(con, tableName);
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e);
		}
		if (keyNames == null) {
			return null;
		}

		if (keyNames == null || keyNames.length < 1) {
			//throw new RQException("Key Names is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidKey"));
		}
		if (keyValues == null || keyValues.length < 1) {
			//throw new RQException("Key Values is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidKeyValue"));
		}

		byte type = 0;
		if (opt != null) {
			if (opt.toLowerCase().indexOf('u') > -1) {
				type = 1;
			} else if (opt.toLowerCase().indexOf('i') > -1) {
				type = 2;
			}
		}

		int size = keyNames.length;
		String condition = null;
		String key = "";
		Object v = null;
		ArrayList<Object> params = new ArrayList<Object>();
		for (int i = 0; i < size; i++) {
			key = keyNames[i];
			if (key != null && key.trim().length() > 0) {
				v = null;
				if (i < keyValues.length) {
					v = keyValues[i];
				}
				if (condition == null) {
					condition = "(" + key + " = ?)";
					params.add(v);
				} else {
					condition += " and (" + key + " = ?)";
					params.add(v);
				}
			}
		}
		if (condition == null || condition.trim().length() < 1) {
			//throw new RQException("Condition is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidCondition"));
		}

		String sql = "";
		int n = 0;
		if (type == 0) {
			sql = "select count(*) from " + addTilde(tableName, dbs) + " where " + condition;
			Sequence se = retrieve(sql, params.toArray(), null, dbs, null, -1);
			n = ((Number) ((Record) se.get(1)).getFieldValue(0)).intValue();
			if (n > 0) {
				type = 1;
			} else {
				type = 2;
			}
		}
		ArrayList<Object> allParams = new ArrayList<Object>();
		if (type == 1) {
			size = fields.length;
			String sets = null;
			String field = "";
			Object fv = null;
			for (int i = 0; i < size; i++) {
				field = fields[i];
				if (field != null && field.trim().length() > 0) {
					fv = null;
					if (i < values.length) {
						fv = values[i];
					}
					if (sets == null) {
						sets = "" + field + " = ?";
						allParams.add(fv);
					} else {
						sets += ", " + field + " = ?";
						allParams.add(fv);
					}
				}
			}
			allParams.addAll(params);
			if (sets == null || sets.trim().length() < 1) {
				//throw new RQException("Field Names of Values is Invalid!");
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.invalidFieldValue"));
			}

			sql = "update " + addTilde(tableName, dbs) + " set " + sets + " where " + condition;
		} else {
			String fieldAll = null;
			String sets = null;
			String field = "";
			Object fv = null;
			size = keyNames.length;
			for (int i = 0; i < size; i++) {
				field = keyNames[i];
				if (field != null && field.trim().length() > 0) {
					fv = null;
					if (i < keyValues.length) {
						fv = keyValues[i];
					}
					if (sets == null) {
						fieldAll = "(" + field;
						sets = "( ?";
						allParams.add(fv);
					} else {
						fieldAll += "," + field;
						sets += ", ?";
						allParams.add(fv);
					}
				}
			}
			size = fields.length;
			for (int i = 0; i < size; i++) {
				field = fields[i];
				if (field == null) {
					continue;
				}
				boolean exist = false;
				for (int j = 0; j < keyNames.length; j++) {
					if (field.equalsIgnoreCase(keyNames[j])) {
						exist = true;
						break;
					}
				}
				if (exist) {
					continue;
				}
				if (field.trim().length() > 0) {
					fv = null;
					if (i < values.length) {
						fv = values[i];
					}
					if (sets == null) {
						fieldAll = "(" + field;
						sets = "( ?";
						allParams.add(fv);
					} else {
						fieldAll += "," + field;
						sets += ", ?";
						allParams.add(fv);
					}
				}
			}
			if (sets == null || sets.trim().length() < 1) {
				//throw new RQException("Field Values of Values is Invalid!");
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.invalidFieldValue"));
			} else {
				sets += " )";
			}
			if (fieldAll == null || fieldAll.trim().length() < 1) {
				//throw new RQException("Field Names of Values is Invalid!");
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.invalidFieldValue"));
			} else {
				fieldAll += " )";
			}
			sql = "insert into " + addTilde(tableName, dbs) + " " + fieldAll + " values " + sets;
		}
		if (dbs != null) {
			return runSQL(sql, allParams.toArray(), null, dbs, true, opt);
		} else {
			String dbName = "";
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.conClosed", dbName));
		}
	}

	/**
	 * ���������ת��
	 * @param type	��������
	 * @param dbType	���ݿ�����
	 * @param rs	�����
	 * @param index	�������
	 * @param needTranContent	�Ƿ���Ҫת���ַ�
	 * @param dbCharset	���ݿ��ַ���
	 * @param toCharset	�ն��ַ���
	 * @param bb	�ַ����Ƿ�һ��
	 * @return
	 * @throws Exception
	 */
	public static Object tranData(int type, int dbType, ResultSet rs, int index, boolean needTranContent,
			String dbCharset, String toCharset, boolean bb) throws Exception {
		return tranData(type, dbType, rs, index, needTranContent, dbCharset, toCharset, bb, null);
	}

	/**
	 * ���������ת��
	 * @param type	��������
	 * @param dbType	���ݿ�����
	 * @param rs	�����
	 * @param index	�������
	 * @param needTranContent	�Ƿ���Ҫת���ַ�
	 * @param dbCharset	���ݿ��ַ���
	 * @param toCharset	�ն��ַ���
	 * @param bb	�ַ����Ƿ�һ��
	 * @param opt	����ѡ��
	 * @return
	 * @throws Exception
	 */
	public static Object tranData(int type, int dbType, ResultSet rs, int index, boolean needTranContent,
			String dbCharset, String toCharset, boolean bb, String opt) throws Exception {
		if (dbType == DBTypes.ORACLE && oracleTIMESTAMP == null) {
			try {
				oracleTIMESTAMP = Class.forName("oracle.sql.TIMESTAMP");
				oracleDATE = Class.forName("oracle.sql.DATE");
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else if (dbType == DBTypes.SYBASE && sybaseTIMESTAMP == null) {
			try {
				sybaseTIMESTAMP = Class.forName("com.sybase.jdbc2.tds.SybTimestamp");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		Object obj = null;
		if (type == -1) {
			Reader rd = rs.getCharacterStream(index);
			obj = "";
			int c;
			StringBuffer sb = new StringBuffer();
			try {
				while ((c = rd.read()) != -1) {
					sb.append((char) c);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			obj = new String(sb.toString());
		} else if (type == java.sql.Types.DATE) {
			obj = rs.getTimestamp(index);
		} else {
			obj = rs.getObject(index);
		}

		if (obj == null)
			return null;
		
		if (obj instanceof Number) {
			if(obj instanceof java.math.BigDecimal){
				if (opt != null && opt.indexOf('d') > -1)
					return ((Number)obj).doubleValue();
			} else if (obj instanceof Integer | obj instanceof Long || obj instanceof Double){
				return obj;
			} else if (obj instanceof java.math.BigInteger) {
				java.math.BigInteger bi = (java.math.BigInteger) obj;
				return new java.math.BigDecimal(bi);
			} else if (obj instanceof Byte || obj instanceof Short) {
				return ((Number)obj).intValue();
			} else if (obj instanceof Float) {
				return ((Number)obj).doubleValue();
			}
		} else if (obj instanceof String && !bb) {
			try {
				if (needTranContent) {
					return new String(((String) obj).getBytes(dbCharset), toCharset);
				}
			} catch (Exception e) {
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.charset", dbCharset, toCharset));
			}
		} else if (obj instanceof Blob) {
			Blob blob = (Blob) obj;
			InputStream is = new BufferedInputStream(blob.getBinaryStream());
			try {
				ByteArrayOutputStream baos = new ByteArrayOutputStream(8096);
				byte[] buf = new byte[8096];
				while (true) {
					int c1 = is.read(buf);
					if (c1 < 0) {
						break;
					}
					baos.write(buf, 0, c1);
				}
				return baos.toByteArray();
			} catch (Exception e) {
				if (is != null) {
					try {
						is.close();
					} catch (Exception e1) {
					}
				}
			}
		} else if (obj instanceof Clob) {
			Clob clob = (Clob) obj;
			StringBuffer sb = new StringBuffer((int) clob.length());
			BufferedReader br = new BufferedReader(clob.getCharacterStream());
			try {
				char[] buf = new char[8096];
				while (true) {
					int c1 = br.read(buf);
					if (c1 < 0) {
						break;
					}
					sb.append(buf, 0, c1);
				}
				return sb.toString();
			} catch (Exception e) {
				if (br != null) {
					try {
						br.close();
					} catch (Exception el) {
					}
				}
			}
		} else if (dbType == DBTypes.ORACLE && oracleTIMESTAMP != null && oracleTIMESTAMP.isInstance(obj)) {
			return TranOracle.tran(TYPE_ORACLE_TIMESTAMP, obj);
		} else if (dbType == DBTypes.ORACLE && oracleDATE != null && oracleDATE.isInstance(obj)) {
			return TranOracle.tran(TYPE_ORACLE_DATE, obj);
		} else if (dbType == DBTypes.SYBASE && sybaseTIMESTAMP != null && sybaseTIMESTAMP.isInstance(obj)) {
			return TranSybase.tran(TYPE_SYBASE_TIMESTAMP, obj);
		}

		return obj;
	}

	private static String tranName(String name, boolean needTranContent, String dbCharset, String toCharset,
			boolean bb, String opt)
			throws Exception {
		String result = name;
		if (name != null && !bb) {
			try {
				if (needTranContent) {
					result = new String(name.getBytes(dbCharset), toCharset);
				}
			} catch (Exception e) {
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.charset", dbCharset, toCharset));
			}
		}
		if (result != null && opt != null && opt.indexOf("l") > -1) {
			result = result.toLowerCase();
		}
		return result;
	}

	private static String[] getKeyNames(Connection conn, String tableName) {
		if (conn == null || tableName == null || tableName.trim().length() < 1) {
			return null;
		}
		ResultSet rs = null;
		try {
			DatabaseMetaData dmd = conn.getMetaData();
			rs = dmd.getPrimaryKeys(conn.getCatalog(), null, tableName);
			int count = 0;
			ArrayList<String> nameList = new ArrayList<String>();
			ArrayList<Object> seqList = new ArrayList<Object>();
			while (rs.next()) {
				String keyName = rs.getString("COLUMN_NAME");
				Object seqObj = rs.getObject("KEY_SEQ");
				if (keyName != null && keyName.trim().length() > 0) {
					nameList.add(keyName);
					seqList.add(seqObj);
					count++;
				}
			}
			if (count > 0) {
				Object[] names0 = nameList.toArray();
				if (names0 == null || names0.length < 1) {
					return null;
				}
				String[] names = new String[count];
				int[] seqs = new int[count];
				for (int i = 0; i < count; i++) {
					seqs[i] = Integer.parseInt(seqList.get(i).toString());
					names[i] = names0[i].toString();
				}
				for (int i = 0; i < count - 1; i++) {
					for (int j = 0; j < count - 1 - i; j++) {
						if (seqs[j] > seqs[j + 1]) {
							int tmp = seqs[j];
							String tmps = names[j];
							seqs[j] = seqs[j + 1];
							names[j] = names[j + 1];
							seqs[j + 1] = tmp;
							names[j + 1] = tmps;
						}
					}
				}
				return names;
			}
			return null;
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e);
		} finally {
			try {
				if (rs != null) {
					rs.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
	}

	/**
	 * ��SQL�����ݿ��и�������ֵ���ѯstr�������ؽ��
	 * @param tableName	String ����
	 * @param str	String sql���ʽ
	 * @param keyValues	Object[] �����ֶζ�Ӧֵ
	 * @param dbs	DBSession ���ݿ�Session
	 * @return Object ��ѯ���
	 */
	public static Sequence select(String tableName, String str, Object[] keyValues, DBSession dbs, String opt) {
		return select(tableName, str, null, keyValues, dbs, opt);
	}

	/**
	 * ��SQL�����ݿ��и�������ֵ���ѯstr�������ؽ��
	 * @param tableName	String ����
	 * @param str	String sql���ʽ
	 * @param keyNames	��������
	 * @param keyValues	Object[] �����ֶζ�Ӧֵ
	 * @param dbs	DBSession ���ݿ�Session
	 * @return Object ��ѯ���
	 */
	public static Sequence select(String tableName, String str, String[] keyNames, Object[] keyValues, DBSession dbs,
			String opt) {
		DBInfo info = dbs.getInfo();
		String dbName = "";
		if (info != null) {
			dbName = info.getName();
		}
		String sql = "select " + str;
		Connection con = null;
		try {
			MessageManager mm = DataSetMessage.get();
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}
			if (con == null || con.isClosed()) {
				throw new RQException(mm.getMessage("error.conClosed", dbName));
			}
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e);
		}
		if (keyNames == null) {
			keyNames = getKeyNames(con, tableName);
		}
		if (keyNames == null) {
			return null;
		}

		int size = keyNames.length;
		String key = "";
		Object v = null;
		String condition = null;
		ArrayList<Object> params = new ArrayList<Object>();
		for (int i = 0; i < size; i++) {
			key = keyNames[i];
			if (key != null && key.trim().length() > 0) {
				v = null;
				if (i < keyValues.length) {
					v = keyValues[i];
				}
				if (condition == null) {
					condition = "(" + key + " = ?)";
					params.add(v);
				} else {
					condition += " and (" + key + " = ?)";
					params.add(v);
				}
			}
		}
		if (condition == null || condition.trim().length() < 1) {
			//throw new RQException("Condition is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidCondition"));
		}
		sql += " from " + addTilde(tableName, dbs) + " where " + condition;
		return retrieve2(sql, params.toArray(), null, dbs, opt);
	}

	/**
	 * ��SQL�����ݿ��и�������ֵ���ѯstr�������飬�����ؽ����������
	 * @param tableName	String ����
	 * @param str	String sql���ʽ
	 * @param keyValues	Object[] �����ֶζ�Ӧֵ
	 * @param dbs	DBSession ���ݿ�Session
	 * @return Object	��ѯ���
	 */
	public static Sequence select(String tableName, String[] strs, String[] keyNames, Object[] keyValues, DBSession dbs,
			String opt) {
		DBInfo info = dbs.getInfo();
		String dbName = "";
		if (info != null) {
			dbName = info.getName();
		}
		String sql = "select ";

		int size = strs.length;
		String fieldAll = null;
		String field = null;
		for (int i = 0; i < size; i++) {
			field = strs[i];
			if (field != null && field.trim().length() > 0) {
				if (fieldAll == null) {
					fieldAll = field;
				} else {
					fieldAll += ", " + field;
				}
			}
		}
		if (fieldAll == null || fieldAll.trim().length() < 1) {
			//throw new RQException("SQL strings are Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidFieldValue"));
		}

		Connection con = null;
		try {
			MessageManager mm = DataSetMessage.get();
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}
			if (con == null || con.isClosed()) {
				throw new RQException(mm.getMessage("error.conClosed", dbName));
			}
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e);
		}
		if (keyNames == null) {
			keyNames = getKeyNames(con, tableName);
		}
		if (keyNames == null) {
			return null;
		}

		size = keyNames.length;
		String key = "";
		Object v = null;
		String condition = null;
		ArrayList<Object> params = new ArrayList<Object>();
		for (int i = 0; i < size; i++) {
			key = keyNames[i];
			if (key != null && key.trim().length() > 0) {
				v = null;
				if (i < keyValues.length) {
					v = keyValues[i];
				}
				if (condition == null) {
					condition = "(" + key + " = ?)";
					params.add(v);
				} else {
					condition += " and (" + key + " = ?)";
					params.add(v);
				}
			}
		}
		if (condition == null || condition.trim().length() < 1) {
			//throw new RQException("Condition is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidCondition"));
		}
		sql += fieldAll + " from " + addTilde(tableName, dbs) + " where " + condition;
		return retrieve2(sql, params.toArray(), null, dbs, opt);
	}

	/**
	 * ��SQL�����ݿ��и�������ֵ���ѯstr�������飬�����ؽ����������
	 * @param tableName	String ����
	 * @param str	String sql���ʽ
	 * @param keyValues	Object[] �����ֶζ�Ӧֵ
	 * @param dbs	DBSession ���ݿ�Session
	 * @return 	Object ��ѯ���
	 */
	public static Sequence select(String tableName, String[] strs, Object[] keyValues, DBSession dbs, String opt) {
		return select(tableName, strs, null, keyValues, dbs, opt);
	}

	/**
	 * ��ָ�������ݿ�����ִ��sql��䣬���ؽ�����еļ�¼���У����ֻ��һ��Fieldһ����¼��ֱ�ӷ���ֵ�����򷵻�ֵ�����У�
	 * ��������Field������¼���򷵻�ֵ���е�����
	 * @param sql
	 *            String sql���
	 * @param params
	 *            Object[] ����ֵ�б�
	 * @param types
	 *            byte[] ���������б��ɿգ��������Ͳμ�com.scudata.common.Types�������������ַ����������
	 *            ���������Ϳ�ʱ����Ϊ��ͬ�ڡ�Ĭ�ϡ����ͣ���ʱע�����ֵ����Ϊnull
	 * @param dbs
	 *            DBSession
	 * @return Sequence
	 */
	private static Sequence retrieve2(String sql, Object[] params, byte[] types, DBSession dbs, String opt) {
		ResultSet rs = null;
		PreparedStatement pst = null;
		Connection con = null;
		String dbCharset = null;
		String toCharset = null;
		boolean tranSQL = false;
		boolean tranContent = true;
		int dbType = DBTypes.UNKNOWN;
		try {
			DBConfig dsConfig = null;
			MessageManager mm = DataSetMessage.get();
			if (dbs != null && dbs.getInfo() instanceof DBConfig) {
				dsConfig = (DBConfig) dbs.getInfo();
			}
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}

			if (con == null || con.isClosed()) {
				String name = "";
				DBInfo info = dbs.getInfo();
				if (info != null) {
					name = info.getName();
				}
				throw new RQException(mm.getMessage("error.conClosed", name));
			}

			if (dsConfig != null) {
				dbCharset = dsConfig.getDBCharset();
				tranSQL = dsConfig.getNeedTranSentence();
				tranContent = dsConfig.getNeedTranContent();
				if ((tranContent || tranSQL) && dbCharset == null) {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.fromCharset", name));
				}

				toCharset = dsConfig.getClientCharset();
				if ((tranContent || tranSQL) && toCharset == null) {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.toCharset", name));
				}

				dbType = dsConfig.getDBType();
			} else {
				tranContent = false;
			}

			if (tranSQL) {
				sql = new String(sql.getBytes(), dbCharset);
			}

			int paramCount = (params == null ? 0 : params.length);
			Object[] args = null;
			byte[] argTypes = null;
			if (paramCount > 0) {
				args = new Object[paramCount];
				argTypes = new byte[paramCount];
				int pos = 0;
				for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
					pos = Sentence.indexOf(sql, "?", pos + 1, Sentence.IGNORE_PARS + Sentence.IGNORE_QUOTE);
					args[paramIndex] = params[paramIndex];
					if (types == null || types.length <= paramIndex) {
						argTypes[paramIndex] = com.scudata.common.Types.DT_DEFAULT;
					} else {
						argTypes[paramIndex] = types[paramIndex];
					}
					if (args[paramIndex] == null) {
						continue;
					}
					if (args[paramIndex] instanceof Sequence && tranContent) {
						Sequence l = (Sequence) args[paramIndex];
						for (int i = 1, size = l.length(); i <= size; i++) {
							Object o = l.get(i);
							if (o instanceof String && tranSQL) {
								o = new String(((String) o).getBytes(), dbCharset);
								l.set(i, o);
							}
						}
					} else if (args[paramIndex] instanceof String && tranSQL) {
						args[paramIndex] = new String(((String) args[paramIndex]).getBytes(), dbCharset);
					}
					if (args[paramIndex] instanceof Sequence) {
						Object[] objs = ((Sequence) args[paramIndex]).toArray();
						int objCount = objs.length;
						StringBuffer sb = new StringBuffer(2 * objCount);
						for (int iObj = 0; iObj < objCount; iObj++) {
							sb.append("?,");
						}
						if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
							sb.deleteCharAt(sb.length() - 1);
						}
						if (sb.length() > 1) {
							sql = sql.substring(0, pos) + sb.toString() + sql.substring(pos + 1);
						}
						pos = pos + sb.length();
					}
				}
			}

			try {
				pst = con.prepareStatement(sql);
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);

				}
			}

			if (args != null && args.length > 0) {
				int pos = 0;
				for (int iArg = 0; iArg < args.length; iArg++) {
					pos++;
					try {
						byte type = argTypes[iArg];
						if (args[iArg] != null && args[iArg] instanceof Sequence) {
							Object[] objs = ((Sequence) args[iArg]).toArray();
							for (int iObj = 0; iObj < objs.length; iObj++) {
								SQLTool.setObject(dbType, pst, pos, objs[iObj], type);
								pos++;
							}
							pos--;
						} else {
							SQLTool.setObject(dbType, pst, pos, args[iArg], type);
						}
					} catch (Exception e) {
						String name = "";
						DBInfo info = dbs.getInfo();
						if (info != null) {
							name = info.getName();
						}
						throw new RQException(mm.getMessage("error.argIndex", name, Integer.toString(iArg + 1)));
					}
				}
			}

			try {
				rs = pst.executeQuery();
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
				}
			}
			return populate2(rs, dbCharset, tranContent, toCharset, dbType, opt);
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (rs != null) {
					rs.close();
				}
				if (pst != null) {
					pst.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
	}

	/**
	 * ��ָ�������ݿ�����ִ��sql��䣬���ؽ�����еļ�¼���У����ֻ��һ��Fieldһ����¼��ֱ�ӷ���ֵ�����򷵻�ֵ�����У�
	 * ��������Field������¼���򷵻�ֵ���е�����
	 * @param rs
	 *            ResultSet ������ݼ�
	 * @param dbCharset
	 *            String
	 * @param needTranContent
	 *            boolean
	 * @param toCharset
	 *            String
	 * @param dbType
	 *            int DBTypes�ж��������
	 * @throws SQLException
	 * @throws UnsupportedEncodingException
	 * @return Sequence
	 */
	private static Sequence populate2(ResultSet rs, String dbCharset, boolean needTranContent, String toCharset,
			int dbType, String opt) throws SQLException, UnsupportedEncodingException {
		if (rs == null) {
			return null;
		}

		ResultSetMetaData rsmd = rs.getMetaData();

		int colCount = rsmd.getColumnCount();

		if (needTranContent && (toCharset == null || toCharset.trim().length() == 0)) {
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.toCharset"));
		}

		boolean bb = true;
		if (toCharset != null) {
			bb = toCharset.equalsIgnoreCase(dbCharset) || dbCharset == null;
		}
		Sequence series = new Sequence();
		int size = 0;
		Object value = null;
		while (rs.next()) {
			if (colCount == 1) {
				int type = 0;
				if (dbType == DBTypes.ORACLE) {
					type = rsmd.getColumnType(1);
				}
				try {
					value = tranData(type, dbType, rs, 1, needTranContent, dbCharset, toCharset, bb, opt);
				} catch (Exception e) {
					e.printStackTrace();
				}
				series.add(value);
			} else {
				Sequence sub = new Sequence();
				for (int n = 1; n <= colCount; ++n) {
					int type = 0;
					if (dbType == DBTypes.ORACLE) {
						type = rsmd.getColumnType(n);
					}
					try {
						Object obj = tranData(type, dbType, rs, n, needTranContent, dbCharset, toCharset, bb, opt);
						sub.add(obj);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				value = sub;
				series.add(value);
			}
			size++;
		}
		if (size < 2) {
			Sequence ser = new Sequence();
			ser.add(value);
			return ser;
		} else {
			return series;
		}
	}

	/**
	 * ������������update��insert����������
	 * @param tableName	String ��/��ͼ��
	 * @param str	String Ҫ���µ�SQL���
	 * @param keyNames	String[] ������
	 * @param keyValues	Object[] ����ֵ
	 * @param dbs	DBSession edited by bdl, 2012.9.18, ���ӷ���ֵ
	 * @return 	���н�� ������䷵��Integer����ͨsql����Boolean
	 */
	public static Object update(String tableName, String str, Object[] params, byte[] types, Object[] keyValues,
			DBSession dbs) {
		if (tableName == null || tableName.trim().length() < 1) {
			//throw new RQException("Table Name is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidTable"));
		}
		Connection con = null;
		String[] keyNames = null;
		try {
			MessageManager mm = DataSetMessage.get();
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}
			if (con == null || con.isClosed()) {
				String dbName = "";
				DBInfo info = dbs.getInfo();
				if (info != null) {
					dbName = info.getName();
				}
				throw new RQException(mm.getMessage("error.conClosed", dbName));
			}
			keyNames = getKeyNames(con, tableName);
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e);
		}
		if (keyNames == null) {
			return null;
		}

		if (keyNames == null || keyNames.length < 1) {
			//throw new RQException("Key Names is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidKey"));
		}
		if (keyValues == null || keyValues.length < 1) {
			//throw new RQException("Key Values is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidKeyValue"));
		}

		int size = keyNames.length;
		String condition = null;
		String key = "";
		Object v = null;
		ArrayList<Object> conParams = new ArrayList<Object>();
		for (int i = 0; i < size; i++) {
			key = keyNames[i];
			if (key != null && key.trim().length() > 0) {
				v = null;
				if (i < keyValues.length) {
					v = keyValues[i];
				}
				if (condition == null) {
					condition = "(" + key + " = ?)";
					conParams.add(v);
				} else {
					condition += " and (" + key + " = ?)";
					conParams.add(v);
				}
			}
		}
		if (condition == null || condition.trim().length() < 1) {
			//throw new RQException("Condition is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidCondition"));
		}

		String sql = "select count(*) from " + addTilde(tableName, dbs) + " where " + condition;
		Sequence se = retrieve(sql, conParams.toArray(), null, dbs, null, -1);
		int n = ((Number) ((Record) se.get(1)).getFieldValue(0)).intValue();
		ArrayList<Object> allParams = new ArrayList<Object>();
		if (n > 0) {
			if (str == null || str.trim().length() < 1) {
				//throw new RQException("Update SQL String is Invalid!");
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.invalidUpdateSQL"));
			}

			sql = "update " + addTilde(tableName, dbs) + " set " + str + " where " + condition;
			addParams(allParams, params);
			allParams.addAll(conParams);
		} else {
			String fieldAll = "";
			if (str == null || str.trim().length() < 1) {
				//throw new RQException("Update SQL String is Invalid!");
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.invalidUpdateSQL"));
			}
			/*
			if (fieldAll == null || fieldAll.trim().length() < 1) {
				//throw new RQException("Field Names of Values is Invalid!");
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.invalidField"));
			} else {
				fieldAll += " )";
			}
			*/
			sql = "insert into " + addTilde(tableName, dbs) + " " + fieldAll + " values (" + str + " )";
			addParams(allParams, params);
		}
		if (dbs != null) {
			return runSQL(sql, allParams.toArray(), types, dbs, true, null);
		} else {
			String dbName = "";
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.conClosed", dbName));
		}
	}

	private static void addParams(ArrayList<Object> allParams, Object[] params) {
		if (params == null || params.length < 1) {
			return;
		}
		for (int i = 0, iCount = params.length; i < iCount; i++) {
			allParams.add(params[i]);
		}
	}

	/**
	 * ������������update��insert����������
	 * @param tableName	String ��/��ͼ��
	 * @param strs	String[] Ҫ���µ�SQL�����
	 * @param keyNames	String[] ������
	 * @param keyValues	Object[] ����ֵ
	 * @param dbs	DBSession edited by bdl, 2012.9.18, ���ӷ���ֵ
	 * @return ���н�� ������䷵��Integer����ͨsql����Boolean
	 */
	public static Object update(String tableName, String[] strs, String[] keyNames, Object[] keyValues, DBSession dbs) {
		if (tableName == null || tableName.trim().length() < 1) {
			//throw new RQException("Table Name is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidTable"));
		}
		if (keyNames == null || keyNames.length < 1) {
			//throw new RQException("Key Names is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidKey"));
		}
		if (keyValues == null || keyValues.length < 1) {
			//throw new RQException("Key Values is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidKeyValue"));
		}
		int size = strs.length;
		String str = null;
		for (int i = 0; i < size; i++) {
			if (str == null) {
				str = strs[i];
			} else {
				str += strs[i];
			}
		}

		size = keyNames.length;
		String condition = null;
		String key = "";
		Object v = null;
		ArrayList<Object> conParams = new ArrayList<Object>();
		for (int i = 0; i < size; i++) {
			key = keyNames[i];
			if (key != null && key.trim().length() > 0) {
				v = null;
				if (i < keyValues.length) {
					v = keyValues[i];
				}
				if (condition == null) {
					condition = "(" + key + " = ?)";
					conParams.add(v);
				} else {
					condition += " and (" + key + " = ?)";
					conParams.add(v);
				}
			}
		}
		if (condition == null || condition.trim().length() < 1) {
			//throw new RQException("Condition is Invalid!");
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.invalidCondition"));
		}

		String sql = "select count(*) from " + addTilde(tableName, dbs) + " where " + condition;
		Sequence se = retrieve(sql, null, null, dbs, null, -1);
		int n = ((Number) ((Record) se.get(1)).getFieldValue(0)).intValue();
		if (n > 0) {
			if (str == null || str.trim().length() < 1) {
				//throw new RQException("Update SQL String is Invalid!");
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.invalidUpdateSQL"));
			}
			sql = "update " + addTilde(tableName, dbs) + " set " + str + " where " + condition;
		} else {
			String fieldAll = "";
			if (str == null || str.trim().length() < 1) {
				//throw new RQException("Update SQL String is Invalid!");
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.invalidUpdateSQL"));
			}
			/*
			if (fieldAll == null || fieldAll.trim().length() < 1) {
				throw new RQException("Field Names of Values is Invalid!");
			} else {
				fieldAll += " )";
			}
			*/
			sql = "insert into " + addTilde(tableName, dbs) + " " + fieldAll + " values (" + str + " )";
		}
		if (dbs != null) {
			return runSQL(sql, conParams.toArray(), null, dbs, true, null);
		} else {
			String dbName = "";
			MessageManager mm = DataSetMessage.get();
			throw new RQException(mm.getMessage("error.conClosed", dbName));
		}

	}

	/**
	 * ִ�д洢������䣬���ؽ�����У�������ض�����ݼ����򷵻����е����У����������ֵ�������뽫ֵ����ָ������
	 * @param proc	String �洢�������
	 * @param params	Object[] ����ֵ
	 * @param modes	byte[] ����ģʽ������ѡ��DatabaseUtil.PROC_MODE_IN, PROC_MODE_OUT, PROC_MODE_INOUT
	 * @param types	byte[] ��������
	 * @param outVariables	String[] ���ֵʱ������ֵ�Ĳ�����
	 * @param dbs	DBSession
	 * @param ctx	Context �����Ļ���
	 */
	public static Sequence proc(String sql, Object[] params, byte[] modes, byte[] types, String[] outVariables,
			DBSession dbs, Context ctx) {
		PreparedStatement pst = null;
		ResultSet rs = null;
		Connection con = null;
		String dbCharset = null;
		String toCharset = null;
		boolean tranSQL = false;
		boolean tranContent = true;
		int dbType = DBTypes.UNKNOWN;
		boolean hasOutParam = false;
		try {
			DBInfo dsInfo = null;
			MessageManager mm = DataSetMessage.get();
			if (dbs != null && dbs.getInfo() instanceof DBInfo) {
				dsInfo = dbs.getInfo();
			}
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}

			String name = "";
			if (dsInfo != null) {
				name = dsInfo.getName();
			}
			if (con == null || con.isClosed()) {
				throw new RQException(mm.getMessage("error.conClosed", name));
			}

			if (dsInfo != null) {
				dbCharset = dsInfo.getDBCharset();
				tranSQL = dsInfo.getNeedTranSentence();
				tranContent = dsInfo.getNeedTranContent();
				if ((tranContent || tranSQL) && dbCharset == null) {
					throw new RQException(mm.getMessage("error.fromCharset", name));
				}

				toCharset = dsInfo.getClientCharset();
				if ((tranContent || tranSQL) && toCharset == null) {
					throw new RQException(mm.getMessage("error.toCharset", name));
				}

				dbType = dsInfo.getDBType();
			} else {
				tranContent = false;
			}

			if (tranSQL) {
				sql = new String(sql.getBytes(), dbCharset);
			}
			if (sql.trim().startsWith("call")) {
				sql = "{" + sql + "}";
			}

			if (tranSQL) {
				sql = new String(sql.getBytes(), dbCharset);
			}

			int paramCount = params == null ? 0 : params.length;
			Object[] args = null;
			int outCount = 0;
			int outCursor = 0;
			if (paramCount > 0) {
				args = new Object[paramCount];
				int pos = 0;
				for (int i = 0; i < paramCount; i++) {
					pos = Sentence.indexOf(sql, "?", pos + 1, Sentence.IGNORE_PARS + Sentence.IGNORE_QUOTE);
					byte mode = modes[i];
					if (mode == DatabaseUtil.PROC_MODE_OUT || mode == DatabaseUtil.PROC_MODE_INOUT) {
						hasOutParam = true;
						if (types[i] != com.scudata.common.Types.DT_CURSOR) {
							outCount++;
						} else {
							outCursor++;
						}
					}
					if (mode == DatabaseUtil.PROC_MODE_IN || mode == DatabaseUtil.PROC_MODE_INOUT) {
						args[i] = params[i];
						if (args[i] instanceof Sequence) {
							Sequence l = (Sequence) args[i];
							int count = l.length();
							StringBuffer sb = new StringBuffer(2 * count);
							for (int n = 0; n < count; n++) {
								sb.append("?,");
							}
							if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
								sb.deleteCharAt(sb.length() - 1);
							}
							if (sb.length() > 1) {
								sql = sql.substring(0, pos) + sb.toString() + sql.substring(pos + 1);
							}
							pos = pos + sb.length();
						}
					}
				}
			}
			int dsPos = -1; 
			Sequence dsPosGroup = new Sequence(); 
			String[] outParams = new String[outCount];
			int[] outParamsIndex = new int[outCount];
			int outIndex = -1;
			String[] outTables = new String[outCursor];
			int cursorIndex = -1;

			if (hasOutParam) {
				try {
					pst = con.prepareCall(sql);
				} catch (SQLException e) {
					if (dbs.getErrorMode()) {
						dbs.setError(e);
					} else {
						throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
					}
				}
			} else {
				try {
					pst = con.prepareStatement(sql);
				} catch (SQLException e) {
					if (dbs.getErrorMode()) {
						dbs.setError(e);
					} else {
						throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
					}
				}
			}

			int paramIndex = 0;
			for (int iArg = 0; iArg < paramCount; iArg++) {
				byte mode = modes[iArg];
				if (mode == DatabaseUtil.PROC_MODE_IN) {
					if (args[iArg] instanceof Sequence) {
						Sequence l = (Sequence) args[iArg];
						if (tranSQL) {
							for (int i = 1, size = l.length(); i <= size; i++) {
								Object o = l.get(i);
								if (o instanceof String) {
									o = new String(((String) o).getBytes(), dbCharset);
								}
								paramIndex++;
								SQLTool.setObject(dbType, pst, paramIndex, o, types[iArg]);
							}
						} else {
							for (int i = 1, size = l.length(); i <= size; i++) {
								Object o = l.get(i);
								paramIndex++;
								SQLTool.setObject(dbType, pst, paramIndex, o, types[iArg]);
							}
						}
					} else {
						Object o = args[iArg];
						if (tranSQL && o instanceof String) {
							o = new String(((String) o).getBytes(), dbCharset);
						}
						paramIndex++;
						SQLTool.setObject(dbType, pst, paramIndex, o, types[iArg]);
					}
				} else if (mode == DatabaseUtil.PROC_MODE_OUT) {
					paramIndex++;
					if (com.scudata.common.Types.DT_CURSOR == types[iArg]) {
						if (dbType == DBTypes.ORACLE) {
							try {
								Class<?> c = Class.forName("oracle.jdbc.driver.OracleTypes");
								Field f = c.getField("CURSOR");
								((CallableStatement) pst).registerOutParameter(paramIndex, f.getInt(null));
							} catch (Exception e) {
								throw new RQException(mm.getMessage("error.cursorException"));
							}
							if (dsPos < 0) {
								dsPos = paramIndex;
							} else {
								dsPosGroup.add(new Integer(paramIndex));
							}
							if (dsPos < 0) {
								throw new RQException(mm.getMessage("error.noResultSet"));
							}
							cursorIndex++;
							outTables[cursorIndex] = outVariables[iArg];
						}
						else if (dbType == DBTypes.POSTGRES || dbType == DBTypes.DBONE ) {
							((CallableStatement) pst).registerOutParameter(paramIndex, java.sql.Types.OTHER);
							if (dsPos < 0) {
								dsPos = paramIndex;
							}
							else {
								dsPosGroup.add(new Integer(paramIndex));
							}
							if (dsPos < 0) {
								throw new RQException(mm.getMessage("error.noResultSet"));
							}
						}
					} else {
						outIndex++;
						outParams[outIndex] = outVariables[iArg];
						outParamsIndex[outIndex] = paramIndex;
						registerOtherParameter((CallableStatement) pst, paramIndex, types[iArg], mm);
					}
				} else {
					Object o = args[iArg];
					if (tranContent && o instanceof String) {
						o = new String(((String) o).getBytes(), dbCharset);
					}
					paramIndex++;
					SQLTool.setObject(dbType, pst, paramIndex, o, types[iArg]);
					outIndex++;
					outParams[outIndex] = outVariables[iArg];
					outParamsIndex[outIndex] = paramIndex;
					registerOtherParameter((CallableStatement) pst, paramIndex, types[iArg], mm);
				}
			}
			if (hasOutParam) {
				if ((dbType == DBTypes.POSTGRES || dbType == DBTypes.DBONE) && dsPos > 0) {
					((CallableStatement) pst).execute(); 
				}
				else {
					rs = ((CallableStatement) pst).executeQuery(); 
				}
				try {
					for (int i = 0, count = outParams.length; i < count; i++) {
						Object value = ((CallableStatement) pst).getObject(outParamsIndex[i]);
						ctx.setParamValue(outParams[i], value);
					}
				} catch (Exception e) {
					throw new RQException(mm.getMessage("error.outParam"));
				}
			}
			try {
				if (dbType == DBTypes.ORACLE && dsPos > 0) {
					rs = (ResultSet) ((CallableStatement) pst).getObject(dsPos);
				}
				else if ((dbType == DBTypes.POSTGRES || dbType == DBTypes.DBONE) && dsPos > 0) {
					rs = (ResultSet) ((CallableStatement) pst).getObject(dsPos);
				}
				// edited by bd, 2022.12.11, ��������������pst�Ѿ�ִ�й���
				else if (hasOutParam) {
				}
				else {
					rs = pst.executeQuery();
				}
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
				}
			}

			if (dsInfo == null) {
				tranContent = false;
			}
			Sequence se = populate(rs, dbCharset, tranContent, toCharset, dbType, false, null, false, -1, null);
			if (outCursor > 0) {
				String outName = outTables[0];
				if (outName != null && outName.trim().length() > 0) {
					ctx.setParamValue(outName, se);
				}
			}

			if (dsPosGroup.length() > 0) {
				Sequence mul_dataset = new Sequence();
				mul_dataset.add(se);
				int size = dsPosGroup.length();
				for (int i = 0; i < size; i++) {
					int loc = ((Integer) dsPosGroup.get(i + 1)).intValue();
					if (rs != null) {
						try {
							rs.close();
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
					try {
						rs = (ResultSet) ((CallableStatement) pst).getObject(loc);
					} catch (SQLException e) {
						if (dbs.getErrorMode()) {
							dbs.setError(e);
						} else {
							throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
						}
					}
					Sequence se_add = populate(rs, dbCharset, tranContent, toCharset, dbType, false, null, false, -1,
							null);
					mul_dataset.add(se_add);
					if (outCursor > 0) {
						String outName = null;
						if (i < outTables.length - 1) {
							outName = outTables[i + 1];
						}
						if (outName != null && outName.trim().length() > 0) {
							ctx.setParamValue(outName, se_add);
						}
					}
				}
				if (mul_dataset.length() > 1) {
					return mul_dataset;
				}
			} else if (pst.getMoreResults()) {
				Sequence mul_dataset = new Sequence();
				mul_dataset.add(se);
				if (rs != null) {
					try {
						rs.close();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				rs = pst.getResultSet();
				Sequence se_add = populate(rs, dbCharset, tranContent, toCharset, dbType, false, null, false, -1, null);
				mul_dataset.add(se_add);

				while (pst.getMoreResults() || pst.getUpdateCount() != -1) {
					if (pst.getUpdateCount() == -1) {
						pst.getMoreResults();
						continue;
					}
					if (rs != null) {
						try {
							rs.close();
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
					rs = pst.getResultSet();
					se_add = populate(rs, dbCharset, tranContent, toCharset, dbType, false, null, false, -1, null);
					mul_dataset.add(se_add);
				}
				if (mul_dataset.length() > 1) {
					return mul_dataset;
				}
			}
			return se;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (rs != null) {
					rs.close();
				}
				if (pst != null) {
					pst.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
	}

	/**
	 * ע�����
	 * @param cst	CallableStatement
	 * @param paramIndex	�������
	 * @param type	��������
	 * @param mm	��Ϣ������
	 */
	public static void registerOtherParameter(CallableStatement cst, int paramIndex, int type, MessageManager mm) {
		try {
			switch (type) {
			case com.scudata.common.Types.DT_STRING_SERIES:
			case com.scudata.common.Types.DT_STRING:
				cst.registerOutParameter(paramIndex, java.sql.Types.VARCHAR);
				break;
			case com.scudata.common.Types.DT_DOUBLE_SERIES:
			case com.scudata.common.Types.DT_DOUBLE:
				cst.registerOutParameter(paramIndex, java.sql.Types.DOUBLE);
				break;
			case com.scudata.common.Types.DT_INT_SERIES:
			case com.scudata.common.Types.DT_INT:
				cst.registerOutParameter(paramIndex, java.sql.Types.INTEGER);
				break;
			case com.scudata.common.Types.DT_DATE_SERIES:
			case com.scudata.common.Types.DT_DATE:
				cst.registerOutParameter(paramIndex, java.sql.Types.DATE);
				break;
			case com.scudata.common.Types.DT_TIME_SERIES:
			case com.scudata.common.Types.DT_TIME:
				cst.registerOutParameter(paramIndex, java.sql.Types.TIME);
				break;
			case com.scudata.common.Types.DT_DATETIME_SERIES:
			case com.scudata.common.Types.DT_DATETIME:
				cst.registerOutParameter(paramIndex, java.sql.Types.TIMESTAMP);
				break;
			case com.scudata.common.Types.DT_LONG_SERIES:
			case com.scudata.common.Types.DT_LONG:
			case com.scudata.common.Types.DT_BIGINT_SERIES:
			case com.scudata.common.Types.DT_BIGINT:
				cst.registerOutParameter(paramIndex, java.sql.Types.BIGINT);
				break;
			case com.scudata.common.Types.DT_SHORT_SERIES:
			case com.scudata.common.Types.DT_SHORT:
				cst.registerOutParameter(paramIndex, java.sql.Types.SMALLINT);
				break;
			case com.scudata.common.Types.DT_FLOAT_SERIES:
			case com.scudata.common.Types.DT_FLOAT:
				cst.registerOutParameter(paramIndex, java.sql.Types.FLOAT);
				break;
			case com.scudata.common.Types.DT_DECIMAL_SERIES:
			case com.scudata.common.Types.DT_DECIMAL:
				cst.registerOutParameter(paramIndex, java.sql.Types.DECIMAL);
				break;
			default:
				cst.registerOutParameter(paramIndex, java.sql.Types.VARCHAR);
				break;
			}
		} catch (Exception e) {
			throw new RQException(mm.getMessage("error.regParam", Integer.toString(paramIndex)));
		}
	}

	private static String addTilde(String field, DBSession dbs) {
		if (dbs != null && dbs.getInfo() instanceof DBConfig) {
			DBConfig dbc = (DBConfig) dbs.getInfo();
			if (dbc.isAddTilde()) {
				int dbType = dbc.getDBType();
				field = DBTypes.getLeftTilde(dbType) + field + DBTypes.getRightTilde(dbType);
			}
		}
		return field;
	}

	private static String removeTilde(String field, DBSession dbs) {
		if (field == null || field.trim().length() < 1) {
			return field;
		}
		if (dbs != null && dbs.getInfo() instanceof DBConfig) {
			DBConfig dbc = (DBConfig) dbs.getInfo();
			if (dbc.isAddTilde()) {
				int dbType = dbc.getDBType();
				if (field.substring(0, 1).equals(DBTypes.getLeftTilde(dbType))) {
					field = field.substring(1);
				}
				if (field.substring(field.length() - 1).equals(DBTypes.getRightTilde(dbType))) {
					field = field.substring(0, field.length() - 1);
				}
			}
		}
		return field;
	}

	/**
	 * DBObject���ã�������е�ÿһ��Ԫ��ִ�в�ѯ��䣬���ؽ�����ϲ������ 
	 * @param sql	String
	 * @param valueGroup	Object[][]
	 * @param types	int[]
	 * @param dbs	DBSession
	 * @param opt	String
	 * @param ctx	Context
	 * @return Table
	 */
	public static Table queryGroup(String inisql, Object[][] valueGroup, byte[] types, DBSession dbs, String opt,
			Context ctx) {
		ResultSet rs = null;
		PreparedStatement pst = null;
		Connection con = null;
		String dbCharset = null;
		String toCharset = null;
		boolean tranSQL = false;
		boolean tranContent = true;
		int dbType = DBTypes.UNKNOWN;
		try {
			DBConfig dsConfig = null;
			MessageManager mm = DataSetMessage.get();
			if (dbs != null && dbs.getInfo() instanceof DBConfig) {
				dsConfig = (DBConfig) dbs.getInfo();
			}
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}

			if (con == null || con.isClosed()) {
				String name = "";
				DBInfo info = dbs.getInfo();
				if (info != null) {
					name = info.getName();
				}
				throw new RQException(mm.getMessage("error.conClosed", name));
			}

			if (dsConfig != null) {
				dbCharset = dsConfig.getDBCharset();
				tranSQL = dsConfig.getNeedTranSentence();
				tranContent = dsConfig.getNeedTranContent();
				if ((tranContent || tranSQL) && dbCharset == null) {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.fromCharset", name));
				}

				toCharset = dsConfig.getClientCharset();
				if ((tranContent || tranSQL) && toCharset == null) {
					String name = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						name = info.getName();
					}
					throw new RQException(mm.getMessage("error.toCharset", name));
				}

				dbType = dsConfig.getDBType();
			} else {
				tranContent = false;
			}

			if (tranSQL) {
				inisql = new String(inisql.getBytes(), dbCharset);
			}
			Table table = null;
			boolean addTable = false;
			if (opt != null && opt.indexOf("f") > -1) {
				addTable = true;

			}
			boolean oneRecord = false;
			if (opt != null && opt.indexOf("1") > -1) {
				oneRecord = true;

			}
			int queryCount = valueGroup == null ? 0 : valueGroup.length;

			for (int qi = 0; qi < queryCount; qi++) {
				try {
					if (rs != null) {
						try {
							rs.close();
						} catch (Exception e) {
							e.printStackTrace();
						}
						rs = null;
					}
					if (pst != null) {
						pst.close();
						pst = null;
					}
				} catch (Exception e) {
					throw new RQException(e.getMessage(), e);
				}
				String sql = inisql;
				Object[] params = valueGroup[qi];
				int paramCount = (params == null ? 0 : params.length);
				Object[] args = null;
				byte[] argTypes = null;
				if (paramCount > 0) {
					args = new Object[paramCount];
					argTypes = new byte[paramCount];
					int pos = 0;
					for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
						pos = Sentence.indexOf(sql, "?", pos + 1, Sentence.IGNORE_PARS + Sentence.IGNORE_QUOTE);
						args[paramIndex] = params[paramIndex];
						if (types == null || types.length <= paramIndex) {
							argTypes[paramIndex] = com.scudata.common.Types.DT_DEFAULT;
						} else {
							argTypes[paramIndex] = types[paramIndex];
						}
						if (args[paramIndex] == null) {
							continue;
						}
						if (args[paramIndex] instanceof Sequence && tranContent) {
							Sequence l = (Sequence) args[paramIndex];
							for (int i = 1, size = l.length(); i <= size; i++) {
								Object o = l.get(i);
								if (o instanceof String && tranSQL) {
									o = new String(((String) o).getBytes(), dbCharset);
									l.set(i, o);
								}
							}
						} else if (args[paramIndex] instanceof String && tranSQL) {
							args[paramIndex] = new String(((String) args[paramIndex]).getBytes(), dbCharset);
						}
						if (args[paramIndex] instanceof Sequence) {
							Object[] objs = ((Sequence) args[paramIndex]).toArray();
							int objCount = objs.length;
							StringBuffer sb = new StringBuffer(2 * objCount);
							for (int iObj = 0; iObj < objCount; iObj++) {
								sb.append("?,");
							}
							if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
								sb.deleteCharAt(sb.length() - 1);
							}
							if (sb.length() > 1) {
								sql = sql.substring(0, pos) + sb.toString() + sql.substring(pos + 1);
							}
							pos = pos + sb.length();
						}
					}
				}

				try {
					pst = con.prepareStatement(sql);
				} catch (SQLException e) {
					if (dbs.getErrorMode()) {
						dbs.setError(e);
					} else {
						String name = "";
						DBInfo info = dbs.getInfo();
						if (info != null) {
							name = info.getName();
						}
						throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
					}
				}

				if (args != null && args.length > 0) {
					int pos = 0;
					for (int iArg = 0; iArg < args.length; iArg++) {
						pos++;
						try {
							byte type = argTypes[iArg];
							if (args[iArg] != null && args[iArg] instanceof Sequence) {
								Object[] objs = ((Sequence) args[iArg]).toArray();
								for (int iObj = 0; iObj < objs.length; iObj++) {
									SQLTool.setObject(dbType, pst, pos, objs[iObj], type);
									pos++;
								}
								pos--;
							} else {
								SQLTool.setObject(dbType, pst, pos, args[iArg], type);
							}
						} catch (Exception e) {
							String name = "";
							DBInfo info = dbs.getInfo();
							if (info != null) {
								name = info.getName();
							}
							throw new RQException(mm.getMessage("error.argIndex", name, Integer.toString(iArg + 1)));
						}
					}
				}

				try {
					rs = pst.executeQuery();
				} catch (SQLException e) {
					if (dbs.getErrorMode()) {
						dbs.setError(e);
					} else {
						String name = "";
						DBInfo info = dbs.getInfo();
						if (info != null) {
							name = info.getName();
						}
						throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
					}
				}
				if (oneRecord) {
					return populate(rs, dbCharset, tranContent, toCharset, dbType, addTable, table, oneRecord, -1, opt);
				}
				table = populate(rs, dbCharset, tranContent, toCharset, dbType, addTable, table, oneRecord, -1, opt);
			}
			return table;
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (rs != null) {
					rs.close();
				}
				if (pst != null) {
					pst.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
	}

	private static byte[] toByteArray(ArrayList<Byte> bytes) {
		byte[] rs = new byte[bytes.size()];
		int i = 0;
		for (byte obj : bytes) {
			rs[i++] = obj;
		}
		return rs;
	}

	/**
	 * �����α���±�table�е��ֶ�fields�� ʹ��batchִ�У�Ҫ��һ��ִ�и��������α����ݣ���Ȼ�޷�����executeBatch
	 * @param cs	ICursor Դ�α�
	 * @param table	String ����
	 * @param fields	String[] �ֶ���
	 * @param fopts	String[] p���ֶ���������a���ֶ��������ֶ�
	 * @param exps	Expression[] ֵ���ʽ
	 * @param opt	String t����Ϊ�Ǹ��������k����ɺ�����״̬
	 * @param dbs	DBSession
	 * @param ctx	Context
	 * @return int	���ӷ���ֵ���ɹ���������
	 */
	public static int update(ICursor cs, String table, String[] fields, String[] fopts, Expression[] exps, String opt,
			DBSession dbs, Context ctx) {
		Statement st = null;
		Connection con = null;
		String dbCharset = null;
		String toCharset = null;
		boolean tranSQL = false;
		boolean tranContent = true;
		int dbType = DBTypes.UNKNOWN;

		int fsize = fields.length;
		String field = "";
		String fieldAll = null;
		String dbName = "";
		int batchSize = 1000;
		PreparedStatement pst = null;

		try {
			DBConfig dsConfig = null;
			if (dbs != null && dbs.getInfo() instanceof DBConfig) {
				dsConfig = (DBConfig) dbs.getInfo();
			}
			MessageManager mm = DataSetMessage.get();
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
				batchSize = dsConfig.getBatchSize();
				if (batchSize < 1) {
					batchSize = 1;
				}
				dbCharset = dsConfig.getDBCharset();
				tranSQL = dsConfig.getNeedTranSentence();
				tranContent = dsConfig.getNeedTranContent();
				if ((tranContent || tranSQL) && dbCharset == null) {
					throw new RQException(mm.getMessage("error.fromCharset", dbName));
				}

				toCharset = dsConfig.getClientCharset();
				if ((tranContent || tranSQL) && toCharset == null) {
					throw new RQException(mm.getMessage("error.toCharset", dbName));
				}
				dbType = dsConfig.getDBType();
			} else {
				tranContent = false;
			}

			DBInfo info = dbs.getInfo();
			if (info != null) {
				dbName = info.getName();
			}
			if (con == null || con.isClosed()) {
				throw new RQException(mm.getMessage("error.conClosed", dbName));
			}
			boolean bb = true;
			if (toCharset != null) {
				bb = toCharset.equalsIgnoreCase(dbCharset) || dbCharset == null;
			}

			ResultSet rs = null;
			byte[] tColTypes = null;
			ArrayList<Integer> keyCols = new ArrayList<Integer>();
			ArrayList<String> autoKeys = new ArrayList<String>();
			boolean initial = true;
			byte[] ais = new byte[fsize];
			if (fopts != null) {
				int osize = fopts.length;
				if (osize > fsize) {
					osize = fsize;
				}
				for (int i = 0; i < osize; i++) {
					String fopt = fopts[i];
					if (fopt != null) {
						if (fopt.indexOf("p") > -1) {
							keyCols.add(new Integer(i));
						}
						if (fopt.indexOf("a") > -1) {
							ais[i] = Col_AutoIncrement;
							autoKeys.add(fields[i]);
						} else {
							ais[i] = 0;
						}
					}
				}
			}
			int[] kis = null;
			int keysize = keyCols.size();
			String check_sql = "";
			String update_sql = "";
			String insert_sql = "";
			while (true) {
				Sequence fetchSeq = cs.fetch(batchSize);
				if (fetchSeq == null || fetchSeq.length() == 0)
					break;
				if (initial) {
					if (keysize < 1) {
						Object o1 = fetchSeq.get(1);
						if (o1 instanceof Record) {
							DataStruct ds1 = ((Record) o1).dataStruct();
							String[] keys = ds1.getPrimary();
							int kc = keys == null ? 0 : keys.length;
							for (int i = 0; i < kc; i++) {
								String key = keys[i];
								if (key == null) {
									continue;
								}
								int ki = -1;
								for (int j = 0; j < fsize; j++) {
									if (key.equalsIgnoreCase(fields[j])) {
										ki = j;
										break;
									}
								}
								if (ki > -1) {
									keyCols.add(new Integer(ki));
								}
							}
						}
						keysize = keyCols.size();
					}
					if (tColTypes == null || tColTypes.length < 1 || keysize < 1) {
						String sql = "select";
						for (int i = 0, iSize = fields.length; i < iSize; i++) {
							sql += " " + addTilde(fields[i], dbs);
							if (i < iSize - 1) {
								sql += ",";
							}
						}
						sql += " from " + addTilde(table, dbs) + " where 1 = 0";
						try {
							pst = con.prepareStatement(sql);
							rs = pst.executeQuery();
							ResultSetMetaData rsmd = rs.getMetaData();
							int colSize = rsmd.getColumnCount();
							tColTypes = new byte[fsize];

							for (int ci = 0; ci < colSize; ci++) {
								String colname = rsmd.getColumnLabel(ci + 1);
								colname = tranName(colname, tranContent, dbCharset, toCharset, bb, opt);
								if (colname == null || colname.trim().length() < 1) {
									continue;
								}
								for (int fi = 0; fi < fsize; fi++) {
									if (colname.equalsIgnoreCase(fields[fi])) {
										int sqlType = rsmd.getColumnType(ci + 1);
										byte rqType = com.scudata.common.Types.getTypeBySQLType(sqlType);
										tColTypes[fi] = rqType;
										break;
									}
								}
							}

							if (keysize < 1) {
								if (table.indexOf(".") > 0) {
									String[] tns = table.split("\\.");
									if (tns == null || tns.length < 2) {
										int loc = table.indexOf(".");
										tns = new String[2];
										tns[0] = table.substring(0, loc);
										tns[1] = table.substring(loc + 1);
									}
								} else {
								}
								try {
									while (rs.next()) {
										String columnName = rs.getString("COLUMN_NAME");
										if (columnName == null) {
											continue;
										}
										int ki = -1;
										for (int i = 0; i < fsize; i++) {
											if (columnName.equalsIgnoreCase(fields[i])) {
												ki = i;
												break;
											}
										}
										if (ki > -1) {
											keyCols.add(new Integer(ki));
										}
									}
									keysize = keyCols.size();
								}
								catch (Exception e) {
								}
							}
						} catch (Exception e) {
							throw new RQException(e.getMessage(), e);
						} finally {
							try {
								if (rs != null) {
									rs.close();
								}
								if (pst != null) {
									pst.close();
								}
							} catch (Exception e) {
								throw new RQException(e.getMessage(), e);
							}
							rs = null;
						}
					}
					if (keysize < 1) {
						keyCols.add(new Integer(0));
						keysize = keyCols.size();
					}

					Expression[] keyExps = null;
					kis = new int[keysize];
					keyExps = new Expression[keysize];
					for (int i = 0; i < keysize; i++) {
						kis[i] = ((Integer) keyCols.get(i)).intValue();
						keyExps[i] = exps[kis[i]];
					}

					for (int iField = 0; iField < fsize; iField++) {
						if (ais[iField] == Col_AutoIncrement) {
							continue;
						}

						field = fields[iField];
						if (field != null && field.trim().length() > 0) {
							if (fieldAll == null) {
								fieldAll = "(" + field;
							} else {
								fieldAll += ", " + field;
							}
						}
					}
					if (fieldAll == null || fieldAll.trim().length() < 1) {
						//throw new RQException("Field Names is Invalid!");
						throw new RQException(mm.getMessage("error.invalidField"));
					}
					fieldAll += ")";
				}
				try {
					if (initial) {
						if (opt != null && opt.indexOf("a") > -1) {
							//Logger.debug("Clear all the records from table +"+table);
							Logger.debug(mm.getMessage("info.clearTable", table));
							String sql = "delete from " + addTilde(table, dbs);
							st = con.createStatement();
							st.execute(sql);
							st.close();
						}

						String condition = null;
						String key = "";
						for (int j = 0; j < keysize; j++) {
							key = fields[kis[j]];
							if (key != null && key.trim().length() > 0) {
								if (condition == null) {
									condition = "(" + key + " = ?)";
								} else {
									condition += " and (" + key + " = ?)";
								}
							}
						}
						check_sql = "select count(*) from " + addTilde(table, dbs) + " where " + condition;
						String sets = null;
						for (int iField = 0; iField < fsize; iField++) {
							if (ais[iField] == Col_AutoIncrement) {
								continue;
							}

							field = fields[iField];
							if (field != null && field.trim().length() > 0) {//�˴���fieldû�й����Ƿ�Ϊ�������������������һ�飬xq 2015.4.21
								if (sets == null) {
									sets = field + " = ?";
								} else {
									sets += ", " + field + " = ?";
								}
							}
						}
						if (sets == null || sets.trim().length() < 1) {
							//throw new RQException("Field Names of Values is Invalid!");
							throw new RQException(mm.getMessage("error.invalidField"));
						}
						update_sql = "update " + addTilde(table, dbs) + " set " + sets + " where " + condition;
						sets = null;
						for (int iField = 0; iField < fsize; iField++) {
							if (ais[iField] == DataStruct.Col_AutoIncrement) {
								continue;
							}
							field = fields[iField];
							if (field != null && field.trim().length() > 0) {
								if (sets == null) {
									fieldAll = "(" + field;
									sets = "( ?";
								} else {
									fieldAll += ", " + field;
									sets += ", ?";
								}
							}
						}
						if (sets == null || sets.trim().length() < 1) {
							//throw new RQException("Field Values of Values is Invalid!");
							throw new RQException(mm.getMessage("error.invalidFieldValue"));
						} else {
							sets += " )";
						}
						if (fieldAll == null || fieldAll.trim().length() < 1) {
							//throw new RQException("Field Names of Values is Invalid!");
							throw new RQException(mm.getMessage("error.invalidField"));
						} else {
							fieldAll += " )";
						}
						insert_sql = "insert into " + addTilde(table, dbs) + " " + fieldAll + " values " + sets;

						if (tranSQL) {
							check_sql = new String(check_sql.getBytes(), dbCharset);
							update_sql = new String(update_sql.getBytes(), dbCharset);
							insert_sql = new String(insert_sql.getBytes(), dbCharset);
						}
					}

					ArrayList<Expression> updateParams = new ArrayList<Expression>();
					ArrayList<Expression> primaryParams = new ArrayList<Expression>();
					ArrayList<Expression> insertParams = new ArrayList<Expression>();
					ArrayList<Byte> updateTypes = new ArrayList<Byte>();
					ArrayList<Byte> primaryTypes = new ArrayList<Byte>();
					ArrayList<Byte> insertTypes = new ArrayList<Byte>();

					for (int iField = 0; iField < fsize; iField++) {
						if (ais[iField] == DataStruct.Col_AutoIncrement) {
							continue;
						}
						insertParams.add(exps[iField]);
						insertTypes.add(tColTypes[iField]);
					}

					for (int ki = 0; ki < keysize; ki++) {
						primaryParams.add(exps[kis[ki]]);
						primaryTypes.add(tColTypes[kis[ki]]);
					}

					updateParams.addAll(insertParams);
					updateTypes.addAll(insertTypes);
					updateParams.addAll(primaryParams);
					updateTypes.addAll(primaryTypes);

					boolean isAutoDetect = true;
					if (opt != null) {
						if (opt.indexOf('i') > -1) {
							if (initial) {
								try {
									//Logger.debug("Insert-only, preparing insert records: "+insert_sql);
									Logger.debug(mm.getMessage("info.insertOnly", insert_sql));
									pst = con.prepareStatement(insert_sql);
								} catch (SQLException e) {
									if (dbs.getErrorMode()) {
										dbs.setError(e);
									} else {
										throw new RQException(mm.getMessage("error.sqlException", dbName, insert_sql)
												+ " : " + e.getMessage(), e);
									}
								}
							}
							executeBatchPst(fetchSeq, pst, insertParams, insertTypes, ctx, dbs, dbCharset, tranSQL,
									dbType, dbName);
							isAutoDetect = false;
						} else if (opt.indexOf('u') > -1) {
							if (initial) {
								try {
									//Logger.debug("Update-only, preparing update records: "+update_sql);
									Logger.debug(mm.getMessage("info.updateOnly", update_sql));
									pst = con.prepareStatement(update_sql);
								} catch (SQLException e) {
									if (dbs.getErrorMode()) {
										dbs.setError(e);
									} else {
										throw new RQException(mm.getMessage("error.sqlException", dbName, update_sql)
												+ " : " + e.getMessage(), e);
									}
								}
							}
							executeBatchPst(fetchSeq, pst, updateParams, updateTypes, ctx, dbs, dbCharset, tranSQL,
									dbType, dbName);
							isAutoDetect = false;
						}
					}
					if (initial)
						initial = false;
					if (isAutoDetect) {
						Expression[] expParams = new Expression[primaryParams.size()];
						primaryParams.toArray(expParams);
						// edited by bd, 2022.4.15, �α����ʱ������ͨ���������Ƚϴ���ʱ��֧�ֶԿ�ֵ�����Ĵ���
						Sequence recordCount = query(fetchSeq, check_sql, expParams, toByteArray(primaryTypes), opt,
								ctx, dbs, null, null, keysize);
						Sequence updateRecords = new Sequence();
						Sequence insertRecords = new Sequence();
						int rsize = recordCount == null ? 0 : recordCount.length();
						for (int i = 1; i <= rsize; i++) {
							Record r = (Record) recordCount.get(i);
							int c = ((Number) r.getFieldValue(0)).intValue();
							if (c > 0) {
								updateRecords.add(fetchSeq.get(i));
							} else {
								insertRecords.add(fetchSeq.get(i));
							}
						}
						if (updateRecords.length() > 0) {
							//Logger.debug("Auto update, preparing update records: "+update_sql);
							Logger.debug(mm.getMessage("info.autoUpdate", update_sql));
							executeBatchSql(fetchSeq, update_sql, updateParams, updateTypes, ctx, dbs);
						}
						if (insertRecords.length() > 0) {
							//Logger.debug("Auto insert, preparing insert records: "+insert_sql);
							Logger.debug(mm.getMessage("info.autoInsert", insert_sql));
							executeBatchSql(fetchSeq, insert_sql, insertParams, insertTypes, ctx, dbs);
						}
					}

					/* ������������ִ��Ϊ����ִ�� xq 2015.4.21 end */
				} catch (RQException e) {
					//com.scudata.common.Logger.debug("update error:", e);
					Logger.debug(mm.getMessage("error.update", e.getMessage()));
					if (dbs.getErrorMode()) {
						dbs.setError(new SQLException(e.getMessage(), "Error: 5001 Update error: ", 5001));
					}
				}
			}
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (opt == null || opt.indexOf('k') < 0) {
					con.commit();
				}
				if (st != null) {
					st.close();
				}
				if (pst != null) {
					pst.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
		return 0;// ����������֪�����ĳ�������ֵ�����壬ʼ�շ���0 xq 2015.4.21
	}
	
	private static byte Col_NormalKey = 0x02;

	/**
	 * db.update(A:A',tbl,F:x,��;P,��)����Դ���кͶԱ����У��������ݿ��е�tbl��
	 * @param srcSeq	Sequence Դ���У�������tbl����ӻ���¼�¼
	 * @param compSeq	Sequence �Ա����У�������tbl�У�ɾ��������compSeq������srcSeq�еļ�¼
	 * @param table	String ���ݿ��б�����tbl
	 * @param fields	String[] ���ݿ���е��ֶ���������ʱ���ϲ������A�е��ֶ�
	 * @param fopts	String[] ���ÿ���ֶε�ѡ����ϲ�������ʽʱ���ã�a����p����
	 * @param exps	Expression[] ���ÿ���ֶθ������õı��ʽ������ʱ���ϲ����
	 * @param opt	String ѡ�֧�֣�iֻ���;uֻ����;dֻɾ��;aִ��ǰɾ��ԭ�������м�¼;
					l��һ���ֶ����Ը����ֶ�(�ϲ㴦��);k��ɺ��ύ����(�ϲ㴦��)
	 * @param dbs	DBSession
	 * @param ctx	Context
	 * @return
	 */
	public static int update(Sequence srcSeq, Sequence compSeq, String table, String[] fields, String[] fopts,
			Expression[] exps, String opt, DBSession dbs, Context ctx) {
		boolean oClear = false;
		if (opt != null && opt.indexOf("a") > -1) {
			oClear = true;
		}
		if (oClear || compSeq == null || compSeq.length() == 0) {
			//return update(srcSeq, table, fields, fopts, exps, opt, dbs, ctx);
		}
		
		Statement st = null;
		Connection con = null;
		String dbCharset = null;
		String toCharset = null;
		boolean tranSQL = false;
		boolean tranContent = true;
		int dbType = DBTypes.UNKNOWN;

		int fsize = fields.length;
		String field = "";
		String fieldAll = null;
		String dbn = "";
		int batchSize = 1000;

		try {
			DBConfig dsConfig = null;
			if (dbs != null && dbs.getInfo() instanceof DBConfig) {
				dsConfig = (DBConfig) dbs.getInfo();
			}
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}
			if (con == null || con.isClosed()) {
				DBInfo info = dbs.getInfo();
				if (info != null) {
					dbn = info.getName();
				}
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.conClosed", dbn));
			}
			boolean bb = true;

			ResultSet rs = null;
			byte[] tColTypes = null;
			ArrayList<Integer> keyCols = new ArrayList<Integer>();
			ArrayList<String> autoKeys = new ArrayList<String>();
			byte[] ais = new byte[fsize];
			if (fopts != null) {
				int osize = fopts.length;
				if (osize > fsize) {
					osize = fsize;
				}
				for (int i = 0; i < osize; i++) {
					String fopt = fopts[i];
					if (fopt != null) {
						if (fopt.indexOf("p") > -1) {
							keyCols.add(new Integer(i));
						}
						if (fopt.indexOf("a") > -1) {
							ais[i] = DataStruct.Col_AutoIncrement;
							autoKeys.add(fields[i]);
						}
						else if (fopt.indexOf("p") > -1) {
							// edited by bd, 2022.4.14, ����ʱ������������ֵ������Ԥ�ж�
							ais[i] = Col_NormalKey;
						}
						else {
							ais[i] = 0;
						}
					}
				}
			}
			int[] kis = null;
			int keysize = keyCols.size();

			if (tColTypes == null || tColTypes.length < 1 || keysize < 1 ) {
				String sql = "select";
				for (int i = 0, iSize = fields.length; i < iSize; i++) {
					sql += " " + addTilde(fields[i], dbs);
					if (i < iSize - 1) {
						sql += ",";
					}
				}
				sql += " from " + addTilde(table, dbs) + " where 1 = 0";
				PreparedStatement pst = null;
				try {
					pst = con.prepareStatement(sql);
					rs = pst.executeQuery();
					ResultSetMetaData rsmd = rs.getMetaData();
					int colSize = rsmd.getColumnCount();
					tColTypes = new byte[fsize];

					for (int ci = 0; ci < colSize; ci++) {
						String colname = rsmd.getColumnLabel(ci + 1);
						colname = tranName(colname, tranContent, dbCharset, toCharset, bb, opt);
						if (colname == null || colname.trim().length() < 1) {
							continue;
						}
						for (int fi = 0; fi < fsize; fi++) {
							if (colname.equalsIgnoreCase(fields[fi])) {
								int sqlType = rsmd.getColumnType(ci + 1);
								byte rqType = com.scudata.common.Types.getTypeBySQLType(sqlType);
								tColTypes[fi] = rqType;
								break;
							}
						}
					}
					
					if (keysize < 1) {
						DatabaseMetaData dbmd = con.getMetaData();
						String schema = "", tn = "";
						if (table.indexOf(".") > 0) {
							String[] tns = table.split("\\.");
							if (tns == null || tns.length < 2) {
								int loc = table.indexOf(".");
								tns = new String[2];
								tns[0] = table.substring(0, loc);
								tns[1] = table.substring(loc + 1);
							}
							schema = tns[0];
							tn = tns[1];
						} else {
							tn = table;
						}
						try {
							rs.close();
							rs = dbmd.getPrimaryKeys("", schema, tn);
							while (rs.next()) {
								String columnName = rs.getString("COLUMN_NAME");
								if (columnName == null) {
									continue;
								}
								int ki = -1;
								for (int i = 0; i < fsize; i++) {
									if (columnName.equalsIgnoreCase(fields[i])) {
										ki = i;
										break;
									}
								}
								if (ki > -1) {
									keyCols.add(new Integer(ki));
								}
							}
							keysize = keyCols.size();
						}
						catch (Exception e) {
						}
					}
				} catch (Exception e) {
					throw new RQException(e.getMessage(), e);
				} finally {
					try {
						if (rs != null) {
							rs.close();
						}
						if (pst != null) {
							pst.close();
						}
					} catch (Exception e) {
						throw new RQException(e.getMessage(), e);
					}
					rs = null;
				}
			}
			keysize = keyCols.size();
			if (keysize < 1) {
				DataStruct ds1 = null;
				if (srcSeq instanceof Table) {
					ds1 = ((Table) srcSeq).dataStruct();
				}
				if (ds1 == null&& srcSeq != null && srcSeq.length() > 0 ) {
					Object o1 = srcSeq.get(1);
					if (o1 instanceof Record) {
						ds1 = ((Record) o1).dataStruct();
					}
				}
				if (ds1 != null) {
					String[] keys = ds1.getPrimary();
					int kc = keys == null ? 0 : keys.length;
					for (int i = 0; i < kc; i++) {
						String key = keys[i];
						if (key == null) {
							continue;
						}
						int ki = -1;
						for (int j = 0; j < fsize; j++) {
							if (key.equalsIgnoreCase(fields[j])) {
								ki = j;
								break;
							}
						}
						if (ki > -1) {
							keyCols.add(new Integer(ki));
						}
					}
				}
				keysize = keyCols.size();
			}
			if (keysize < 1) {
				//throw new RQException("update function can't find Key Columns.");
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.invalidKey"));
			}

			Expression[] keyExps = null;
			kis = new int[keysize];
			keyExps = new Expression[keysize];
			for (int i = 0; i < keysize; i++) {
				kis[i] = ((Integer) keyCols.get(i)).intValue();
				keyExps[i] = exps[kis[i]];
			}

			for (int iField = 0; iField < fsize; iField++) {
				if (ais[iField] == DataStruct.Col_AutoIncrement) {
					continue;
				}

				field = fields[iField];
				if (field != null && field.trim().length() > 0) {
					if (fieldAll == null) {
						fieldAll = "(" + field;
					} else {
						fieldAll += ", " + field;
					}
				}
			}
			if (fieldAll == null || fieldAll.trim().length() < 1) {
				//throw new RQException("Field Names is Invalid!");
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.invalidField"));
			}
			fieldAll += ")";

			if (dsConfig != null) {
				batchSize = dsConfig.getBatchSize();
				if (batchSize < 1) {
					batchSize = 1;
				}
				dbCharset = dsConfig.getDBCharset();
				tranSQL = dsConfig.getNeedTranSentence();
				tranContent = dsConfig.getNeedTranContent();
				if ((tranContent || tranSQL) && dbCharset == null) {
					String dbName = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						dbName = info.getName();
					}
					MessageManager mm = DataSetMessage.get();
					throw new RQException(mm.getMessage("error.fromCharset", dbName));
				}

				toCharset = dsConfig.getClientCharset();
				if ((tranContent || tranSQL) && toCharset == null) {
					String dbName = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						dbName = info.getName();
					}
					MessageManager mm = DataSetMessage.get();
					throw new RQException(mm.getMessage("error.toCharset", dbName));
				}

				dbType = dsConfig.getDBType();
			} else {
				tranContent = false;
			}
			String dbName = "";
			try {
				boolean clearAll = false;
				if (opt != null && opt.indexOf("a") > -1) {
					//Logger.debug("Clear all the records from "+table);
					MessageManager mm = DataSetMessage.get();
					Logger.debug(mm.getMessage("info.clearTable", table));
					String sql = "delete from " + addTilde(table, dbs);
					st = con.createStatement();
					st.execute(sql);
					st.close();
					clearAll = true;
				}

				// edited by bd, 2022.4.14, ���ⲿ�ָ�ֵ��ǰ�����������֮ǰ���ã�����Ϊ����������ʽȥ���������п��ܰ����Ŀ�ֵ
				/* ������������ִ��Ϊ����ִ�� xq 2015.4.21 begin */
				ArrayList<Expression> updateParams = new ArrayList<Expression>();
				ArrayList<Expression> updateFields = new ArrayList<Expression>();
				ArrayList<Expression> primaryParams = new ArrayList<Expression>();
				ArrayList<Expression> insertParams = new ArrayList<Expression>();
				ArrayList<Byte> updateTypes = new ArrayList<Byte>();
				ArrayList<Byte> primaryTypes = new ArrayList<Byte>();
				ArrayList<Byte> insertTypes = new ArrayList<Byte>();

				for (int iField = 0; iField < fsize; iField++) {
					if (ais[iField] == DataStruct.Col_AutoIncrement) {
						continue;
					}
					insertParams.add(exps[iField]);
					insertTypes.add(tColTypes[iField]);
					// edited by bd, 2022.4.14, updateʱ���ٸ�������ֵ
					if (ais[iField] != DatabaseUtil.Col_NormalKey) {
						updateParams.add(exps[iField]);
						updateFields.add(new Expression(fields[iField]));
						updateTypes.add(tColTypes[iField]);
					}
				}
				ArrayList<Expression> primaryFields = new ArrayList<Expression>();
				for (int ki = 0; ki < keysize; ki++) {
					primaryFields.add(new Expression(fields[kis[ki]]));
					primaryParams.add(exps[kis[ki]]);
					primaryTypes.add(tColTypes[kis[ki]]);
					updateFields.add(new Expression(fields[kis[ki]]));
				}

				//updateParams.addAll(insertParams);
				//updateTypes.addAll(insertTypes);
				updateParams.addAll(primaryParams);
				updateTypes.addAll(primaryTypes);

				// added by bd, 2022.4.14, ����ÿ�������Ƿ��ǿ�ֵ
				Expression[] params = new Expression[primaryParams.size()];
				ArrayList<Integer> nullKeys = new ArrayList<Integer>();
				primaryParams.toArray(params);
				int paramCount = params.length;
				int len = srcSeq.length();

				ComputeStack stack = ctx.getComputeStack();
				Sequence.Current current = srcSeq.new Current();
				stack.push(current);

				try {
					for (int p = 0; p < paramCount; ++p) {
						if (params[p] == null)
							continue;
						for (int i = 1; i <= len; ++i) {
							current.setCurrent(i);
							Object pv = params[p].calculate(ctx);
							if (pv == null) {
								nullKeys.add(p);
								break;
							}
						}
					}
				} finally {
					stack.pop();
				}
				int nkeys = nullKeys.size();
				int nulls = (int) Math.round(Math.pow(2, nkeys)) - 1;
				String[] ucons = nulls > 0 ? new String[nulls] : null;
				
				String condition = null;
				String key = "";
				String check_sql = "";
				String update_sql = "";
				String insert_sql = "";
				String delete_sql = "";
				String[] ch_sqls = nulls > 0 ? new String[nulls] : null;
				String[] up_sqls = nulls > 0 ? new String[nulls] : null;
				String[] de_sqls = nulls > 0 ? new String[nulls] : null;
				for (int j = 0; j < keysize; j++) {
					key = fields[kis[j]];
					if (key != null && key.trim().length() > 0) {
						if (condition == null) {
							condition = "(" + key + " = ?)";
							// added by bd, 2022.4.14, �����ǰ�п����п�ֵ�����ڶ�Ӧ�ļ���λ�õ�����������is null����
							if (ucons != null) {
								int kloc = nullKeys.indexOf(j);
								if (kloc>-1) {
									// ��ǰ�������ܴ��ڿ�ֵ
									for (int k = 0; k < nulls; k++) {
										if ((k & (1<<kloc)) == 0) {
											ucons[k] = key + " is null";
										}
										else {
											ucons[k] = "(" + key + " = ?)";
										}
									}
								}
							}
						} else {
							condition += " and (" + key + " = ?)";
							// added by bd, 2022.4.14, �����ǰ�п����п�ֵ�����ڶ�Ӧ�ļ���λ�õ�����������is null����
							if (ucons != null) {
								int kloc = nullKeys.indexOf(j);
								if (kloc>-1) {
									// ��ǰ�������ܴ��ڿ�ֵ
									for (int k = 0; k < nulls; k++) {
										if ((k & (1<<kloc)) == 0) {
											ucons[k] += " and " + key + " is null";
										}
										else {
											ucons[k] += " and (" + key + " = ?)";
										}
									}
								}
							}
						}
					}
				}
				check_sql = "select count(*) from " + addTilde(table, dbs) + " where " + condition;
				delete_sql = "delete from " + addTilde(table, dbs) + " where " + condition;
				if (ucons != null) {
					for (int i = 0; i < nulls; i++) {
						ch_sqls[i] = "select count(*) from " + addTilde(table, dbs) + " where " + ucons[i];
						de_sqls[i] = "delete from " + addTilde(table, dbs) + " where " + ucons[i];
					}
				}
				String usets = null;
				for (int iField = 0; iField < fsize; iField++) {
					// edited by bd, 2022.4.14, ����ʱ���ٽ�������һ����
					if (ais[iField] == DataStruct.Col_AutoIncrement || 
							ais[iField] == DatabaseUtil.Col_NormalKey) {
						continue;
					}

					field = fields[iField];
					if (field != null && field.trim().length() > 0) {// �����˴���fieldû�й����Ƿ�Ϊ�������������������һ�飬xq
																		// 2015.4.21
						if (usets == null) {
							usets = field + " = ?";
						} else {
							usets += ", " + field + " = ?";
						}
					}
				}
				if (usets == null || usets.trim().length() < 1) {
					if (fields == null || fields.length == 0) {
						//throw new RQException("Field Names of Values is Invalid!");
						MessageManager mm = DataSetMessage.get();
						throw new RQException(mm.getMessage("error.invalidField"));
					}
					else {
						// edited by bd, 2022.4.14, ����û�ֻ����������ֵ����ʵ�Ǵ���ģ����Ǿ͸�������һ�����������ˣ�
						for (int iField = 0; iField < fsize; iField++) {
							if (ais[iField] == DataStruct.Col_AutoIncrement) {
								continue;
							}

							field = fields[iField];
							if (field != null && field.trim().length() > 0) {
								if (usets == null) {
									usets = field + " = ?";
									break;
								}
							}
						}
						if (usets == null || usets.trim().length() < 1) {
							//throw new RQException("Field Names of Values is Invalid!");
							MessageManager mm = DataSetMessage.get();
							throw new RQException(mm.getMessage("error.invalidFieldValue"));
						}
						else {
							//Logger.warn("No field value will be updated!");
							MessageManager mm = DataSetMessage.get();
							Logger.warn(mm.getMessage("info.noUpdateField"));
						}
					}
				}
				update_sql = "update " + addTilde(table, dbs) + " set " + usets + " where " + condition;
				if (ucons != null) {
					for (int i = 0; i < nulls; i++) {
						up_sqls[i] = "update " + addTilde(table, dbs) + " set " + usets + " where " + ucons[i];
					}
				}
				String sets = null;
				for (int iField = 0; iField < fsize; iField++) {
					if (ais[iField] == DataStruct.Col_AutoIncrement) {
						continue;
					}
					field = fields[iField];
					if (field != null && field.trim().length() > 0) {
						if (sets == null) {
							fieldAll = "(" + field;
							sets = "( ?";
						} else {
							fieldAll += ", " + field;
							sets += ", ?";
						}
					}
				}
				if (sets == null || sets.trim().length() < 1) {
					//throw new RQException("Field Values of Values is Invalid!");
					MessageManager mm = DataSetMessage.get();
					throw new RQException(mm.getMessage("error.invalidFieldValue"));
				} else {
					sets += " )";
				}
				if (fieldAll == null || fieldAll.trim().length() < 1) {
					//throw new RQException("Field Names of Values is Invalid!");
					MessageManager mm = DataSetMessage.get();
					throw new RQException(mm.getMessage("error.invalidField"));
				} else {
					fieldAll += " )";
				}
				insert_sql = "insert into " + addTilde(table, dbs) + " " + fieldAll + " values " + sets;

				if (tranSQL) {
					check_sql = new String(check_sql.getBytes(), dbCharset);
					update_sql = new String(update_sql.getBytes(), dbCharset);
					insert_sql = new String(insert_sql.getBytes(), dbCharset);
					delete_sql = new String(delete_sql.getBytes(), dbCharset);
					if (ucons != null) {
						for (int i = 0; i < nulls; i++) {
							ch_sqls[i] = new String(ch_sqls[i].getBytes(), dbCharset);
							de_sqls[i] = new String(de_sqls[i].getBytes(), dbCharset);
							up_sqls[i] = new String(up_sqls[i].getBytes(), dbCharset);
						}
					}
				}


				boolean isAutoDetect = true;
				if (opt != null) {// û��ѡ��ʱ��ʹ���Զ��жϼ�¼�Ĳ�������
					if (opt.indexOf('d') > -1 && !clearAll) {
						//Logger.debug("Delete-only, preparing delete records: "+delete_sql);
						MessageManager mm = DataSetMessage.get();
						Logger.debug(mm.getMessage("info.deleteOnly"));
						Expression[] keysFields = new Expression[primaryFields.size()];
						primaryFields.toArray(keysFields);
						// edited by bd, 2022.4.15, ɾ��ʱ���������Ϊnull�����ж�ʱ�޷�ʹ��F=?����Ҫʹ��F is null��Ϊ�ˣ���Ҫ��remainSeq�У�����Ϊnull�ļ�¼���������ִ��
						executeDifferBatch(srcSeq, compSeq, delete_sql, primaryFields, primaryParams, primaryTypes, ctx, dbs, con,
								dbCharset, tranSQL, dbType, dbName, batchSize, de_sqls, nullKeys, keysize);
						isAutoDetect = false;
					} else if (opt.indexOf('i') > -1) {
						if (compSeq != null && compSeq.length() > 0) {
							//Logger.debug("Insert only, preparing insert new-records: "+insert_sql);
							MessageManager mm = DataSetMessage.get();
							Logger.debug(mm.getMessage("info.insertOnly"));
							Expression[] keysParam = new Expression[primaryParams.size()];
							primaryParams.toArray(keysParam);
							Expression[] keysFields = new Expression[primaryFields.size()];
							primaryFields.toArray(keysFields);
							Sequence insertSeq = diffSequence(srcSeq, compSeq, keysParam, keysFields, ctx);
							ListBase1 oldMems = srcSeq.getMems();
							srcSeq.setMems(insertSeq.getMems());
							executeBatchSql(srcSeq, insert_sql, insertParams,
									insertTypes, ctx, dbs, con, dbCharset, tranSQL, dbType, dbName, batchSize);
							srcSeq.setMems(oldMems);
						} else {
							//Logger.debug("Insert-Only, preparing insert records: "+insert_sql);
							MessageManager mm = DataSetMessage.get();
							Logger.debug(mm.getMessage("info.insertOnly"));
							executeBatchSql(srcSeq, insert_sql, insertParams, insertTypes, ctx, dbs, con, dbCharset,
									tranSQL, dbType, dbName, batchSize);
						}
						isAutoDetect = false;
					} else if (opt.indexOf('u') > -1) {
						if (compSeq != null && compSeq.length() > 0) {
							//Logger.debug("Update-only, update changed-records: "+update_sql);
							MessageManager mm = DataSetMessage.get();
							Logger.debug(mm.getMessage("info.updateOnly"));
							Expression[] keysParam = new Expression[primaryParams.size()];
							primaryParams.toArray(keysParam);
							Expression[] keysFields = new Expression[primaryFields.size()];
							primaryFields.toArray(keysFields);
							Sequence remainSeq = isectSequence(srcSeq, compSeq, keysParam, keysFields, ctx);
							ListBase1 oldMems = srcSeq.getMems();
							srcSeq.setMems(remainSeq.getMems());
							//edited by bd, 2022.4.15, @uѡ��ʱ���������keysize��λ�õĿ�ֵ���ø������
							executeDifferBatch(compSeq, srcSeq, update_sql, updateParams, updateFields, updateTypes, ctx, dbs, con,
									dbCharset, tranSQL, dbType, dbName, batchSize, up_sqls, nullKeys, keysize);
							srcSeq.setMems(oldMems);
						} else {
							executeBatchSql(srcSeq, update_sql, updateParams, updateTypes, ctx, dbs, con, dbCharset,
									tranSQL, dbType, dbName, batchSize);
						}
						isAutoDetect = false;
					}
				}
				if (isAutoDetect) {
					if (compSeq != null && compSeq.length() > 0) {
						if (!clearAll) {
							Expression[] keysFields = new Expression[primaryFields.size()];
							primaryFields.toArray(keysFields);
							//Logger.debug("Auto delete, preparing delete lost-records: "+delete_sql);
							MessageManager mm = DataSetMessage.get();
							Logger.debug(mm.getMessage("info.autoDelete"));
							//edited by bd, 2022.4.15, @uѡ��ʱ���������keysize��λ�õĿ�ֵ���ø������
							executeDifferBatch(srcSeq, compSeq, delete_sql, primaryFields, primaryParams, primaryTypes, ctx, dbs, con,
									dbCharset, tranSQL, dbType, dbName, batchSize, de_sqls, nullKeys, keysize);
						}
						Expression[] keysParam = new Expression[primaryParams.size()];
						primaryParams.toArray(keysParam);
						//Logger.debug("Auto insert, preparing insert new-records: "+insert_sql);
						MessageManager mm = DataSetMessage.get();
						Logger.debug(mm.getMessage("info.autoInsert"));
						Expression[] keysFields = new Expression[primaryFields.size()];
						primaryFields.toArray(keysFields);
						Sequence insertSeq = diffSequence(srcSeq, compSeq, keysParam, keysFields, ctx);
						ListBase1 oldMems = srcSeq.getMems();
						srcSeq.setMems(insertSeq.getMems());
						executeBatchSql(srcSeq, insert_sql, insertParams,
								insertTypes, ctx, dbs, con, dbCharset, tranSQL, dbType, dbName, batchSize);
						srcSeq.setMems(oldMems);
						Logger.debug("Auto update, preparing update changed-records: "+update_sql);
						Logger.debug(mm.getMessage("info.autoUpdate"));
						Sequence remainSeq = mergeDiffSequence(srcSeq, insertSeq, null, ctx);
						// edited by bd, 2022.4.14, ����ʱ���������Ϊnull�����ж�ʱ�޷�ʹ��F=?����Ҫʹ��F is null��Ϊ�ˣ���Ҫ��remainSeq�У�����Ϊnull�ļ�¼���������ִ��
						srcSeq.setMems(remainSeq.getMems());
						//edited by bd, 2022.4.15, �������keysize��λ�õĿ�ֵ���ø������
						executeDifferBatch(compSeq, srcSeq, update_sql, updateParams, updateFields, updateTypes, ctx, dbs, con,
								dbCharset, tranSQL, dbType, dbName, batchSize, up_sqls, nullKeys, keysize);
						srcSeq.setMems(oldMems);
					} else {
						Expression[] expParams = new Expression[primaryParams.size()];
						primaryParams.toArray(expParams);
						//edited by bd, 2022.4.15, �������keysize��λ�õĿ�ֵ���ò�ѯ���
						Sequence recordCount = query(srcSeq, check_sql, expParams, toByteArray(primaryTypes), opt, ctx,
								dbs, ch_sqls, nullKeys, keysize);
						Sequence updateRecords = new Sequence();
						Sequence insertRecords = new Sequence();
						int rsize = recordCount == null ? 0 : recordCount.length();
						for (int i = 1; i <= rsize; i++) {
							Record r = (Record) recordCount.get(i);
							// edited by bd, 2022.12.9, ����ѯ��Ӧ����Ϊ��ʱ����ֹ����
							int c = 0;
							if (r != null)
								c = ((Number) r.getFieldValue(0)).intValue();
							//int c = ((Number) r.getFieldValue(0)).intValue();
							if (c > 0) {
								updateRecords.add(srcSeq.get(i));
							} else {
								insertRecords.add(srcSeq.get(i));
							}
						}
						if (updateRecords.length() > 0) {
							//Logger.debug("Auto update, preparing update records: "+update_sql);
							MessageManager mm = DataSetMessage.get();
							Logger.debug(mm.getMessage("info.autoUpdate"));
							//executeBatchSql(updateRecords, update_sql, updateParams, updateTypes, ctx, dbs, con,
							//		dbCharset, tranSQL, dbType, dbName, batchSize);
							executeDifferBatch(null, updateRecords, update_sql, updateParams, updateFields, updateTypes, ctx, dbs, con,
									dbCharset, tranSQL, dbType, dbName, batchSize, up_sqls, nullKeys, keysize);
						}
						if (insertRecords.length() > 0) {
							//Logger.debug("Auto insert, preparing insert records: "+update_sql);
							MessageManager mm = DataSetMessage.get();
							Logger.debug(mm.getMessage("info.autoInsert"));
							executeBatchSql(insertRecords, insert_sql, insertParams, insertTypes, ctx, dbs, con,
									dbCharset, tranSQL, dbType, dbName, batchSize);
						}
					}
				}

				/* ������������ִ��Ϊ����ִ�� xq 2015.4.21 end */
			} catch (RQException e) {
				//com.scudata.common.Logger.debug("update error:", e);
				MessageManager mm = DataSetMessage.get();
				Logger.debug(mm.getMessage("error.update", e.getMessage()));
				if (dbs.getErrorMode()) {
					dbs.setError(new SQLException(e.getMessage(), "Error: 5001 Update error: ", 5001));
				}
			}
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (opt == null || opt.indexOf('k') < 0) {
					con.commit();
				}
				if (st != null) {
					st.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
		return 0;// ����������֪�����ĳ�������ֵ�����壬ʼ�շ���0 xq 2015.4.21
	}

	/**
	 * DBObject���ã�����srcSeries���±�table�е��ֶ�fields
	 * @param srcSeries	Sequence Դ����
	 * @param table	String ����
	 * @param fields	String[] �ֶ���
	 * @param fopts	String[] p���ֶ���������a���ֶ��������ֶ�
	 * @param exps	Expression[] ֵ���ʽ
	 * @param opt	String t����Ϊ�Ǹ��������k����ɺ�����״̬
	 * @param dbs	DBSession
	 * @param ctx	Context
	 * @return int 	���ӷ���ֵ���ɹ���������
	 */
	public static int update(Sequence srcSeries, String table, String[] fields, String[] fopts, Expression[] exps,
			String opt, DBSession dbs, Context ctx) {
		if (1 > 0)
			return update(srcSeries, null, table, fields, fopts, exps, opt, dbs, ctx);
		boolean oClear = false;
		if (opt != null && opt.indexOf("a") > -1) {
			oClear = true;
		}
		boolean oInsert = false;
		if (opt != null && opt.indexOf("i") > -1) {
			oInsert = true;
		}
		if ( srcSeries == null || srcSeries.length() == 0) {
			return 0;
		}
		Statement st = null;
		Connection con = null;
		String dbCharset = null;
		String toCharset = null;
		boolean tranSQL = false;
		boolean tranContent = true;

		int fsize = fields.length;
		String field = "";
		String fieldAll = null;

		try {
			DBConfig dsConfig = null;
			if (dbs != null && dbs.getInfo() instanceof DBConfig) {
				dsConfig = (DBConfig) dbs.getInfo();
			}
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}
			if (con == null || con.isClosed()) {
				String dbName = "";
				DBInfo info = dbs.getInfo();
				if (info != null) {
					dbName = info.getName();
				}
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.conClosed", dbName));
			}
			boolean bb = true;

			ResultSet rs = null;
			byte[] tColTypes = null;
			ArrayList<Integer> keyCols = new ArrayList<Integer>();
			ArrayList<String> autoKeys = new ArrayList<String>();
			byte[] ais = new byte[fsize];
			if (fopts != null) {
				int osize = fopts.length;
				if (osize > fsize) {
					osize = fsize;
				}
				for (int i = 0; i < osize; i++) {
					String fopt = fopts[i];
					if (fopt != null) {
						if (fopt.indexOf("p") > -1) {
							keyCols.add(new Integer(i));
						}
						if (fopt.indexOf("a") > -1) {
							ais[i] = DataStruct.Col_AutoIncrement;
							autoKeys.add(fields[i]);
						}
						else if (fopt.indexOf("p") > -1) {
							// edited by bd, 2022.4.14, ����ʱ������������ֵ������Ԥ�ж�
							ais[i] = Col_NormalKey;
						}
						else {
							ais[i] = 0;
						}
					}
				}
			}
			int[] kis = null;
			int keysize = keyCols.size();
			if (tColTypes == null || tColTypes.length < 1 || keysize < 1) {
				String sql = "select";
				for (int i = 0, iSize = fields.length; i < iSize; i++) {
					sql += " " + addTilde(fields[i], dbs);
					if (i < iSize - 1) {
						sql += ",";
					}
				}
				sql += " from " + addTilde(table, dbs) + " where 1 = 0";
				PreparedStatement pst = null;
				try {
					pst = con.prepareStatement(sql);
					rs = pst.executeQuery();
					ResultSetMetaData rsmd = rs.getMetaData();
					int colSize = rsmd.getColumnCount();
					tColTypes = new byte[fsize];

					for (int ci = 0; ci < colSize; ci++) {
						String colname = rsmd.getColumnLabel(ci + 1);
						colname = tranName(colname, tranContent, dbCharset, toCharset, bb, opt);
						if (colname == null || colname.trim().length() < 1) {
							continue;
						}
						for (int fi = 0; fi < fsize; fi++) {
							if (colname.equalsIgnoreCase(fields[fi])) {
								int sqlType = rsmd.getColumnType(ci + 1);
								byte rqType = com.scudata.common.Types.getTypeBySQLType(sqlType);
								tColTypes[fi] = rqType;
								break;
							}
						}
					}

					if (!oClear && !oInsert && keysize < 1) {
						DatabaseMetaData dbmd = con.getMetaData();
						String schema = "", tn = "";
						if (table.indexOf(".") > 0) {
							String[] tns = table.split("\\.");
							if (tns == null || tns.length < 2) {
								int loc = table.indexOf(".");
								tns = new String[2];
								tns[0] = table.substring(0, loc);
								tns[1] = table.substring(loc + 1);
							}
							schema = tns[0];
							tn = tns[1];
						} else {
							tn = table;
						}
						try {
							rs.close();
							rs = dbmd.getPrimaryKeys("", schema, tn);
							while (rs.next()) {
								String columnName = rs.getString("COLUMN_NAME");
								if (columnName == null) {
									continue;
								}
								int ki = -1;
								for (int i = 0; i < fsize; i++) {
									if (columnName.equalsIgnoreCase(fields[i])) {
										ki = i;
										break;
									}
								}
								if (ki > -1) {
									keyCols.add(new Integer(ki));
								}
							}
							keysize = keyCols.size();
						}
						catch (Exception e) {
						}
					}
				} catch (Exception e) {
					throw new RQException(e.getMessage(), e);
				} finally {
					try {
						if (rs != null) {
							rs.close();
						}
						if (pst != null) {
							pst.close();
						}
					} catch (Exception e) {
						throw new RQException(e.getMessage(), e);
					}
					rs = null;
				}
			}
			if (!oClear && !oInsert && keysize < 1) {
				DataStruct ds1 = null;
				if (srcSeries instanceof Table) {
					ds1 = ((Table) srcSeries).dataStruct();
				}
				if (ds1 == null ) {
					Object o1 = srcSeries.get(1);
					if (o1 instanceof Record) {
						ds1 = ((Record) o1).dataStruct();
					}
				}
				if (ds1 != null) {
					String[] keys = ds1.getPrimary();
					int kc = keys == null ? 0 : keys.length;
					for (int i = 0; i < kc; i++) {
						String key = keys[i];
						if (key == null) {
							continue;
						}
						int ki = -1;
						for (int j = 0; j < fsize; j++) {
							if (key.equalsIgnoreCase(fields[j])) {
								ki = j;
								break;
							}
						}
						if (ki > -1) {
							keyCols.add(new Integer(ki));
						}
					}
				}
				keysize = keyCols.size();
			}
			if (!oClear && !oInsert && keysize < 1) {
				keyCols.add(new Integer(0));
				keysize = keyCols.size();
			}

			Expression[] keyExps = null;
			kis = new int[keysize];
			keyExps = new Expression[keysize];
			for (int i = 0; i < keysize; i++) {
				kis[i] = ((Integer) keyCols.get(i)).intValue();
				keyExps[i] = exps[kis[i]];
			}

			for (int iField = 0; iField < fsize; iField++) {
				if (ais[iField] == DataStruct.Col_AutoIncrement) {
					continue;
				}

				field = fields[iField];
				if (field != null && field.trim().length() > 0) {
					if (fieldAll == null) {
						fieldAll = "(" + field;
					} else {
						fieldAll += ", " + field;
					}
				}
			}
			if (fieldAll == null || fieldAll.trim().length() < 1) {
				//throw new RQException("Field Names is Invalid!");
				MessageManager mm = DataSetMessage.get();
				throw new RQException(mm.getMessage("error.invalidField"));
			}
			fieldAll += ")";
			if (dsConfig != null) {
				dbCharset = dsConfig.getDBCharset();
				tranSQL = dsConfig.getNeedTranSentence();
				tranContent = dsConfig.getNeedTranContent();
				if ((tranContent || tranSQL) && dbCharset == null) {
					String dbName = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						dbName = info.getName();
					}
					MessageManager mm = DataSetMessage.get();
					throw new RQException(mm.getMessage("error.fromCharset", dbName));
				}

				toCharset = dsConfig.getClientCharset();
				if ((tranContent || tranSQL) && toCharset == null) {
					String dbName = "";
					DBInfo info = dbs.getInfo();
					if (info != null) {
						dbName = info.getName();
					}
					MessageManager mm = DataSetMessage.get();
					throw new RQException(mm.getMessage("error.toCharset", dbName));
				}

			} else {
				tranContent = false;
			}

			DataStruct tds = null;
			if (srcSeries instanceof Table) {
				tds = ((Table) srcSeries).dataStruct();
			}
			if (tds == null && srcSeries.length()>0) {
				Object o1 = srcSeries.get(1);
				if (o1 instanceof Record) {
					tds = ((Record) o1).dataStruct();
				}
			}
			try {
				if (oClear) {
					//Logger.debug("Clear all the records from table +"+table);
					MessageManager mm = DataSetMessage.get();
					Logger.debug(mm.getMessage("info.clearTable", table));
					String sql = "delete from " + addTilde(table, dbs);
					st = con.createStatement();
					st.execute(sql);
					st.close();
				}
				
				// edited by bd, 2022.4.15, ���ⲿ�ָ�ֵ��ǰ�����������֮ǰ���ã�����Ϊ����������ʽȥ���������п��ܰ����Ŀ�ֵ
				// ������������ִ��Ϊ����ִ�� xq 2015.4.21 begin
				ArrayList<Expression> updateParams = new ArrayList<Expression>();
				ArrayList<Expression> primaryParams = new ArrayList<Expression>();
				ArrayList<Expression> insertParams = new ArrayList<Expression>();
				ArrayList<Byte> updateTypes = new ArrayList<Byte>();
				ArrayList<Byte> primaryTypes = new ArrayList<Byte>();
				ArrayList<Byte> insertTypes = new ArrayList<Byte>();

				for (int iField = 0; iField < fsize; iField++) {
					if (ais[iField] == DataStruct.Col_AutoIncrement) {
						continue;
					}
					insertParams.add(exps[iField]);
					insertTypes.add(tColTypes[iField]);
					// edited by bd, 2022.4.14, updateʱ���ٸ�������ֵ
					if (ais[iField] != DatabaseUtil.Col_NormalKey) {
						updateParams.add(exps[iField]);
						updateTypes.add(tColTypes[iField]);
					}
				}

				for (int ki = 0; ki < keysize; ki++) {
					primaryParams.add(exps[kis[ki]]);
					primaryTypes.add(tColTypes[kis[ki]]);
				}

				//updateParams.addAll(insertParams);
				//updateTypes.addAll(insertTypes);
				updateParams.addAll(primaryParams);
				updateTypes.addAll(primaryTypes);

				String condition = null;
				String key = "";
				String check_sql = "";
				String update_sql = "";
				String insert_sql = "";
				for (int j = 0; j < keysize; j++) {
					key = fields[kis[j]];
					if (key != null && key.trim().length() > 0) {
						if (condition == null) {
							condition = "(" + key + " = ?)";
						} else {
							condition += " and (" + key + " = ?)";
						}
					}
				}
				check_sql = "select count(*) from " + addTilde(table, dbs) + " where " + condition;
				String sets = null;
				for (int iField = 0; iField < fsize; iField++) {
					if (ais[iField] == DataStruct.Col_AutoIncrement) {
						continue;
					}

					field = fields[iField];
					if (field != null && field.trim().length() > 0) {// �����˴���fieldû�й����Ƿ�Ϊ�������������������һ�飬xq 2015.4.21
						if (sets == null) {
							sets = field + " = ?";
						} else {
							sets += ", " + field + " = ?";
						}
					}
				}
				if (sets == null || sets.trim().length() < 1) {
					//throw new RQException("Field Names of Values is Invalid!");
					MessageManager mm = DataSetMessage.get();
					throw new RQException(mm.getMessage("error.invalidField", table));
				}
				update_sql = "update " + addTilde(table, dbs) + " set " + sets + " where " + condition;
				sets = null;
				for (int iField = 0; iField < fsize; iField++) {
					if (ais[iField] == DataStruct.Col_AutoIncrement) {
						continue;
					}
					field = fields[iField];
					if (field != null && field.trim().length() > 0) {
						if (sets == null) {
							fieldAll = "(" + field;
							sets = "( ?";
						} else {
							fieldAll += ", " + field;
							sets += ", ?";
						}
					}
				}
				if (sets == null || sets.trim().length() < 1) {
					//throw new RQException("Field Values of Values is Invalid!");
					MessageManager mm = DataSetMessage.get();
					throw new RQException(mm.getMessage("error.invalidFieldValue", table));
				} else {
					sets += " )";
				}
				if (fieldAll == null || fieldAll.trim().length() < 1) {
					//throw new RQException("Field Names of Values is Invalid!");
					MessageManager mm = DataSetMessage.get();
					throw new RQException(mm.getMessage("error.invalidField", table));
				} else {
					fieldAll += " )";
				}
				insert_sql = "insert into " + addTilde(table, dbs) + " " + fieldAll + " values " + sets;

				if (tranSQL) {
					check_sql = new String(check_sql.getBytes(), dbCharset);
					update_sql = new String(update_sql.getBytes(), dbCharset);
					insert_sql = new String(insert_sql.getBytes(), dbCharset);
				}


				boolean isAutoDetect = true;
				if (opt != null) {// û��ѡ��ʱ��ʹ���Զ��жϼ�¼�Ĳ�������
					if (oClear || oInsert) {// ǿ�ƶ�ÿ����¼ִ��insert���Ϸ����ɳ���Ա��֤��ͨ��������a����ִ��ɾ��
						//Logger.debug("Insert-only, preparing insert records: "+insert_sql);
						MessageManager mm = DataSetMessage.get();
						Logger.debug(mm.getMessage("info.insertOnly", table));
						executeBatchSql(srcSeries, insert_sql, insertParams, insertTypes, ctx, dbs);
						isAutoDetect = false;
					} else if (opt.indexOf('u') > -1) {// ǿ�ƶ�ÿ����¼ִ��update���Ϸ����ɳ���Ա��֤
						//Logger.debug("Update-only, preparing update records: "+update_sql);
						MessageManager mm = DataSetMessage.get();
						Logger.debug(mm.getMessage("info.updateOnly", table));
						executeBatchSql(srcSeries, update_sql, updateParams, updateTypes, ctx, dbs);
						isAutoDetect = false;
					}
				}
				if (isAutoDetect) {// ʹ���Զ�̽���¼�Ĳ���͸���״̬
					Expression[] expParams = new Expression[primaryParams.size()];
					primaryParams.toArray(expParams);
					
					Sequence recordCount = query(srcSeries, check_sql, expParams, toByteArray(primaryTypes), opt, ctx,
							dbs, null, null, 0);
					Sequence updateRecords = new Sequence();
					Sequence insertRecords = new Sequence();
					int rsize = recordCount == null ? 0 : recordCount.length();
					for (int i = 1; i <= rsize; i++) {
						Record r = (Record) recordCount.get(i);
						int c = ((Number) r.getFieldValue(0)).intValue();
						if (c > 0) {
							updateRecords.add(srcSeries.get(i));
						} else {
							insertRecords.add(srcSeries.get(i));
						}
					}
					if (updateRecords.length() > 0) {
						//Logger.debug("Auto update, preparing update records: "+update_sql);
						MessageManager mm = DataSetMessage.get();
						Logger.debug(mm.getMessage("info.updateOnly", table));
						executeBatchSql(updateRecords, update_sql, updateParams, updateTypes, ctx, dbs);
					}
					if (insertRecords.length() > 0) {
						//Logger.debug("Auto insert, preparing insert records: "+insert_sql);
						MessageManager mm = DataSetMessage.get();
						Logger.debug(mm.getMessage("info.insertOnly", table));
						executeBatchSql(insertRecords, insert_sql, insertParams, insertTypes, ctx, dbs);
					}
				}

				// ������������ִ��Ϊ����ִ�� xq 2015.4.21 end 
			} catch (RQException e) {
				//com.scudata.common.Logger.debug("update error:", e);
				MessageManager mm = DataSetMessage.get();
				Logger.debug(mm.getMessage("error.update", e.getMessage()));
				if (dbs.getErrorMode()) {
					dbs.setError(new SQLException(e.getMessage(), "Error: 5001 Update error: ", 5001));
				}
			}
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (opt == null || opt.indexOf('k') < 0) {
					con.commit();
				}
				if (st != null) {
					st.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
		return 0;// ����������֪�����ĳ�������ֵ�����壬ʼ�շ���0 xq 2015.4.21
	}

	private static void executeBatchSql(Sequence srcSeries, String sql, ArrayList<Expression> exps, ArrayList<Byte> expTypes,
			Context ctx, DBSession dbs) {
		Expression[] expParams = new Expression[exps.size()];
		exps.toArray(expParams);
		execute(srcSeries, sql, expParams, toByteArray(expTypes), ctx, dbs);
	}

	// edited by bd, 2022.4.15, ��Ӳ���de_sqls��nullKeys�����ǿ�����ִ��ɾ��sqlʱ�������ֵ���п�ֵ��Ҫ��is null���жϣ���Ӧ����de_sqls�л�ȡ��
	// nullKeys�м�¼���Ǹ������ܿ�ֵ��λ�ã����ݿ�ֵ������λ�û�ȡde_sqls�����λ�ã��������3����ֵ��ȫ�ǿ�ֵ��Ӧλ��0����1����ֵ��Ӧ110������
	// ���keysize��¼��������������updateʱ�ıȽ�
	private static void executeDifferBatch(Sequence srcSeq, Sequence compSeq, String sql,
			ArrayList<Expression> paramExps, ArrayList<Expression> oldFieldExps, ArrayList<Byte> paramTypes, Context ctx, DBSession dbs, Connection con,
			String dbCharset, boolean tranSQL, int dbType, String dbn, int batchSize, String[] nullSqls, ArrayList<Integer> nullKeys, int keysize) {
		Expression[] params = new Expression[paramExps.size()];
		paramExps.toArray(params);
		Expression[] fieldParams = new Expression[oldFieldExps.size()];
		oldFieldExps.toArray(fieldParams);
		byte[] types = toByteArray(paramTypes);

		if (compSeq == null || compSeq.length() == 0) {
			return;
		}
		int pCount = params == null ? 0 : params.length;
		// edited by bd, 2022.4.15, keyStart��¼�ӵڼ���λ�����Ѿ��Ǽ�ֵ�ˡ�
		int keyStart = pCount - keysize;
		int len = compSeq.length();
		ComputeStack stack = ctx.getComputeStack();
		Sequence.Current current = compSeq.new Current();
		stack.push(current);
		Sequence usingParams = new Sequence();
		int nulls = nullSqls == null ? 0 : nullSqls.length;
		boolean nkeys = nullKeys != null && nullKeys.size() > 0 && nulls > 0;
		ArrayList<Sequence> nullParams = null;
		if (nkeys) {
			nullParams = new ArrayList<Sequence>(nulls);
			for (int i = 0; i < nulls; i++)
				nullParams.add(new Sequence());
		}
		try {
			for (int i = 1; i <= len; ++i) {
				current.setCurrent(i);
				Sequence pValues = new Sequence();
				int nloc = 0;
				for (int p = 0; p < pCount; ++p) {
					if (params[p] != null) {
						Object pv = params[p].calculate(ctx);
						if (nkeys && p >= keyStart && pv == null) {
							int kloc = nullKeys.indexOf(p-keyStart);
							nloc = nloc + (1<<kloc);
							// ����ĳ���is null�жϣ���Ӧ�Ĳ�����������
							// ���ǵ����趨����Ȼû��ִ�бȽ���
						}
						pValues.add(pv);
					}
				}
				if (nloc > 0) {
					nloc = nulls - nloc;
					nullParams.get(nloc).add(pValues);
				}
				else {
					usingParams.add(pValues);
				}
			}
		} finally {
			stack.pop();
		}

		Sequence initParams = new Sequence();
		if (srcSeq != null && srcSeq.length() > 0) {
			len = srcSeq.length();
			stack = ctx.getComputeStack();
			current = srcSeq.new Current();
			stack.push(current);
			try {
				for (int i = 1; i <= len; ++i) {
					current.setCurrent(i);
					Sequence pValues = new Sequence();
					for (int p = 0; p < pCount; ++p) {
						if (fieldParams[p] != null)
							pValues.add(fieldParams[p].calculate(ctx));
					}
					initParams.add(pValues);
				}
			} finally {
				stack.pop();
			}
		}
		usingParams = usingParams.sort(null);
		initParams = initParams.sort(null);
		Sequence diffParamSeq = usingParams.diff(initParams, true);
		len = diffParamSeq.length();
		if (len < 1 && !nkeys)
			return;
		Object[][] valueGroup = new Object[len][pCount];
		for (int i = 1; i <= len; ++i) {
			Sequence seq = (Sequence) diffParamSeq.get(i);
			valueGroup[i - 1] = seq.toArray();
		}
		executeBatch(sql, valueGroup, types, dbs, con, dbCharset, tranSQL, dbType, dbn, batchSize, true);

		// ���compSeq�а�����ֵ��
		if (nkeys) {
			for (int i = 0; i < nulls; i++) {
				Sequence nullParam = nullParams.get(i);
				nullParam = nullParam.sort(null);
				diffParamSeq = nullParam.diff(initParams, true);
				len = diffParamSeq.length();
				if (len < 1)
					continue;
				valueGroup = new Object[len][pCount];
				sql = nullSqls[i];
				for (int j = 1; j <= len; ++j) {
					Sequence seq = (Sequence) diffParamSeq.get(j);
					// �ȽϺ���������ȥ�������еĿ�ֵ
					int psize = seq.length();
					ArrayList<Object> ps = new ArrayList<Object>();
					for (int p = 0; p < psize; p++) {
						Object pv = seq.get(p+1);
						if (p < keyStart || pv != null ) {
							ps.add(pv);
						}
					}
					valueGroup[j - 1] = ps.toArray();
				}
				executeBatch(sql, valueGroup, types, dbs, con, dbCharset, tranSQL, dbType, dbn, batchSize, true);
			}
		}
	}
	
	private static Sequence mergeDiffSequence(Sequence seq1, Sequence seq2, Expression[] exps, Context ctx) {
		Sequence diffAll = new Sequence();
		diffAll.add(seq1);
		diffAll.add(seq2);
		Sequence diffSeq = diffAll.merge(exps, "od", ctx);
		return diffSeq;
	}
	
	private static Sequence diffSequence(Sequence seq1, Sequence seq2, Expression[] exps1, Expression[] exps2, Context ctx) {
		if (exps1 == null || exps1.length < 1 || exps1[0] == null) {
			return mergeDiffSequence(seq1, seq2, exps2, ctx);
		}
		if (exps2 == null || exps2.length < 1 || exps2[0] == null) {
			return mergeDiffSequence(seq1, seq2, exps1, ctx);
		}
		int keyCount = exps1.length;
		ListBase1 mems2 = seq2.getMems();
		int len2 = mems2.size();
		
		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil((int)(len2 * 1.2));
		ListBase1 []groups = new ListBase1[hashUtil.getCapacity()];

		ComputeStack stack = ctx.getComputeStack();
		Sequence.Current current = seq2.new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= len2; ++i) {
				Object []keys = new Object[keyCount];
				current.setCurrent(i);
				for (int c = 0; c < keyCount; ++c) {
					keys[c] = exps2[c].calculate(ctx);
				}

				int hash = hashUtil.hashCode(keys, keyCount);
				if (groups[hash] == null) {
					groups[hash] = new ListBase1(INIT_GROUPSIZE);
					groups[hash].add(keys);
				} else {
					int index = HashUtil.bsearch_a(groups[hash], keys, keyCount);
					if (index < 1) {
						groups[hash].add(-index, keys);
					} else {
						groups[hash].add(index, keys);
					}
				}
			}
		} finally {
			stack.pop();
		}
		
		ListBase1 mems1 = seq1.getMems();
		int len1 = mems1.size();
		Sequence result = new Sequence(len1);
		
		current = seq1.new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= len1; ++i) {
				Object []keys = new Object[keyCount];
				current.setCurrent(i);
				for (int c = 0; c < keyCount; ++c) {
					keys[c] = exps1[c].calculate(ctx);
				}

				int hash = hashUtil.hashCode(keys, keyCount);
				if (groups[hash] == null) {
					result.add(mems1.get(i));
				} else {
					int index = HashUtil.bsearch_a(groups[hash], keys, keyCount);
					if (index < 1) {
						result.add(mems1.get(i));
					} else {
						groups[hash].remove(index);
					}
				}
			}
		} finally {
			stack.pop();
		}
		
		result.trimToSize();
		return result;
	}
	
	private static Sequence mergeIntersection(Sequence seq1, Sequence seq2, Expression[] exps, Context ctx) {
		Sequence diffAll = new Sequence();
		diffAll.add(seq1);
		diffAll.add(seq2);
		Sequence diffSeq = diffAll.merge(exps, "oi", ctx);
		return diffSeq;
	}

	private static Sequence isectSequence(Sequence seq1, Sequence seq2, Expression[] exps1, Expression[] exps2, Context ctx) {
		if (exps1 == null || exps1.length < 1 || exps1[0] == null) {
			return mergeIntersection(seq1, seq2, exps2, ctx);
		}
		if (exps2 == null || exps2.length < 1 || exps2[0] == null) {
			return mergeIntersection(seq1, seq2, exps1, ctx);
		}
		int keyCount = exps1.length;
		ListBase1 mems2 = seq2.getMems();
		int len2 = mems2.size();
		
		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil((int)(len2 * 1.2));
		ListBase1 []groups = new ListBase1[hashUtil.getCapacity()];

		ComputeStack stack = ctx.getComputeStack();
		Sequence.Current current = seq2.new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= len2; ++i) {
				Object []keys = new Object[keyCount];
				current.setCurrent(i);
				for (int c = 0; c < keyCount; ++c) {
					keys[c] = exps2[c].calculate(ctx);
				}

				int hash = hashUtil.hashCode(keys, keyCount);
				if (groups[hash] == null) {
					groups[hash] = new ListBase1(INIT_GROUPSIZE);
					groups[hash].add(keys);
				} else {
					int index = HashUtil.bsearch_a(groups[hash], keys, keyCount);
					if (index < 1) {
						groups[hash].add(-index, keys);
					} else {
						groups[hash].add(index, keys);
					}
				}
			}
		} finally {
			stack.pop();
		}
		
		ListBase1 mems1 = seq1.getMems();
		int len1 = mems1.size();
		Sequence result = new Sequence(len1);
		
		current = seq1.new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= len1; ++i) {
				Object []keys = new Object[keyCount];
				current.setCurrent(i);
				for (int c = 0; c < keyCount; ++c) {
					keys[c] = exps1[c].calculate(ctx);
				}
	
				int hash = hashUtil.hashCode(keys, keyCount);
				if (groups[hash] != null) {
					int index = HashUtil.bsearch_a(groups[hash], keys, keyCount);
					if (index > 0) {
						result.add(mems1.get(i));
						groups[hash].remove(index);
					}
				}
			}
		} finally {
			stack.pop();
		}
		
		result.trimToSize();
		return result;
	}

	private static void executeBatchPst(Sequence srcSeries, PreparedStatement pst, ArrayList<Expression> exps,
			ArrayList<Byte> expTypes, Context ctx, DBSession dbs, String dbCharset, boolean tranSQL, int dbType,
			String name) {
		Expression[] expParams = new Expression[exps.size()];
		exps.toArray(expParams);
		executePst(srcSeries, pst, expParams, toByteArray(expTypes), ctx, dbs, dbCharset, tranSQL, dbType, name);
	}

	private static void executeBatchSql(Sequence srcSeries, String sql, ArrayList<Expression> exps,
			ArrayList<Byte> expTypes, Context ctx, DBSession dbs, Connection con, String dbCharset, boolean tranSQL,
			int dbType, String dbn, int batchSize) {
		Expression[] expParams = new Expression[exps.size()];
		exps.toArray(expParams);
		execute(srcSeries, sql, expParams, toByteArray(expTypes), ctx, dbs, con, dbCharset, tranSQL, dbType, dbn,
				batchSize);
	}
	
	public static Sequence query(Sequence srcSeries, String sql, Expression[] params, byte[] types, String opt,
			Context ctx, DBSession dbs) {
		return query(srcSeries, sql, params, types, opt, ctx, dbs, null, null, 0);
	}

	/* ������÷�������DBObjectŲ�����ģ� xq 2015.4.21 */
	private static Sequence query(Sequence srcSeries, String sql, Expression[] params, byte[] types, String opt,
			Context ctx, DBSession dbs, String[] nullSqls, ArrayList<Integer> nullKeys, int keysize) {
		if (srcSeries == null || srcSeries.length() == 0 || params == null || params.length == 0) {
			return query(sql, null, null, opt, ctx, dbs);
		}

		int pCount = params.length;
		// edited by bd, 2022.4.15, keyStart��¼�ӵڼ���λ�����Ѿ��Ǽ�ֵ�ˡ�
		int keyStart = pCount - keysize;
		int len = srcSeries.length();
		//Object[][] valueGroup = new Object[len][pCount];
		// edited by bd, 2022.4.15, �����ҪӦ�Կ�ֵ��������ĸ����ǲ�����
		ArrayList<ArrayList<Object>> vgs = new ArrayList<ArrayList<Object>>(len);

		ComputeStack stack = ctx.getComputeStack();
		Sequence.Current current = srcSeries.new Current();
		stack.push(current);
		
		int nulls = nullSqls == null ? 0 : nullSqls.length;
		boolean nkeys = nullKeys != null && nullKeys.size() > 0 && nulls > 0;
		ArrayList<ArrayList<ArrayList<Object>>> nullParams = null;
		// res �������μ�¼�Ĳ�ѯ�����Դ��0��������nullֵ�Ĳ�ѯ��������ӦnullParams�еĳ�Աλ��+1
		int[] res = new int[len];
		if (nkeys) {
			nullParams = new ArrayList<ArrayList<ArrayList<Object>>>(nulls);
			for (int i = 0; i < nulls; i++)
				nullParams.add(new ArrayList<ArrayList<Object>>());
		}

		try {
			for (int i = 1; i <= len; ++i) {
				current.setCurrent(i);
				/*
				Object[] paramValues = new Object[pCount];
				valueGroup[i - 1] = paramValues;
				for (int p = 0; p < pCount; ++p) {
					if (params[p] != null)
						paramValues[p] = params[p].calculate(ctx);
				}
				*/
				ArrayList<Object> pValues = new ArrayList<Object>();
				int nloc = 0;
				for (int p = 0; p < pCount; ++p) {
					if (params[p] != null) {
						Object pv = params[p].calculate(ctx);
						if (nkeys && p >= keyStart && pv == null) {
							int kloc = nullKeys.indexOf(p-keyStart);
							nloc = nloc + (1<<kloc);
							// ����ĳ���is null�жϣ���Ӧ�Ĳ�����������
							// ���ǵ��裬��Ȼֱ��ȱʧ�Ļ��������б仯���޷���ԭֵ�Ƚ�
						}
						pValues.add(pv);
					}
				}
				if (nloc > 0) {
					nloc = nulls - nloc;
					nullParams.get(nloc).add(pValues);
					res[i-1] = nloc+1;
				}
				else {
					vgs.add(pValues);
				}
			}
		} finally {
			stack.pop();
		}

		Object[][] valueGroup = toGroup(vgs);
		Table tbl = DatabaseUtil.queryGroup(sql, valueGroup, types, dbs, opt, ctx);

		// edited by bd, 2022.4.15, ���������������Ϊ�˸���db.update�ģ�����@iѡ���ǲ����õģ�������query@i���ַ��س�һ�е����
		/*
		if (tbl != null && tbl.dataStruct().getFieldCount() == 1 && opt != null && opt.indexOf('i') != -1) {
			return tbl.fieldValues(0);
		} else {
			return tbl;
		}
		*/

		// ���compSeq�а�����ֵ��
		if (nkeys) {
			ArrayList<Table> nTbls = new ArrayList<Table>(nulls);
			Table tbli = null;
			for (int i = 0; i < nulls; i++) {
				ArrayList<ArrayList<Object>> nullParam = nullParams.get(i);
				valueGroup = toGroup(nullParam, keyStart);
				if (valueGroup != null) {
					//sql = nullSqls[i];
					// ��ѯʱ�����ڿ�ֵ��������˲����ò���������
					//tbli = DatabaseUtil.queryGroup(sql, valueGroup, null, dbs, opt, ctx);
					tbli = DatabaseUtil.queryGroup(nullSqls[i], valueGroup, null, dbs, opt, ctx);
				}
				// edited by bd, 2022.12.9, �Զ��жϿ�ֵʱ��is null��Ӧ��sql�в���Ҫ�����Ŀ�����
				else if (nullSqls[i].indexOf("?")<0) {
					valueGroup = new Object[1][];
					valueGroup[0] = null;
					tbli = DatabaseUtil.queryGroup(nullSqls[i], valueGroup, null, dbs, opt, ctx);
				}
				nTbls.add(tbli);
			}
			Sequence result = new Sequence(len);
			int[] locs = new int[nulls+1];
			Object o = null;
			for (int i = 0; i < len; i++) {
				int ri = res[i];
				locs[ri] = locs[ri]+1;
				if (ri <= 0) {
					o = tbl.get(locs[ri]);
				}
				else {
					//o = nTbls.get(ri-1).get(locs[ri]);
					// edited by bd, 2022.12.9, �Զ��жϿ�ֵʱ����ֹnTbls���п���δ��¼����
					Table ot = nTbls.get(ri-1);
					o = ot == null ? null : ot.get(locs[ri]);
				}
				result.add(o);
			}
			return result;
		}
		else {
			// û�п�ֵ����ֱ�ӷ��ؾ�����
			return tbl;
		}
	}
	
	private static Object[][] toGroup(ArrayList<ArrayList<Object>> lvalues) {
		int len = lvalues.size();
		if (len < 1) return null;
		ArrayList<Object> o1 = lvalues.get(0);
		int len2 = o1.size();
		if (len2 < 1) return null;
		Object[][] result = new Object[len][len2];
		for (int i = 0; i < len; i++) {
			o1 = lvalues.get(i);
			for (int j = 0; j < len2; j++) {
				result[i][j] = o1.get(j);
			}
		}
		return result;
	}
	
	private static Object[][] toGroup(ArrayList<ArrayList<Object>> lvalues, int keyStart) {
		int len = lvalues.size();
		if (len < 1) return null;
		ArrayList<Object> o1 = lvalues.get(0);
		int len2 = o1.size();
		int len22 = len2;
		for (int i = keyStart; i < len2; i++) {
			Object o = o1.get(i);
			if (o == null) len22--;
		}
		if (len22 < 1) return null;
		Object[][] result = new Object[len][len22];
		for (int i = 0; i < len; i++) {
			o1 = lvalues.get(i);
			int col = 0;
			for (int j = 0; j < len2; j++) {
				Object o = o1.get(j);
				if (o!= null) {
					result[i][col] = o;
					col ++;
				}
			}
		}
		return result;
	}

	/* ����÷����� xq 2015.4.21 */
	public static Sequence query(String sql, Object[] params, byte[] types, String opt, Context ctx, DBSession dbs) {
		// DBSession dbs = getDbSession();
		// edited by bdl, 2015.7.28, ֧��@iѡ�����ʱ��������
		return DatabaseUtil.query(sql, params, types, dbs, opt, ctx);
	}

	/* �����һ������executeһ���������sql�������� xq 2015.4.21 */
	public static void execute(Sequence srcSeries, String sql, Expression[] params, byte[] types, Context ctx,
			DBSession dbs) {// String opt,
		if (srcSeries == null)
			return;
		int paramCount = params == null ? 0 : params.length;
		int len = srcSeries.length();
		Object[][] valueGroup = new Object[len][paramCount];

		ComputeStack stack = ctx.getComputeStack();
		Sequence.Current current = srcSeries.new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= len; ++i) {
				current.setCurrent(i);
				Object[] paramValues = new Object[paramCount];
				valueGroup[i - 1] = paramValues;

				for (int p = 0; p < paramCount; ++p) {
					if (params[p] != null)
						paramValues[p] = params[p].calculate(ctx);
				}
			}
		} finally {
			stack.pop();
		}

		DatabaseUtil.execute2(sql, valueGroup, types, dbs, true);
	}

	/**
	 * ����ִ�У�����ĳ�����У����������ִ��ĳ��sql
	 * @param sql	String sql���
	 * @param params	Expression[] ���ֶβ����ı��ʽ
	 * @param types	byte[] ���������б��ɿգ��������Ͳμ�com.scudata.common.Types����������
	 *            	���ַ���������͡����������Ϳ�ʱ����Ϊ��ͬ�ڡ�Ĭ�ϡ����ͣ���ʱע�����ֵ����Ϊnull
	 * @param ctx	Context �����ģ���������Ĳ�����
	 * @param dbs	DBSession ���ݿ���Ϣ����¼����״̬��
	 * @param con	Connection ���ݿ����Ӷ���
	 * @param dbCharset	���ݿ���룬���ڽ��ַ�������ת��
	 * @param tranSQL	�Ƿ���Ҫת�룬ֻ��Ϊtrueʱ����
	 * @param dbType	���ݿ����ͣ�����ĳЩ���ݿ����趨����ʱ������Ҫ�������
	 * @param dbn	���ݿ����ƣ����ڴ�����ʾ
	 * @param batchSize	int ��������ֵ�����ϲ���
	 */
	private static void execute(Sequence srcSeries, String sql, Expression[] params, byte[] types, Context ctx,
			DBSession dbs, Connection con, String dbCharset, boolean tranSQL, int dbType, String dbn, int batchSize) {// String
																														// opt,
		if (srcSeries == null)
			return;
		int paramCount = params == null ? 0 : params.length;
		int len = srcSeries.length();
		Object[][] valueGroup = new Object[len][paramCount];

		ComputeStack stack = ctx.getComputeStack();
		Sequence.Current current = srcSeries.new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= len; ++i) {
				current.setCurrent(i);
				Object[] paramValues = new Object[paramCount];
				valueGroup[i - 1] = paramValues;

				for (int p = 0; p < paramCount; ++p) {
					if (params[p] != null)
						paramValues[p] = params[p].calculate(ctx);
				}
			}
		} finally {
			stack.pop();
		}

		executeBatch(sql, valueGroup, types, dbs, con, dbCharset, tranSQL, dbType, dbn, batchSize, true);
	}

	/**
	 * ���һ��cursor������ִ��sql����ʱҪ��һ��ȫ��ִ�У�������ָ��fetch 2016.4.19
	 * @param cs	�α�
	 * @param sql	sql���
	 * @param params	ʹ�ò����������α��¼�ı��ʽ
	 * @param types	�������ͣ�������Ϊnullʱ��ʹ��
	 * @param ctx	������
	 * @param dbs	����Դ�趨
	 */
	public static void execute(ICursor cs, String sql, Expression[] params, byte[] types, Context ctx, DBSession dbs) {
		PreparedStatement pst = null;
		Connection con = null;
		String dbCharset = null;
		String toCharset = null;
		boolean tranSQL = false;
		boolean tranContent = true;
		int dbType = DBTypes.UNKNOWN;
		String name = "";
		DBInfo info = dbs.getInfo();
		if (info != null) {
			name = info.getName();
		}
		int batchSize = 1000;

		try {
			DBConfig dsConfig = null;
			MessageManager mm = DataSetMessage.get();
			if (dbs != null && dbs.getInfo() instanceof DBConfig) {
				dsConfig = (DBConfig) dbs.getInfo();
			}
			if (dbs != null) {
				Object session = dbs.getSession();
				if (session instanceof Connection) {
					con = (Connection) session;
				}
			}

			if (con == null || con.isClosed()) {
				throw new RQException(mm.getMessage("error.conClosed", name));
			}

			if (dsConfig != null) {
				batchSize = dsConfig.getBatchSize();
				if (batchSize < 1) {
					batchSize = 1;
				}
				dbCharset = dsConfig.getDBCharset();
				tranSQL = dsConfig.getNeedTranSentence();
				tranContent = dsConfig.getNeedTranContent();
				if ((tranContent || tranSQL) && dbCharset == null) {
					throw new RQException(mm.getMessage("error.fromCharset", name));
				}

				toCharset = dsConfig.getClientCharset();
				if ((tranContent || tranSQL) && toCharset == null) {
					throw new RQException(mm.getMessage("error.toCharset", name));
				}

				dbType = dsConfig.getDBType();
			} else {
				tranContent = false;
			}

			if (tranSQL) {
				sql = new String(sql.getBytes(), dbCharset);
			}

			try {
				pst = con.prepareStatement(sql);
			} catch (SQLException e) {
				if (dbs.getErrorMode()) {
					dbs.setError(e);
				} else {
					throw new RQException(mm.getMessage("error.sqlException", name, sql) + " : " + e.getMessage(), e);
				}
			}
			while (true) {
				Sequence fetchSeq = cs.fetch(batchSize);
				if (fetchSeq == null || fetchSeq.length() == 0)
					break;
				DatabaseUtil.executePst(fetchSeq, pst, params, types, ctx, dbs, dbCharset, tranSQL, dbType, name);
			}
		} catch (RQException re) {
			throw re;
		} catch (Exception e) {
			throw new RQException(e.getMessage(), e);
		} finally {
			try {
				if (pst != null) {
					pst.close();
				}
			} catch (Exception e) {
				throw new RQException(e.getMessage(), e);
			}
		}
	}

	private static void executePst(Sequence srcSeries, PreparedStatement pst, Expression[] params, byte[] types,
			Context ctx, DBSession dbs, String dbCharset, boolean tranSQL, int dbType, String name) {
		if (srcSeries == null)
			return;
		int paramCount = params == null ? 0 : params.length;
		int len = srcSeries.length();
		Object[][] valueGroup = new Object[len][paramCount];

		ComputeStack stack = ctx.getComputeStack();
		Sequence.Current current = srcSeries.new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= len; ++i) {
				current.setCurrent(i);
				Object[] paramValues = new Object[paramCount];
				valueGroup[i - 1] = paramValues;

				for (int p = 0; p < paramCount; ++p) {
					if (params[p] != null)
						paramValues[p] = params[p].calculate(ctx);
				}
			}
		} finally {
			stack.pop();
		}

		executeBatch(pst, valueGroup, types, dbs, dbCharset, tranSQL, dbType, name, true);
	}
	
	/**
	 * ��������autoDB�������ڸ�������dfxʱ�����Ӻ��ͷŶ���õ��Զ���������Դ
	 * @param ctx
	 * @param startDsNames
	 */
	public static void connectAutoDBs(Context ctx, List<String> startDsNames) {
		try {
			if (startDsNames != null) {
				for (int i = 0; i < startDsNames.size(); i++) {
					String dsName = (String) startDsNames.get(i);
					ISessionFactory isf = Env.getDBSessionFactory(dsName);
					if (isf != null){
						ctx.setDBSession(dsName, isf.getSession());
						Logger.debug(dsName+ " is auto connected.");
					}
				}
			}
		} catch (Throwable x) {
		}
	}

	/**
	 * �Զ��ر�����
	 * @param ctx
	 */
	public static void closeAutoDBs(Context ctx) {
		if(ctx==null){
			return;
		}
		Map<String, DBSession> map = ctx.getDBSessionMap();
		if (map != null) {
			Iterator<String> iter = map.keySet().iterator();
			while (iter.hasNext()) {
				String name = iter.next().toString();
				DBSession sess = ctx.getDBSession(name);
				if (sess == null || sess.isClosed())
					continue;
				Object o = ctx.getDBSession(name).getSession();
				if (o != null && o instanceof java.sql.Connection) {
					try {
						((java.sql.Connection) o).close();
						Logger.debug(name+ " is auto closed.");
					} catch (Exception e) {
						Logger.warn(e.getMessage(), e);
					}
				}
			}
		}
	}
	
}
