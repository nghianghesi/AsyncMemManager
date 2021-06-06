package asyncMemManager.common;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import asyncMemManager.common.AsyncMemManager.ManagedObjectBase.KeyLock;
import asyncMemManager.common.di.BinarySerializer;

public class AsyncMemManager implements asyncMemManager.common.di.AsyncMemManager {
	
	private Configuration config;
	private asyncMemManager.common.di.HotTimeCalculator coldTimeCalculator;
	private asyncMemManager.common.di.Persistence persistence;
	private BlockingQueue<Queue<ManagedObjectBase>> candlesPool;
	private List<Queue<ManagedObjectBase>> candles;
	private ConcurrentHashMap<UUID, ManagedObjectBase> map;
	private long usedSize;
	private Object usedSizeKey = new Object(); 
	private Comparator<ManagedObjectBase> coldCacheNodeComparator = (n1, n2) -> n2.hotTime.compareTo(n1.hotTime);

	/**
	 * Construct Async Memcache object
	 * @param config
	 * @param coldTimeCalculator
	 * @param persistence
	 */
	public AsyncMemManager(Configuration config,
								asyncMemManager.common.di.HotTimeCalculator coldTimeCalculator, 
								asyncMemManager.common.di.Persistence persistence) 
	{
		this.config = config;
		this.coldTimeCalculator = coldTimeCalculator;
		this.persistence = persistence;
		this.map = new ConcurrentHashMap<>(this.config.initialSize);
		this.candlesPool = new PriorityBlockingQueue<>(this.config.candlePoolSize, 
														(c1, c2) -> Integer.compare(c1.size(), c2.size()));
		this.candles = new ArrayList<>(this.config.candlePoolSize);
		
		// init candle pool
		for(int i = 0; i < config.candlePoolSize; i++)
		{
			Queue<ManagedObjectBase> candle = new PriorityQueue<>(this.coldCacheNodeComparator);
			this.candlesPool.add(candle);
			this.candles.add(candle);
		}
	}
	
	/***
	 * put object to cache
	 * @param flowKey
	 * @param object
	 * @param serializer
	 * @return key for retrieve object from cache.
	 */
	@Override
	public <T> ManagedObject<T> manage(String flowKey, T object, BinarySerializer<T> serializer) 
	{
		// init key, mapKey, newnode
		if (object == null)
		{
			return null;
		}
		
		ManagedObject<T> managedObj = new ManagedObject<>();
		managedObj.key = UUID.randomUUID();
		managedObj.object = object;
		managedObj.serializer = new BinarySerializerBase(serializer);
		managedObj.startTime = LocalTime.now();
		managedObj.estimatedSize = serializer.estimateObjectSize(object);
		
		long waitDuration = this.coldTimeCalculator.calculate(this.config, flowKey);
		managedObj.hotTime = managedObj.startTime.plus(waitDuration, ChronoField.MILLI_OF_SECOND.getBaseUnit());
		
		this.map.put(managedObj.key, managedObj);

		// put node to candle
		Queue<ManagedObjectBase> candle = null;
		try {
			candle = this.candlesPool.take();
		} catch (InterruptedException e) {
			return null;
		}

		synchronized (candle) {
			candle.add(managedObj);
			managedObj.containerCandle = candle;						
		}		
		
		synchronized (this.usedSizeKey) {
			this.usedSize += managedObj.estimatedSize;
		}
		
		new RecursiveCompletableFuture(() -> this.isOverCapability() ? this.cleanUp() : null).run();		
		return managedObj;
	}
	
	private boolean isOverCapability()
	{
		return this.usedSize > 0 && this.usedSize > this.config.capacity;
	}
	
	private CompletableFuture<Void> cleanUp()
	{
		ManagedObjectBase coldestNode = null;
		for (Queue<ManagedObjectBase> candle : this.candles)
		{
			ManagedObjectBase node = candle.peek();
			if (node != null)
			{
				if (coldestNode == null || coldCacheNodeComparator.compare(coldestNode, node) > 0)
				{
					coldestNode = node;
				}
			}
		}
		
		if (coldestNode != null)
		{
			while(!this.candlesPool.remove(coldestNode.containerCandle))
			{
				Thread.yield();
			}				
			
			synchronized (coldestNode.containerCandle) {
				coldestNode = coldestNode.containerCandle.poll(); // it's ok if coldestNode may be updated to warmer by other thread.
				coldestNode.containerCandle = null;				
			}

			this.candlesPool.offer(coldestNode.containerCandle);
			
			// coldestNode was removed from candles so, never duplicate persistence.
			if (coldestNode != null)
			{
				final ManagedObjectBase persistedNode = coldestNode;
				final CompletableFuture<Void> res = new CompletableFuture<>();
				this.persistence.store(coldestNode.key, coldestNode.serializer.serialize(coldestNode.object))
				.thenRun(()->{
					
					// synchronized to ensure retrieve never return null
					KeyLock keylock = persistedNode.lockManage();
					persistedNode.object = null;
					keylock.unlock();
					
					synchronized (this.usedSizeKey) {
						this.usedSize -= persistedNode.estimatedSize;
					}
					
					res.complete(null);
				});
				return res;
			}
		}
		
		return CompletableFuture.completedFuture(null);
	}

	public static class FlowKeyConfiguration
	{	
	}
	
	public static class Configuration
	{
		int initialSize;
		int capacity;
		int cleanupInterval;
		int candlePoolSize;
		Map<String, FlowKeyConfiguration> flowKeyConfig = new HashMap<>();

		public Configuration(int capacity, 
								int initialSize, 
								int cleanupInterval,
								int candlePoolSize,
								Map<String, FlowKeyConfiguration> flowKeyConfig) 
		{
			this.capacity = capacity;
			this.candlePoolSize = candlePoolSize >= 0 ? candlePoolSize : Runtime.getRuntime().availableProcessors();
			this.initialSize = initialSize > 0 ? initialSize : 100;
			this.cleanupInterval = cleanupInterval;
			this.flowKeyConfig = flowKeyConfig;
		}
	}
	
	abstract class ManagedObjectBase
	{
		UUID key;
		Object object;
		LocalTime startTime;
		LocalTime hotTime;
		long estimatedSize;
		Queue<ManagedObjectBase> containerCandle;
		BinarySerializerBase serializer;
		
		private int acceessCounter = 0;
		KeyLock lockRead()
		{
			while (true)
			{
				synchronized (this.key) {
					if (this.acceessCounter <= 0)
					{
						return new ReadKeyLock();
					}
				}
				Thread.yield();
			}				
		}
		
		KeyLock lockManage()
		{
			while (true)
			{
				synchronized (this.key) {
					if (this.acceessCounter == 0)
					{
						return new ManageKeyLock();
					}
				}
				Thread.yield();
			}
		}
		
		abstract class KeyLock {
			protected boolean unlocked = false;
			abstract int lockFactor();
			
			KeyLock()
			{
				ManagedObjectBase.this.acceessCounter += this.lockFactor();
			}

			void unlock() {
				synchronized (ManagedObjectBase.this.key) {
					if (!this.unlocked)
					{
						this.unlocked = true;
						ManagedObjectBase.this.acceessCounter -= this.lockFactor();
					}
				}					
			}	
			
			@Override
			protected void finalize() throws Throwable {
				if (this.unlocked)
				{
					this.unlock();
				}
			}		
		}			
		
		class ReadKeyLock extends KeyLock
		{
			@Override
			int lockFactor() {
				return 1;
			}
		}
		
		class ManageKeyLock extends KeyLock
		{
			@Override
			int lockFactor() {
				return -1;
			}			
		}
	}
	
	public class ManagedObject<T> extends ManagedObjectBase implements InvocationHandler
	{		
		@Override
		protected void finalize() throws Throwable {
			// TODO Auto-generated method stub
			
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			// TODO Auto-generated method stub
			KeyLock keylock = this.lockRead();
			if (this.object == null) {
				//this.object = AsyncMemManager.this.persistence.retrieve(this.key);
			}
			Object res = method.invoke(object, args);
			keylock.unlock();
			return res;
		}
	}
	
	private static class RecursiveCompletableFuture
	{
		Runnable runnable;
		RecursiveCompletableFuture(Supplier<CompletableFuture<Void>> s)
		{
			this.runnable = () -> {
				CompletableFuture<Void> f = s.get();
				if(f!=null)
				{
					f.thenRun(this.runnable);
				}
			};
		}
		
		void run() 
		{
			this.runnable.run();
		}
	}
}
