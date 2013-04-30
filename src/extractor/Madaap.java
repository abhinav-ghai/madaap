package extractor;
import gate.Gate;
import gate.creole.ANNIEConstants;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;

import collectURL.ManualFeeder;
import collectURL.Twitter;


public class Madaap {

	/**
	 * Starting point of application
	 * @param args
	 * @throws Exception
	 * 
	 */
	static final int ONE_MILLSEC = 1;
	static final int ONE_SEC = 1000*ONE_MILLSEC;
	static final int ONE_MIN = 60*ONE_SEC;
	static final int ONE_HOUR = 60*ONE_MIN;
	static final int ONE_DAY = 24*ONE_HOUR;
	
	
	public static void main(String[] args) throws Exception{
		
		/*Set to your Gate installation home*/
		XMLConfiguration config = new XMLConfiguration("config/madaap.xml");
		Gate.setGateHome(new File(config.getString("gate.home")));
		
		Gate.init();
		
		Gate.getCreoleRegister().registerDirectories(new File(Gate.getPluginsHome(), ANNIEConstants.PLUGIN_DIR).toURI().toURL());
		
		/*Set the path to \Plugins\Tools directory*/
		Gate.getCreoleRegister().registerDirectories(new File(config.getString("gate.home") + "\\plugins\\Tools").toURI().toURL()); 
		
		/*Declare queue to receive URL from various collectors*/
		BlockingQueue<URL> queue = new LinkedBlockingQueue<URL>();
		
		/*Start extractor*/
		Extractor e = new Extractor(queue);
		
		/*Timer to schedule collector tasks at regular intervals*/
		Timer timer = new Timer();
		
		System.out.println("timer is " + config.getString("timer.ManualFeederInterval"));
		/*Collect URL from /input/url.txt*/
		TimerTask manualFeederTask = new ManualFeeder(queue);
		long manualFeederTime = Long.parseLong(config.getString("timer.ManualFeederInterval"))*ONE_HOUR;//Unit of ManualFeederTime: hour
		timer.scheduleAtFixedRate(manualFeederTask, 0, manualFeederTime);
		
		/*Collect URL from twitter feed*/
		
		TimerTask twitterTask = new Twitter(queue);
		long twitterTime = Long.parseLong(config.getString("timer.TwitterInterval"))*ONE_MIN;//Unit of twitterTime: minutes
		timer.scheduleAtFixedRate(twitterTask, 0, twitterTime);
		
		
		/*Check all URL if they are active or not*/
		TimerTask checkerTask = new Checker();
		long checkerTime = Long.parseLong(config.getString("timer.CheckerInterval"))*ONE_HOUR;//Unit of CheckerTime: hour
		timer.scheduleAtFixedRate(checkerTask, 0, checkerTime);
	}
	
	/**
	 * Get a connection to MySQL database named in madaap.xml file
	 * @return
	 */
	static Connection getMySQLconnection() {
		Connection mysqlconn = null;
		try{
			Class.forName("com.mysql.jdbc.Driver").newInstance();	
		}
		catch(Exception e){
			System.out.println(e.getMessage() +"\nCannot register MySQL to DriverManager");
		}
		try{
			XMLConfiguration config = new XMLConfiguration("config/madaap.xml");
			Properties connectionProp = new Properties();
			connectionProp.put("user",config.getString("database.username"));
			connectionProp.put("password", config.getString("database.password"));
			mysqlconn = DriverManager.getConnection("jdbc:mysql"+"://"+config.getString("database.url")+":"+config.getString("database.port")+"/madaap",connectionProp);
		}
		catch(SQLException e){
			System.out.println(e.getMessage() + "\nCannot connect to MySQL. Check if MySQL is running.");
			System.exit(0);
		} catch (ConfigurationException e) {
			e.printStackTrace();
		}
		System.out.println("Connection made to MySQL");
		return mysqlconn;
	}
	
}
