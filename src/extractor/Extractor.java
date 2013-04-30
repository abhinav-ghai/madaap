package extractor;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
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
import gate.annotation.AnnotationSetImpl;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.lang.math.NumberUtils;

import crawler.EntityController;

/**
 * Contains methods to perform annotations and extract few entities like author,title etc from a given URL
 * and store them in a MySQL database.
 * It consumes URLs from a FIFO Queue.
 * Queue is being populated by manual feeder and twitter posts, and other collectors from package collectURL
 * @author abhinav
 *
 */
public class Extractor implements Runnable{
	
	/*declare GATE processing resources*/
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
	
	/*declare queue for collecting URL*/
	private final BlockingQueue<URL> queue;
	
	/*to start extractor thread*/
	public Extractor(BlockingQueue<URL> q){
		queue = q;
		new Thread(this).start();
	}
	/**
	 * 
	 * @param url
	 * @param fromQueue
	 * @return
	 */
	public static int getEntities(URL url,boolean fromQueue){
				
				/*Create a connection to MySQL database*/
				Connection mysqlconn = Madaap.getMySQLconnection();
				
				/*Check if URL already exists in database*/
				if (isURLexisting(url, mysqlconn)){
					try {
						mysqlconn.close();
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					return 0;
				}
				
				/*Create new GATE document from URL*/
				Document doc = createDocument(url);
				
				if (doc!=null){
					
					/*Get original markups of GATE document*/
					AnnotationSet original = doc.getAnnotations("Original markups");
					
					/*Get download able links*/
					Set<String> downloadLinks = getDownloadLinks(original, doc, url);
					
					/*If current document is a sub-page and has no links, discard document*/
					if(fromQueue==false && downloadLinks.isEmpty()){
						Factory.deleteResource(doc);
						try {
							mysqlconn.close();
						} catch (SQLException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						return 0;
					}
					/*Perform annotation on newly created GATE document*/
					AnnotationSet set = doAnnotation(doc);
					if (set == null){
						try {
							mysqlconn.close();
						} catch (SQLException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						return 0;
					}
					
					URL metadataurl = hasMetadata(original, doc, url);
					
					/*If web page has metadata, extract spatial extent from metadata page*/
					
					Set<String> spatialExtent = new HashSet<String>();
					if (metadataurl != null){
							spatialExtent = getSpatialExtent(metadataurl);
					}
					/*Get all the entities extracted from web page*/
					Set<String> formatSet = getFormats(downloadLinks);
					Set<String> titleSet = getTitle(original,doc);
					Set<DocumentContent> authorSet = getAuthor(set,original,doc);
					String abst = getAbstract(original,doc);
					
					/*Print entities*/
					System.out.println("Formats:" + formatSet);
					System.out.println("Title: " + titleSet.toString());
					System.out.println("Author: " + authorSet);
					System.out.println("Abstract: " + abst.toString());
					System.out.println("Links: " + downloadLinks.toString());
					System.out.println("Spatial: " + spatialExtent.toString());
					
					/*If download links is empty, crawl sub-pages i.e. Call EntityController using current URL as seed
					 * Call the crawler only if current URL is taken from input queue*/
					
					if (crawlSubpages(url, fromQueue, doc, original, downloadLinks)){
						try {
							mysqlconn.close();
						} catch (SQLException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						return 0;
					}
					
					insertURL(mysqlconn, url);
					
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
						urlID = getURLid.executeQuery("select ID from tab_url where URL = '"+ url.toString() + "'limit 1");
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					try {
						if (urlID.next()){
							int id = urlID.getInt(1);
							insertAbstract(mysqlconn,abst,id);
							insertFormat(mysqlconn,formatSet,id);
							insertAuthor(mysqlconn,authorSet,id);
							insertTitle(mysqlconn,titleSet,id);
							insertLink(mysqlconn,downloadLinks,id);
							insertSpatialExtent(mysqlconn,spatialExtent,id);
						}
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					try {
						urlID.close();
					} catch (SQLException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
					Factory.deleteResource(doc);
					try {
						mysqlconn.close();
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					return 0;
				}
				else{
					/*Document was null*/
					try {
						mysqlconn.close();
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					return 1;	
				}
	}
	/**
	 * 
	 * @param downloadLinks
	 * @return
	 */
	private static Set<String> getFormats(Set<String> downloadLinks) {
		Set<String> formatSet = new HashSet<String>(); 
		Iterator<String> linkit = downloadLinks.iterator();
		while(linkit.hasNext()){
			String link = linkit.next();
			formatSet.add(link.substring(link.lastIndexOf('.')+1, link.length()));
		}
		return formatSet;
	}
	
	/**
	 * 
	 * @param mysqlconn
	 * @param spatialExtent
	 * @param id
	 * @throws SQLException
	 */
	private static void insertSpatialExtent(Connection mysqlconn,
			Set<String> spatialExtent, int id) throws SQLException {
		PreparedStatement insertSE = mysqlconn.prepareStatement("insert into tab_spatialextent values(?,?,?)");
		Iterator<?> seiter = spatialExtent.iterator();
		while(seiter.hasNext()){
			insertSE.setString(1, null);
			insertSE.setInt(2, id);
			insertSE.setString(3, seiter.next().toString());
			insertSE.executeUpdate();
		}
		insertSE.close();
	}

	/**
	 * 
	 * @param mysqlconn
	 * @param downloadLinks
	 * @param id
	 * @throws SQLException
	 */
	private static void insertLink(Connection mysqlconn,
			Set<String> downloadLinks,int id) throws SQLException {
		PreparedStatement insertLinks = mysqlconn.prepareStatement("insert into tab_links values(?,?,?)");
		Iterator<?> Link = downloadLinks.iterator();
		while(Link.hasNext()){
			insertLinks.setString(1, null);
			insertLinks.setInt(2, id);
			insertLinks.setString(3, Link.next().toString());
			insertLinks.executeUpdate();
		}
		insertLinks.close();
	}

	/**
	 * 
	 * @param mysqlconn
	 * @param titleSet
	 * @param id
	 * @throws SQLException
	 */
	private static void insertTitle(Connection mysqlconn, Set<?> titleSet,int id) throws SQLException {
		PreparedStatement insertTitles = mysqlconn.prepareStatement("insert into tab_title values(?,?,?)");
		Iterator<?> Title = titleSet.iterator();
		while(Title.hasNext()){
			insertTitles.setString(1, null);
			insertTitles.setInt(2, id);
			insertTitles.setString(3, Title.next().toString());
			insertTitles.executeUpdate();
		}
		insertTitles.close();
	}

	/**
	 * 
	 * @param mysqlconn
	 * @param authorSet
	 * @param id
	 * @throws SQLException
	 */
	private static void insertAuthor(Connection mysqlconn, Set<?> authorSet,int id) throws SQLException {
		PreparedStatement insertAuthors = mysqlconn.prepareStatement("insert into tab_authors values(?,?,?)");
		Iterator<?> author = authorSet.iterator();
		while(author.hasNext()){
			insertAuthors.setString(1, null);
			insertAuthors.setInt(2, id);
			insertAuthors.setString(3, author.next().toString());
			insertAuthors.executeUpdate();
		}
		insertAuthors.close();
	}

	/**
	 * 
	 * @param mysqlconn
	 * @param formatSet
	 * @param id
	 * @throws SQLException
	 */
	private static void insertFormat(Connection mysqlconn, Set<String> formatSet,int id) throws SQLException {
		PreparedStatement insertFormats = mysqlconn.prepareStatement("insert into tab_format values(?,?,?)");
		Iterator<?> format = formatSet.iterator();
		while(format.hasNext()){
			insertFormats.setString(1, null);
			insertFormats.setInt(2, id);
			insertFormats.setString(3, format.next().toString());
			insertFormats.executeUpdate();
		}
		insertFormats.close();
	}

	/**
	 * 
	 * @param mysqlconn
	 * @param abst
	 * @param id
	 * @throws SQLException
	 */
	private static void insertAbstract(Connection mysqlconn, String abst, int id) throws SQLException {
		PreparedStatement insertAbstract = mysqlconn.prepareStatement("insert into tab_abstract values(?,?,?)");
		insertAbstract.setString(1, null);
		insertAbstract.setInt(2, id);
		insertAbstract.setString(3, abst);
		insertAbstract.executeUpdate();
		insertAbstract.close();
	}

	/**
	 * 
	 * @param mysqlconn
	 * @param url
	 */
	private static void insertURL(Connection mysqlconn,URL url){
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
		try {
			insertURL.close();
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.out.println("URL inserted: " + url.toString());
	}
	
	/**
	 * 
	 * @param metadataurl
	 * @return
	 */
	private static Set<String> getSpatialExtent(URL metadataurl) {
		System.out.println(metadataurl);
		XMLConfiguration config = null;
		List<Object> words = new ArrayList<Object>();
		try {
			config = new XMLConfiguration("config/madaap.xml");
		} catch (ConfigurationException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		if (config!=null){
			words = config.getList("SpatialExtentKeyword.north");
			words.addAll(config.getList("SpatialExtentKeyword.south"));
			words.addAll(config.getList("SpatialExtentKeyword.east"));
			words.addAll(config.getList("SpatialExtentKeyword.west"));
		}
		Document doc = createDocument(metadataurl);
		AnnotationSet original = doc.getAnnotations("Original markups");
		Set<String> spatialextent = new HashSet<String>();
		AnnotationSet spatial = new AnnotationSetImpl(doc);
		List<Integer> idlist = new ArrayList<Integer>(); 
		Iterator<Annotation> annit = original.iterator();
		while(annit.hasNext()){
			Annotation ann = annit.next();
			String content = null;
			try {
				content = doc.getContent().getContent(ann.getStartNode().getOffset(), ann.getEndNode().getOffset()).toString();
				content = content.trim();
			} catch (InvalidOffsetException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			if (NumberUtils.isNumber(content)& !content.isEmpty()){
				float numericcontent = Float.parseFloat(content);
				if (numericcontent<=180 && numericcontent>=-180){
					String type = ann.getType().toLowerCase();
					//if(type.contains("west")||type.contains("east")||type.contains("north")||type.contains("south")){
					Iterator<Object> wordit = words.iterator();
					while(wordit.hasNext()){
						if(type.contains((String)wordit.next())){
							//System.out.println(ann+"\n"+content);
							FeatureMap map = Factory.newFeatureMap();
							map.put("content", content);
							ann.setFeatures(map);
							idlist.add(ann.getId());
							spatial.add(ann);
							break;
						}	
					}
				}
			}
		}
		Collections.sort(idlist);
		if(idlist.size()==0){
			return spatialextent;
		}
		List<Integer> startid = getSpatialGroup(idlist);
		Iterator<Integer> idit = startid.iterator();
		while(idit.hasNext()){
			Integer id = idit.next();
			Annotation one = spatial.get(idlist.get(id));
			Annotation two = spatial.get(idlist.get(id+1));
			Annotation three = spatial.get(idlist.get(id+2));
			Annotation four = spatial.get(idlist.get(id+3));
			String first = one.getType() + ": " + one.getFeatures().get("content");
			String second = two.getType() + ": " + two.getFeatures().get("content");
			String third = three.getType() + ": " + three.getFeatures().get("content");
			String fourth = four.getType() + ": " + four.getFeatures().get("content");
			spatialextent.add(first+";"+second+";"+third+";"+fourth);
			System.out.println(first+"\n"+second+"\n"+third+"\n"+fourth+"\n\n");
		}
		Factory.deleteResource(doc);
		return spatialextent;
	}

	/**
	 * 
	 * @param idlist
	 * @return
	 */
	private static List<Integer> getSpatialGroup(List<Integer> idlist) {
		Integer old = idlist.get(0);
		ArrayList<Integer> startingidlist = new ArrayList<Integer>();
		ArrayList<Integer> diff = new ArrayList<Integer>(idlist.size()-1);
		for(int i=1;i<idlist.size();i++){
			Integer now = idlist.get(i);
			diff.add(now-old);
			old=now;
		}
		Integer position = new Integer(0);
		while(position<diff.size()-2){
			if(diff.get(position).equals(diff.get(position+1))){
				if(diff.get(position+1).equals(diff.get(position+2))){
					startingidlist.add(position);
				}
			}
			position++;
		}
		return startingidlist;
	}

	/**
	 * 
	 * @param url
	 * @param fromQueue
	 * @param doc
	 * @param original
	 * @param downloadLinks
	 * @return
	 */
	private static boolean crawlSubpages(URL url, boolean fromQueue, Document doc,
			AnnotationSet original, Set<String> downloadLinks) {
		
		if (downloadLinks.isEmpty()){
			if (fromQueue){
					//System.out.println("entering crawler..");
					try {
						EntityController.begin(url.toString());
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
			}
			else{
				System.out.println("Sub-page URL,No links,Don't insert in DB");
			}
			Factory.deleteResource(doc);
			return true;
		}
		else{
			return false;	
		}
	}
	/**
	 * 
	 * @param url
	 * @return
	 */
	private static Document createDocument(URL url) {
		Document doc = null;
		try {
			doc = Factory.newDocument(url);
		} catch (ResourceInstantiationException e1) {
			System.out.println("\nCannot connect to provided link." + url);
		}
		return doc;
	}

	/**
	 * 
	 * @param url
	 * @param mysqlconn
	 * @return
	 */
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
			try {
				checkifexists.close();
				check.close();
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		System.out.println(url.toString());
		return false;
	}
	/**
	 * 
	 * @param set
	 * @param doc
	 * @param url
	 * @return
	 */
private static URL hasMetadata(AnnotationSet set,Document doc, URL url){
	String contenttype = new String();
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
		if (value.toLowerCase().contains("metadata")||value.toLowerCase().contains("xml")){
			if (!ann.getFeatures().keySet().contains("href")){
				continue;
			}
			String metadatahref = (String) ann.getFeatures().get("href");
			String metadataurl = null;
			if (metadatahref.startsWith("/")){
				metadataurl = url.getProtocol() + "://" + url.getHost() + metadatahref;
			}
			else{
				metadataurl = url.toString().substring(0, url.toString().lastIndexOf('/')+1)+metadatahref;
			}
			URL u = null;
			try {
				u = new URL(metadataurl);
			} catch (MalformedURLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		    URLConnection uc = null;
		    try {
				uc = u.openConnection();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		    contenttype = uc.getContentType();
		    if(contenttype.endsWith("/xml")){
		    	//System.out.println(u+"\n"+contenttype);
		    	return u;
		    }
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
	/**
	 * 
	 * @param set
	 * @param original
	 * @param doc
	 * @return
	 */
	public static Set<DocumentContent> getAuthor(AnnotationSet set,AnnotationSet original, Document doc){
		
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
	/**
	 * 
	 * @param set
	 * @param doc
	 * @return
	 */
	public static String getAbstract(AnnotationSet set,Document doc){
		mapAbstract.put("class", "body");
		XMLConfiguration config = null;
		try {
			config = new XMLConfiguration("config/madaap.xml");
		} catch (ConfigurationException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		int minAbstractLength = config.getInt("Abstract.MinimumLength");
		AnnotationSet para = set.get("p");
		if (para.isEmpty()){
			return "None found";
		}
		else{
			Iterator<Annotation> it = para.iterator();
			List<Integer> idlist = new ArrayList<Integer>();
			while(it.hasNext()){
				Annotation ann = it.next();
				Long length = ann.getEndNode().getOffset() - ann.getStartNode().getOffset();
				if(length>=minAbstractLength){
					idlist.add(ann.getId());	
				}
			}
			if(idlist.size()==0){
				return "None found";
			}
			Collections.sort(idlist);
			Annotation ann = ((AnnotationSetImpl)set).get(idlist.get(0));
			String content = null;
			try {
				 content = doc.getContent().getContent(ann.getStartNode().getOffset(), ann.getEndNode().getOffset()).toString();
			} catch (InvalidOffsetException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return content;
			/*
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
			return abstractValue;*/
		}
	}
	/**
	 * 
	 * @param original
	 * @param doc
	 * @param url
	 * @return
	 */
	public static Set<String> getDownloadLinks(AnnotationSet original,Document doc, URL url){
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
				config = new XMLConfiguration("config/madaap.xml");
			} catch (ConfigurationException e) {
				e.printStackTrace();
			}
			if (config!=null){
				linkEnd = config.getList("downloads.download");
			}
			
			Iterator<Object> linkIt = linkEnd.iterator();
			while (linkIt.hasNext()){
				if (linkVal.toLowerCase().endsWith((String) linkIt.next())){
					String base = url.toString().substring(0, url.toString().lastIndexOf('/'));
					URL baseurl=null;
					try {
						baseurl = new URL(base);
					} catch (MalformedURLException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
					URL absoluteurl = null;
					try {
						absoluteurl = new URL(baseurl,linkVal);
					} catch (MalformedURLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					System.out.println(base+" ::: "+linkVal);
					linkSet.add(absoluteurl.toString());
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
