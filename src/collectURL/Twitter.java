package collectURL;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONObject;

public class Twitter extends TimerTask implements Runnable{
	private String since_id;
	private final BlockingQueue<URL> queue;
	public Twitter(BlockingQueue<URL> q){
		queue = q;
	}
	private void collect() throws Exception{
		XMLConfiguration config = new XMLConfiguration("config/twitter.xml");
		System.out.println("Twitter collector launched");
		HttpClient twitterClient = new DefaultHttpClient();
		List<NameValuePair> qparams = new ArrayList<NameValuePair>();
		qparams.add(new BasicNameValuePair("q", config.getString("query.q")));
		qparams.add(new BasicNameValuePair("rpp", config.getString("query.rpp")));
		qparams.add(new BasicNameValuePair("page", config.getString("query.page")));
		qparams.add(new BasicNameValuePair("lang", config.getString("query.lang")));
		qparams.add(new BasicNameValuePair("include_entities", "true"));
		System.out.println("Since ID value: "+since_id);
		qparams.add(new BasicNameValuePair("since_id", since_id));
		URI uri = URIUtils.createURI("http", "search.twitter.com", -1, "/search.json", 
		    URLEncodedUtils.format(qparams, "UTF-8"), null);
		HttpGet httpget = new HttpGet(uri);
		HttpResponse response = twitterClient.execute(httpget);
		HttpEntity entity = response.getEntity();
		JSONArray urls = null;
		if (entity!=null){
			InputStream stream = entity.getContent();
			String twitterOutput = IOUtils.toString(stream);
			JSONObject twitterJson = new JSONObject(twitterOutput);
			JSONArray resultArray = twitterJson.getJSONArray("results");
			//queue.put(new URL("https://plus.google.com/109504632519300573123/posts"));
			since_id = twitterJson.get("max_id").toString();
			for(int i=0;i<resultArray.length();i++){
				urls = resultArray.getJSONObject(i).getJSONObject("entities").getJSONArray("urls");
				for (int j=0;j<urls.length();j++){
					if (urls.get(j).toString()!=""){
						URL currentURL = new URL(urls.getJSONObject(j).get("expanded_url").toString());
						URL expandedURL = expandURL(currentURL);
						//System.out.println("current: "+currentURL.toString() + "\nexpanded: " + expandedURL);
						queue.put(expandedURL);
					}
					else
						System.out.println("empty url");
				}
			}
		}
	}
	
	/*Expand shortened URL by getting Header field
	 * Visible across package collectURL*/
	URL expandURL(URL url){
		HttpURLConnection connection = null;
		try{
			connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(20000); //20 second timeout
			connection.setInstanceFollowRedirects(false);
			connection.connect();
			String header = connection.getHeaderField("Location");
			if (header!=null){
				URL expandedURL = new URL(header);
				return expandedURL;
			}
		}
		catch (Throwable e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		finally{
			if (connection!=null){
				((HttpURLConnection)connection).disconnect();
			}
		}
		return url;
	}
	@Override
	public void run() {
		try {
			collect();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
}
