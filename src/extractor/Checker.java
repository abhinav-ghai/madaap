package extractor;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class Checker {
	private Connection mysqlconn;
	public static void main(String[] args){
		Checker checker = new Checker();
		checker.checkAll(checker.getURLlist());
	}
	
	ResultSet getURLlist(){
		mysqlconn = Madaap.getMySQLconnection();
		Statement getAllURL = null;
		try {
			getAllURL = mysqlconn.createStatement();
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		ResultSet URLlist = null;
		try {
			URLlist = getAllURL.executeQuery("Select URL from url WHERE Status != 2");
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return URLlist;
	}
	
	void checkAll(ResultSet set){

		try {
			while(set.next()){
				URL url = new URL(set.getString(1));
				if (!isURLActive(url)){
					PreparedStatement updateStatus = mysqlconn.prepareStatement("UPDATE url SET Status = ? WHERE URL = ?");
					updateStatus.setInt(1, 3);
					updateStatus.setString(2, url.toString());
					updateStatus.executeUpdate();
				}
				//System.out.println("URL: "+url.toString());
				
			}
			mysqlconn.close();
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (MalformedURLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	boolean isURLActive(URL url){
		URLConnection connection = null;
		try {
			connection = url.openConnection();
			int code = ((HttpURLConnection)connection).getResponseCode();
			//System.out.println("Response code: " + code);
			if (code>=400){
				return false;
			}
		} catch (Exception e) {
			return false;
		}
		finally{
			if (connection!=null){
				((HttpURLConnection)connection).disconnect();	
			}
		}
		return true;
	}
}
