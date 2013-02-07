package extractor;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

import gate.Annotation;
import gate.AnnotationSet;
import gate.Corpus;
import gate.Document;
import gate.DocumentContent;
import gate.Factory;
import gate.FeatureMap;
import gate.creole.ANNIETransducer;
import gate.creole.ExecutionException;
import gate.creole.POSTagger;
import gate.creole.ResourceInstantiationException;
import gate.creole.SerialAnalyserController;
import gate.creole.annotdelete.AnnotationDeletePR;
import gate.creole.gazetteer.DefaultGazetteer;
import gate.creole.morph.Morph;
import gate.creole.orthomatcher.OrthoMatcher;
import gate.creole.splitter.SentenceSplitter;
import gate.creole.tokeniser.DefaultTokeniser;
import gate.util.InvalidOffsetException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;

import crawler.EntityController;


/**
 * Contains methods to perform annotations and extract few entities like author,title etc from a given URL
 * and store them in a MySQL database.
 * It consumes URLs from a Queue.
 * @author abhinav
 *
 */
public class Extractor implements Runnable{
	private static AnnotationDeletePR deletor;
	private static DefaultTokeniser tokeniser;
	private static DefaultGazetteer gazetteer;
	private static SentenceSplitter splitter;
	private static POSTagger tagger;
	private static ANNIETransducer transducer;
	private static OrthoMatcher matcher;
	private static Morph morpher;
	private static SerialAnalyserController controller;
	private static FeatureMap map = Factory.newFeatureMap();
	private static FeatureMap pub = Factory.newFeatureMap();
	private static FeatureMap mapAbstract = Factory.newFeatureMap();
	private static Corpus corpus;
	static{
		try {
			corpus = Factory.newCorpus("corpus madaap");
		} catch (ResourceInstantiationException e) {
			System.out.println("Could not create corpus");
			e.printStackTrace();
		}
	}
	
	static{
		try{
			deletor = (AnnotationDeletePR)Factory.createResource("gate.creole.annotdelete.AnnotationDeletePR");
			tokeniser = (DefaultTokeniser) Factory.createResource("gate.creole.tokeniser.DefaultTokeniser");
			gazetteer = (DefaultGazetteer) Factory.createResource("gate.creole.gazetteer.DefaultGazetteer");
			splitter = (SentenceSplitter) Factory.createResource("gate.creole.splitter.SentenceSplitter"); 
			tagger = (POSTagger) Factory.createResource("gate.creole.POSTagger");
			transducer = (ANNIETransducer) Factory.createResource("gate.creole.ANNIETransducer");
			matcher = (OrthoMatcher) Factory.createResource("gate.creole.orthomatcher.OrthoMatcher");
			morpher = (Morph) Factory.createResource("gate.creole.morph.Morph");
			controller = (SerialAnalyserController) Factory.createResource("gate.creole.SerialAnalyserController");
		}
		catch(ResourceInstantiationException e){
			e.printStackTrace();
			System.out.println("Couldn't initialize extractor");
		}
	}
	private final BlockingQueue<URL> queue;
	/**
	 * 
	 * @param url
	 */
	public Extractor(BlockingQueue<URL> q){
		queue = q;
		new Thread(this).start();
	}
	

	public static int getEntities(URL url,boolean fromQueue){
				/*Create a connection to MySQL database*/
				Connection mysqlconn = Madaap.getMySQLconnection();
				
				/*Check if URL already exists in database*/
				if (isURLexisting(url, mysqlconn)){
					return 0;
				}
				
				/*Create new document*/
				Document doc = createDocument(url);
				
				if (doc!=null){
					
					/*Perform annotation on newly created document*/
					AnnotationSet set = doAnnotation(doc);
					if (set == null){
						return 0;
					}
					AnnotationSet original = doc.getAnnotations("Original markups");
					Annotation metadataAnnotation = hasMetadata(original, doc);
					
					/*If web page has metadata, extract entities from metadata page*/
					if (metadataAnnotation != null){
						System.out.println("has metadata link");
						System.exit(0);
					}
					
					/*Get all the entities extracted from web page*/
					HashSet<?> formatSet = (HashSet<?>)getFormats(set,doc);
					Set<?> titleSet = getTitle(original,doc);
					Set<?> authorSet = getAuthor(set,original,doc);
					String abst = getAbstract(original,doc);
					Set<String> downloadLinks = getDownloadLinks(original, doc);
					
					/*Print entities*/
					System.out.println("Formats:" + formatSet);
					System.out.println("Title: " + titleSet.toString());
					System.out.println("Author: " + authorSet);
					System.out.println("Abstract: " + abst.toString());
					System.out.println("Links: " + downloadLinks.toString());
					
					/*If download links is empty, crawl sub-pages i.e. Call EntityController using current URL as seed
					 * Call the crawler only if current URL is taken from input queue*/
					
					if (crawlSubpages(url, fromQueue, doc, original, downloadLinks)){
						return 0;
					}
					
					/*Insert URL in database*/
					PreparedStatement insertURL = null;
					try {
						insertURL = mysqlconn.prepareStatement("insert into tab_url values (?,?,?)");
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					try {
						insertURL.setString(1, null);		/*auto-incremented ID values*/
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					try {
						insertURL.setString(2, url.toString());		/*URL to be inserted into*/
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}

					try {
						insertURL.setInt(3, Checker.FROM_EXTRACTOR);
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					try {
						insertURL.executeUpdate();
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					System.out.println("URL inserted: " + url.toString());
					
					
					/*Insert other entities in database*/
					Statement getURLid = null;
					try {
						getURLid = mysqlconn.createStatement();
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					ResultSet urlID = null;
					try {
						urlID = getURLid.executeQuery("select ID from url where URL = '"+ url.toString() + "'limit 1");
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					try {
						if (urlID.next()){
							int id = urlID.getInt(1);
							
							PreparedStatement insertAbstract = mysqlconn.prepareStatement("insert into abstract values(?,?,?)");
							insertAbstract.setString(1, null);
							insertAbstract.setInt(2, id);
							insertAbstract.setString(3, abst);
							insertAbstract.executeUpdate();
							
							PreparedStatement insertFormats = mysqlconn.prepareStatement("insert into format values(?,?,?)");
							Iterator<?> format = formatSet.iterator();
							while(format.hasNext()){
								insertFormats.setString(1, null);
								insertFormats.setInt(2, id);
								insertFormats.setString(3, format.next().toString());
								insertFormats.executeUpdate();
							}
							PreparedStatement insertAuthors = mysqlconn.prepareStatement("insert into authors values(?,?,?)");
							Iterator<?> author = authorSet.iterator();
							while(author.hasNext()){
								insertAuthors.setString(1, null);
								insertAuthors.setInt(2, id);
								insertAuthors.setString(3, author.next().toString());
								insertAuthors.executeUpdate();
							}
							PreparedStatement insertTitles = mysqlconn.prepareStatement("insert into title values(?,?,?)");
							Iterator<?> Title = titleSet.iterator();
							while(Title.hasNext()){
								insertTitles.setString(1, null);
								insertTitles.setInt(2, id);
								insertTitles.setString(3, Title.next().toString());
								insertTitles.executeUpdate();
							}
							PreparedStatement insertLinks = mysqlconn.prepareStatement("insert into links values(?,?,?)");
							Iterator<?> Link = downloadLinks.iterator();
							while(Link.hasNext()){
								insertLinks.setString(1, null);
								insertLinks.setInt(2, id);
								insertLinks.setString(3, Link.next().toString());
								insertLinks.executeUpdate();
							}
						}
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					Factory.deleteResource(doc);
					return 0;	
				}
				else{
					/*Document was null*/
					return 1;	
				}
				
	}


	private static boolean crawlSubpages(URL url, boolean fromQueue, Document doc,
			AnnotationSet original, Set<String> downloadLinks) {
		
		if (downloadLinks.isEmpty()){
			if (fromQueue){
				try {
					String path = url.toString();
					int position = path.length();
					String basePath = null;
			        for (int i = position-1; i>=0 ;i--){
			        	if (path.charAt(i)=='/'){
			        		position = i;
			        		break;
			        	}
			        }
			        if (position == 0){
			        	basePath = path;
			        }
			        else{
			        	basePath = path.substring(0, position+1);
			        }
			        //System.out.println("basepath: "+basePath);
					Set<String> href = new HashSet<String>();
					href.add("href");
					AnnotationSet a = original.get("a",href);
					Annotation ann = null;
					String linkVal = null;
					Iterator<Annotation> links = a.iterator();
					while (links.hasNext()){
						ann = (Annotation) links.next();
						linkVal = (String) ann.getFeatures().get("href");
						//System.out.println("href: "+ linkVal + " " + basePath);
						if (!linkVal.startsWith("javascript")){
							String fullPath = new String(new URL(new URL(basePath),linkVal).toString());
							//System.out.println("full: "+fullPath);
							if (fullPath.startsWith(basePath)){
								Extractor.getEntities(new URL(fullPath), false);
							}
							//Extractor.getEntities(new URL(linkVal), false);
						}
					}
					//System.out.println("entering crawler..");
					//EntityController.begin(url.toString());
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			else{
				System.out.println("Sub-page url without links,exiting...");
			}
			Factory.deleteResource(doc);
			return true;
		}
		else{
			return false;	
		}
	}


	private static Document createDocument(URL url) {
		Document doc = null;
		try {
			doc = Factory.newDocument(url);
		} catch (ResourceInstantiationException e1) {
			System.out.println("\nCannot connect to provided link." + url);
		}
		return doc;
	}


	private static boolean isURLexisting(URL url, Connection mysqlconn) {
		Statement checkifexists = null;
		try {
			checkifexists = mysqlconn.createStatement();
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		ResultSet check = null;
		try {
			check = checkifexists.executeQuery("Select Exists (Select 1 from tab_url where URL = '"+ url.toString() + "')");
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			if (check.next())
			{
				if (check.getInt(1) == 1){
					System.out.println(url.toString() + " exists: Going to next");
					return true;
				}
			}
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.out.println(url.toString());
		return false;
	}
	
private static Annotation hasMetadata(AnnotationSet set,Document doc){
	AnnotationSet linkSet = set.get("a");
	Iterator<Annotation> linkIt = linkSet.iterator();
	while (linkIt.hasNext()){
		Annotation ann = (Annotation) linkIt.next();
		String value = null;
		try {
			value = doc.getContent().getContent(ann.getStartNode().getOffset(), ann.getEndNode().getOffset()).toString();
		} catch (InvalidOffsetException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if (value.toLowerCase().contains("metadata")){
			return ann;
		}
		
	}
	return null;
	
}


	/**
	 * 
	 * @param doc
	 * @return
	 * @throws Exception
	 */
	public static AnnotationSet doAnnotation(Document doc){
		System.out.println("annotating...");
		AnnotationSet set = null;
		corpus.add(doc);
		controller.add(deletor);
		controller.add(tokeniser);
		controller.add(gazetteer);
		controller.add(splitter);
		controller.add(tagger);
		controller.add(transducer);
		controller.add(matcher);
		controller.add(morpher);
		controller.setCorpus(corpus);
		try {
			controller.execute();
			set = doc.getAnnotations();
		} catch (ExecutionException e) {
			System.out.println("Failed to execute processing pipeline");
			return null;
		}
		finally{
			corpus.remove(doc);
			corpus.unloadDocument(doc);
		}
		return set;
	}


	/**
	 * 
	 * @param set
	 * @param doc
	 * @return
	 */
	public static Set<DocumentContent> getFormats(AnnotationSet set, Document doc){
		//FeatureMap map = Factory.newFeatureMap();
		map.put("majorType", "GISformat");
		AnnotationSet formats = set.get("Lookup",map);
		Set<DocumentContent> formatSet = new HashSet<DocumentContent>();
		Iterator<Annotation> it = formats.iterator();
		while(it.hasNext()){
			Annotation ann = it.next();
			try {
				formatSet.add(doc.getContent().getContent(ann.getStartNode().getOffset(), ann.getEndNode().getOffset()));
			} catch (InvalidOffsetException e) {
				e.printStackTrace();
			}
		}
		return formatSet;
	}
	/**
	 * 
	 * @param set
	 * @param doc
	 * @return
	 * @throws InvalidOffsetException
	 */
	public static Set<String> getTitle(AnnotationSet set,Document doc){
		Set<String> titles = new HashSet<String>();
		titles.add("title");
		titles.add("h1");
		//titles.add("h2");
		AnnotationSet original = set.get(titles);
		Set<String> titleSet = new HashSet<String>();
		if(original.isEmpty()){
			titleSet.add("none");
		}
		else{
			Iterator<Annotation> titleIt = original.iterator();
			int i=0;
				while (titleIt.hasNext()){
					Annotation ann = titleIt.next();
					try {
						titleSet.add(doc.getContent().getContent(ann.getStartNode().getOffset(), ann.getEndNode().getOffset()).toString());
					} catch (InvalidOffsetException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					if ((i++)==5) break;
			}
		}
		return titleSet;
	}
	
	public static Set<?> getAuthor(AnnotationSet set,AnnotationSet original, Document doc){
		
		AnnotationSet org = set.get("Organization");
		Set<DocumentContent> orgSet1 = new HashSet<DocumentContent>();
		Set<DocumentContent> orgSet2 = new HashSet<DocumentContent>();
		Set<DocumentContent> orgSet3 = new HashSet<DocumentContent>();
		Set<DocumentContent> orgSet = new LinkedHashSet<DocumentContent>();
		
		/*Part 1*/
		Annotation ann = null;
		Iterator<Annotation> it = org.iterator();
		while(it.hasNext()){
			ann = (Annotation) it.next();
			try {
				orgSet1.add(doc.getContent().getContent(ann.getStartNode().getOffset(), ann.getEndNode().getOffset()));
			} catch (InvalidOffsetException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		/*Part-2*/
		AnnotationSet sentence = set.get("Sentence");
		//FeatureMap pub = Factory.newFeatureMap();
		//String list = new String("distribute issue produce publish release assemble supply provide create develop");
		pub.put("root", "publish");
		pub.put("root", "distribute");
		pub.put("root", "distribution");
		pub.put("root", "produce");
		pub.put("root", "provide");
		pub.put("root", "create");
		AnnotationSet allContained = null;
		AnnotationSet pubTokens = null;
		Iterator<Annotation> senit = sentence.iterator();
		while(senit.hasNext()){
			ann = (Annotation) senit.next();
			allContained = set.get(ann.getStartNode().getOffset(), ann.getEndNode().getOffset());
			pubTokens = allContained.get("Token", pub);
			if(!pubTokens.isEmpty()){
				allContained = set.get("Organization",ann.getStartNode().getOffset(), ann.getEndNode().getOffset());
				Iterator<Annotation> orgIt = allContained.iterator();
				while (orgIt.hasNext()){
					ann = (Annotation) orgIt.next();
					try {
						orgSet2.add(doc.getContent().getContent(ann.getStartNode().getOffset(), ann.getEndNode().getOffset()));
					} catch (InvalidOffsetException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		}
		
		/*Part-3*/
		AnnotationSet title = original.get("title");
		AnnotationSet orgsInTitles = null;
		if (!title.isEmpty()){
			Annotation titleann = (Annotation) title.toArray()[0];
			orgsInTitles = set.get("Organization",titleann.getStartNode().getOffset(), titleann.getEndNode().getOffset());
			Iterator<Annotation> orgInTitle = orgsInTitles.iterator();
			while (orgInTitle.hasNext()){
				ann = (Annotation) orgInTitle.next();
				try {
					orgSet3.add(doc.getContent().getContent(ann.getStartNode().getOffset(), ann.getEndNode().getOffset()));
				} catch (InvalidOffsetException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		
		/*Combine sets*/
		orgSet.addAll(orgSet3);
		orgSet.addAll(orgSet2);
		//orgSet.addAll(orgSet1);
		return orgSet;
	}
	
	public static String getAbstract(AnnotationSet set,Document doc){
		mapAbstract.put("class", "body");
		AnnotationSet para = set.get("p");
		if (para.isEmpty()){
			return "Empty";
		}
		else{
			Iterator<Annotation> it = para.iterator();
			Annotation ann = (Annotation) para.toArray()[0];
			int minVal = ann.getId().intValue();
			int current = 0;
			while(it.hasNext()){
				ann = (Annotation) it.next();
				current = ann.getId().intValue();
				if (current<minVal){
					minVal = current;
				}
			}
			ann = para.get(minVal);
			String abstractValue = "Empty";
			try {
				abstractValue = doc.getContent().getContent(ann.getStartNode().getOffset(),ann.getEndNode().getOffset()).toString();
			} catch (InvalidOffsetException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}  
			return abstractValue;
		}
		
	}
	public static Set<String> getDownloadLinks(AnnotationSet original,Document doc){
		Set<String> href = new HashSet<String>();
		href.add("href");
		AnnotationSet a = original.get("a",href);
		Annotation ann = null;
		Set<String> linkSet = new HashSet<String>();
		String linkVal = null;
		Iterator<Annotation> links = a.iterator();
		while (links.hasNext()){
			ann = (Annotation) links.next();
			linkVal = (String) ann.getFeatures().get("href");
			//linkVal = doc.getContent().getContent(ann.getStartNode().getOffset(), ann.getEndNode().getOffset());
			List<Object> linkEnd = new ArrayList<Object>();
			XMLConfiguration config = null;
			try {
				config = new XMLConfiguration("madaap.xml");
			} catch (ConfigurationException e) {
				e.printStackTrace();
			}
			if (config!=null){
				linkEnd = config.getList("downloads.download");
			}
			
			Iterator<Object> linkIt = linkEnd.iterator();
			while (linkIt.hasNext()){
				if (linkVal.endsWith((String) linkIt.next())){
					linkSet.add(linkVal);
					break;
				}
			}
		}
		return linkSet;
		
	}

		public void run() {
		try {
			while(true){
				System.out.println("Extractor launched");
				if(getEntities(queue.take(),true)==0){
					report();
				}
			}
		}
		catch (InterruptedException e) {
			System.out.println("Extractor interrupted!");
			e.printStackTrace();
		} catch (Exception e) {
			System.out.println("Extractor is unable to get content.");
			e.printStackTrace();
		}
	}


		private void report() {
			// Report to user and Admin
			System.out.println("Extractor successfully stored entities.\n");
			//System.exit(0);
		}
}
