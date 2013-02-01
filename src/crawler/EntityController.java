package crawler;

import java.util.ArrayList;
import java.util.List;

import edu.uci.ics.crawler4j.crawler.CrawlConfig;
import edu.uci.ics.crawler4j.crawler.CrawlController;
import edu.uci.ics.crawler4j.fetcher.PageFetcher;
import edu.uci.ics.crawler4j.robotstxt.RobotstxtConfig;
import edu.uci.ics.crawler4j.robotstxt.RobotstxtServer;

public class EntityController {
	
	public static void begin(String url) throws Exception{
		String crawlStorageFolder = "Depth2/";
        int numberOfCrawlers = 4;

        CrawlConfig config = new CrawlConfig();
        config.setCrawlStorageFolder(crawlStorageFolder);
        config.setMaxDepthOfCrawling(2);

        /*
         * Instantiate the controller for this crawl.
         */
        PageFetcher pageFetcher = new PageFetcher(config);
        RobotstxtConfig robotstxtConfig = new RobotstxtConfig();
        RobotstxtServer robotstxtServer = new RobotstxtServer(robotstxtConfig, pageFetcher);
        CrawlController controller = new CrawlController(config, pageFetcher, robotstxtServer);
        
        /*
         * For each crawl, you need to add some seed urls. These are the first
         * URLs that are fetched and then the crawler starts following links
         * which are found in these pages
         */
        List<String> domains = new ArrayList<String>();
        controller.addSeed(url);
        int position = url.length();
        for (int i = position-1; i>=0 ;i--){
        	if (url.charAt(i)=='/'){
        		position = i;
        		break;
        	}
        }
        if (position == 0){
        	domains.add(url);
        }
        else{
        	domains.add(url.substring(0, position+1));
        }
        System.out.println("seed: " + url);
        System.out.println("domain: " + url.substring(0, position+1));
    	controller.setCustomData((List<String>)domains);

        /*
         * Start the crawl. This is a blocking operation, meaning that your code
         * will reach the line after this only when crawling is finished.
         */
        controller.start(EntityCrawler.class, numberOfCrawlers);
        System.out.println("crawling done");

	}
}
