package asyncMemManager.common.di;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface Persistence {
	/**
	 * save data storage
	 * @param key
	 * @param data
	 * @return
	 */
	public CompletableFuture<UUID> store(UUID key, String data, long expectedDuration);
	
	/**
	 * retrieve and remove data from storage
	 * @param key
	 * @return
	 */
	public String retrieve(UUID key);
	
	/**
	 * remove data from storage.
	 * @param key
	 */
	public void remove(UUID key);
}
