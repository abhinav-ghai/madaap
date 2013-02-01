package extractor;
import gate.Gate;
import gate.creole.ANNIEConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URI;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;

import com.sun.jndi.toolkit.url.Uri;

import collectURL.Delicious;
import collectURL.Facebook;
import collectURL.Gplus;
import collectURL.ManualFeeder;
import collectURL.Twitter;


public class Madaap {

	/**
	 * Starting point of application
	 * @param args
	 * @throws Exception
	 * 
	 */
	public static void main(String[] args) throws Exception{
		
		/*Set to your Gate installation home*/
		XMLConfiguration config = new XMLConfiguration("madaap.xml");
		Gate.setGateHome(new File(config.getString("gate.home")));
		
		Gate.init();
		
		Gate.getCreoleRegister().registerDirectories(new File(Gate.getPluginsHome(), ANNIEConstants.PLUGIN_DIR).toURI().toURL());
		
		/*Set the path to \Plugins\Tools directory*/
		Gate.getCreoleRegister().registerDirectories(new File(config.getString("gate.home") + "\\plugins\\Tools").toURI().toURL()); 
		BlockingQueue<URL> queue = new LinkedBlockingQueue<URL>();
		TimerTask manualFeederTask = new ManualFeeder(queue);
		TimerTask twitterTask = new Twitter(queue);
		Extractor e = new Extractor(queue);
		Timer timer = new Timer();
		long twitterTime = Long.parseLong(config.getString("timer.twitter"))*1000*60;//Unit of twitterTime: minutes
		//timer.scheduleAtFixedRate(twitterTask, 0, twitterTime);
		long manualFeederTime = Long.parseLong(config.getString("timer.ManualFeederTimer"))*1000*60*60;//Unit of ManualFeederTime: hour
		timer.scheduleAtFixedRate(manualFeederTask, 0, manualFeederTime);
		//timer.scheduleAtFixedRate(twitterTask, 0, twitterTime);
	}
	
	static Connection getMySQLconnection() {
		Connection mysqlconn = null;
		try{
			Class.forName("com.mysql.jdbc.Driver").newInstance();	
		}
		catch(Exception e){
			System.out.println(e.getMessage() +"\nCannot register MySQL to DriverManager");
		}
		try{
			XMLConfiguration config = new XMLConfiguration("madaap.xml");
			mysqlconn = DriverManager.getConnection("jdbc:mysql:"+config.getString("database.url"),"root","");
		}
		catch(SQLException e){
			System.out.println(e.getMessage() + "\nCannot connect to MySQL. Check if MySQL is running.");
			System.exit(0);
		} catch (ConfigurationException e) {
			e.printStackTrace();
		}
		System.out.println("connection to mysql made");
		return mysqlconn;
	}
	
}
