package crawler;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Pattern;

import edu.uci.ics.crawler4j.crawler.Page;
import edu.uci.ics.crawler4j.crawler.WebCrawler;
import edu.uci.ics.crawler4j.url.WebURL;
import extractor.Extractor;


public class EntityCrawler extends WebCrawler{

	/**
	 * @param args
	 */
	private final static Pattern FILTERS = Pattern.compile(".*(\\.(css|js|bmp|gif|jpe?g" 
            + "|png|tiff?|mid|mp2|mp3|mp4"
            + "|wav|avi|mov|mpeg|ram|m4v|pdf" 
            + "|rm|smil|wmv|swf|wma|zip|rar|gz))$");
	@Override
	public boolean shouldVisit(WebURL url){
		return true;
		/*
		List<String> domainsToCrawl = (List<String>) this.getMyController().getCustomData();
		String href = url.getURL().toLowerCase();
		if (FILTERS.matcher(href).matches()) {
		      return false;
		   }
		for(String domain : domainsToCrawl){
		      if (href.startsWith(domain)) {
		    	  System.out.println("visiting: " + href);
		         return true;
		      }
		   }
		System.out.println("temp visiting: " + href);
        return false;*/
	}
	@Override
	public void visit(Page page){
		String url = page.getWebURL().getURL();
		System.out.println("send to annotate: " + url);
        try {
			Extractor.getEntities(new URL(url), false);
		} catch (MalformedURLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
