package nicelee.ui.thread;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import nicelee.ui.Global;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamManager extends Thread{

	private static final Logger logger = LoggerFactory.getLogger(StreamManager.class);
	Process process;
    InputStream inputStream;
    public StreamManager(Process process, InputStream inputStream) {
    	this.process = process;
        this.inputStream = inputStream;
    }
    
        public void run () {
        InputStreamReader inputStreamReader = null;
        BufferedReader bufferedReader = null;
        try {
        	inputStreamReader = new InputStreamReader(inputStream, "utf-8");
        	bufferedReader = new BufferedReader(inputStreamReader);
        	String line = null;
            while((line = bufferedReader.readLine()) !=null ) {
            	if(Global.debugCmd)
            		logger.info("{}", line);
            }
        } catch (IOException e) {
            logger.error("异常", e);
        } finally {
        	try { if (bufferedReader != null) bufferedReader.close(); } catch (Exception ignored) {}
        	try { if (inputStreamReader != null) inputStreamReader.close(); } catch (Exception ignored) {}
        	try { if (inputStream != null) inputStream.close(); } catch (Exception ignored) {}
        }
        process.destroy();
    }
}