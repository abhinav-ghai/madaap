package collectURL;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;

public class ManualFeeder extends TimerTask implements Runnable {
	private final BlockingQueue<URL> queue;
	public ManualFeeder(BlockingQueue<URL> q){
		queue = q;
	}
	@Override
	public void run(){
		BufferedReader urlReader = null;
		BufferedWriter usedWriter = null;
		File urlFile = new File("input/url.txt");
		File usedFile = new File("input/used.txt");
		try {
			urlReader = new BufferedReader(new FileReader(urlFile));
			usedWriter = new BufferedWriter(new FileWriter(usedFile,true));
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		String stringURL = null;
		try {
			while ((stringURL = urlReader.readLine())!=null){
				if (stringURL.length()>0){
					URI uri = new URI(stringURL);
					usedWriter.write(stringURL + '\n');
					queue.add(uri.toURL());
				}
			}
		}catch (IOException e) {
			e.printStackTrace();
			System.out.print("Problem reading file: " + urlFile.toString());
		} catch (URISyntaxException e) {
			e.printStackTrace();
			System.out.print("Issue with URL syntax: " + stringURL);
		}
		try {
			urlReader.close();
			usedWriter.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		BufferedWriter writer = null;
		try {
			writer = new BufferedWriter(new FileWriter(urlFile));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			writer.write("");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			writer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

}
