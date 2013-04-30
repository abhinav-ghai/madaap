package extractor;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.TimerTask;
/**
 * Check validity of all URL stored in table tab_URL of madaap database
 * Use HTTP response codes to check validity
 * @author abhinav
 *
 */
public class Checker extends TimerTask implements Runnable {
	private Connection mysqlconn;
	static final int FROM_EXTRACTOR = 0;
	static final int REVIEWED_GOOD = 1;
	static final int REVIEWED_BAD = 2;
	static final int INACTIVE_UNREVIEWED = 3;
	static final int INACTIVE_REVIEWED = 4;
	
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
			URLlist = getAllURL.executeQuery("Select URL,Status from tab_url WHERE Status != " + REVIEWED_BAD);
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return URLlist;
	}
	
	void checkAll(ResultSet set){

		try {
			while(set.next()){
				URL url = new URL(set.getString("URL"));
				int status = set.getInt("Status");
				PreparedStatement updateStatus = mysqlconn.prepareStatement("UPDATE tab_url SET Status = ? WHERE URL = ?");
				updateStatus.setString(2, url.toString());
				
				switch(status){
				case FROM_EXTRACTOR:
					if (!isURLActive(url)){
						updateStatus.setInt(1, INACTIVE_UNREVIEWED);
						updateStatus.executeUpdate();
					}
					break;
				case REVIEWED_GOOD:
					if (!isURLActive(url)){
						updateStatus.setInt(1, INACTIVE_REVIEWED);
						updateStatus.executeUpdate();
					}
					break;
				case INACTIVE_UNREVIEWED:
					if (isURLActive(url)){
						updateStatus.setInt(1, FROM_EXTRACTOR);
						updateStatus.executeUpdate();
					}
					break;
				case INACTIVE_REVIEWED:
					if (isURLActive(url)){
						updateStatus.setInt(1, FROM_EXTRACTOR);
						updateStatus.executeUpdate();
					}
					break;
				default:
					break;
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

	@Override
	public void run() {
		final long start = System.currentTimeMillis();
		Checker checker = new Checker();
		System.out.println("Checking URL...");
		checker.checkAll(checker.getURLlist());
		final long end = System.currentTimeMillis();
		System.out.println((end - start)/1000.0 + " seconds for checker");
	}
}
