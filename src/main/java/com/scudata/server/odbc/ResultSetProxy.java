package com.scudata.server.odbc;

import com.scudata.dm.*;
import com.scudata.dm.Record;
import com.scudata.dm.cursor.ICursor;
import com.scudata.server.IProxy;

/**
 * ���������
 * @author Joancy
 *
 */
public class ResultSetProxy extends IProxy {
	ICursor cs;
	String[] columns=null;
	Sequence row = null;
	
	/**
	 * �������������
	 * @param sp Statement����
	 * @param id �����
	 * @param cs �α�
	 */
	public ResultSetProxy(StatementProxy sp, int id, ICursor cs) {
		super(sp, id);
		this.cs = cs;
		access();
	}

	/**
	 * ��ȡ�α����
	 * @return �α�
	 */
	public ICursor getCursor() {
		return cs;
	}

	/**
	 * ��ȡ�ֶ���
	 * @return �ֶ�������
	 */
	public String[] getColumns(){
		if(columns==null){
			row = fetch(1);
			if(row==null){
				DataStruct ds = cs.getDataStruct();
				if(ds==null){
					return null;
				}
				columns = ds.getFieldNames();
			}else{
				if(row instanceof Table){
					columns = row.dataStruct().getFieldNames();
				}else{
					Object tmp = row.get(1);
					if(tmp instanceof Record){
						Record rec = (Record)tmp;
						columns = rec.getFieldNames();
					}
				}
			}
			
			if(columns==null){
				columns = new String[]{"_1"};
			}
		}
		return columns;
	}

	/**
	 * ȡ��
	 * @param n ȡ������
	 * @return �������
	 */
	public Sequence fetch(int n) {
		Sequence tmp;
		if(row!=null){
			Object val = row.get(1);
			tmp = cs.fetch(n-1);
			if(tmp==null){//���������պ�ֻ��һ�м�¼ʱ
				tmp = row;
			}else{
				tmp.insert(1, val);
			}
			row = null;
		}else{
			tmp = cs.fetch(n);
		}
		access();
		return tmp;
	}

	/**
	 * ʵ��toString�ӿ�
	 */
	public String toString() {
		return "ResultSet "+getId();
	}
	
	/**
	 * �رյ�ǰ������
	 */
	public void close(){
		cs.close();
	}
}
