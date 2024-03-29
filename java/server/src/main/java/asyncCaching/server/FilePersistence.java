package asyncCaching.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import asyncCaching.server.di.Persistence;

public class FilePersistence implements Persistence {
	
	private String baseFolder;
	public FilePersistence(String basefolder) {
		this.baseFolder = basefolder;
	}
	
	@Override
	public void store(UUID key, String data) {
	    Path path = Paths.get(this.baseFolder + key);
	    byte[] strToBytes = data.getBytes();
	    try {
			Files.write(path, strToBytes);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public String retrieve(UUID key) {	    
		Path path = Paths.get(this.baseFolder + key);
		String res = null;
		try {
			res = Files.readString(path);
			Files.delete(path);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}		
		return res;
	}

	@Override
	public void remove(UUID key) {
		Path path = Paths.get(this.baseFolder + key);
		try {
			Files.delete(path);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
