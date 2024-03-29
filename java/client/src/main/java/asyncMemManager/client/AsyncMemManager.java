package asyncMemManager.client;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import asyncMemManager.client.di.*;
import asyncMemManager.common.Configuration;
import asyncMemManager.common.ManagedObjectQueue;
import asyncMemManager.common.ReadWriteLock;
import asyncMemManager.common.ReadWriteLock.ReadWriteLockableObject;
import asyncMemManager.common.di.IndexableQueuedObject;


public class AsyncMemManager implements asyncMemManager.client.di.AsyncMemManager, AutoCloseable {
	
	// this is for special marker only.
	private static final ManagedObjectQueue<ManagedObjectBase> queuedForManageCandle = new ManagedObjectQueue<>(0, null);
	private static final ManagedObjectQueue<ManagedObjectBase> obsoletedManageCandle = new ManagedObjectQueue<>(0, null);
	
	private Configuration config;
	private HotTimeCalculator hotTimeCalculator;
	private Persistence persistence;
	private BlockingQueue<ManagedObjectQueue<ManagedObjectBase>> candlesPool;	
	private List<ManagedObjectQueue<ManagedObjectBase>> candlesSrc;
	private AtomicLong usedSize = new AtomicLong(0);
	private Comparator<ManagedObjectBase> cacheNodeComparator = (n1, n2) -> (n2.isObsoleted()) ? 1 : 
																			(n1.isObsoleted()) ? -1 : 
																			n2.hotTime.compareTo(n1.hotTime);

	/**
	 * Construct Async Mem Manager
	 * @param config
	 * @param coldTimeCalculator
	 * @param persistence
	 */
	public AsyncMemManager(Configuration config,
								HotTimeCalculator coldTimeCalculator, 
								Persistence persistence) 
	{
		this.config = config;
		this.hotTimeCalculator = coldTimeCalculator;
		this.persistence = persistence;
		this.candlesPool = new PriorityBlockingQueue<>(this.config.getCandlePoolSize(), 
														(c1, c2) -> Integer.compare(c1.getSize(), c2.getSize()));
		this.candlesSrc = new ArrayList<>(this.config.getCandlePoolSize());
		
		int numberOfManagementThread = this.config.getCandlePoolSize();
		numberOfManagementThread = numberOfManagementThread > 0 ? numberOfManagementThread : 1;
		
		int initcandleSize = this.config.getInitialSize() / this.config.getCandlePoolSize();
		initcandleSize = initcandleSize > 0 ? initcandleSize : this.config.getInitialSize();
		
		// init candle pool
		for(int i = 0; i < config.getCandlePoolSize(); i++)
		{
			ManagedObjectQueue<ManagedObjectBase> candle = new ManagedObjectQueue<>(initcandleSize, this.cacheNodeComparator); // thread-safe ensured by candlesPool
			this.candlesPool.add(candle);
			this.candlesSrc.add(candle);
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
	public <T> asyncMemManager.client.di.AsyncMemManager.SetupObject<T> manage(String flowKey, T object, AsyncMemSerializer<T> serializer) 
	{
		// init key, mapKey, newnode
		if (object == null)
		{
			return null;
		}
		
		SerializerGeneral baseSerializer = SerializerGeneral.getSerializerBaseInstance(serializer);
		long estimatedSize = serializer.estimateObjectSize(object);
		
		ManagedObject<T> managedObj = new ManagedObject<>(flowKey, object,  estimatedSize, baseSerializer);
		
		return new SetupObject<T>(managedObj);
	}
	
	public String debugInfo()
	{
		StringBuilder res = new StringBuilder();
		res.append("Used:"); res.append(this.usedSize.get());		
		long countItems = 0;
		for(ManagedObjectQueue<ManagedObjectBase> queue: this.candlesSrc)
		{
			countItems += queue.getSize();
		}
		res.append(" Items:"); res.append(countItems);
		return res.toString();
	}

	@Override
	public void close() throws Exception {
		for (int i=0; i < this.candlesSrc.size(); i++)
		{
			this.candlesPool.take();
		}
		
		for (ManagedObjectQueue<ManagedObjectBase> candle: this.candlesSrc) {
			while (candle.getSize() > 0)
			{
				ManagedObjectBase managedObj = candle.getAndRemoveAt(candle.getSize() - 1);		
				this.usedSize.addAndGet(-managedObj.estimatedSize);	
				this.persistence.remove(managedObj.key);
			}
		}
	}
	
	private void track(ManagedObjectBase managedObj) {		
		this.doManageAction(managedObj, EnumSet.of(ManagementState.None, ManagementState.Managing), (containerCandle) ->{
				
			long nextwaitDuration = this.hotTimeCalculator.calculate(this.config, managedObj.flowKey, managedObj.numberOfAccess);
			managedObj.hotTime = managedObj.startTime.plus(nextwaitDuration, ChronoField.MILLI_OF_SECOND.getBaseUnit());	
			boolean needcheckRemove = true;
			if (containerCandle == null) // unmanaged, probably none or cached.
			{
				// put node to candle	
				
				boolean shouldTracking = true;
				if (this.usedSize.get() + managedObj.estimatedSize > this.config.getCapacity()) {
					ManagedObjectBase coldestNode = this.getColdestCandidate();
					
					if (coldestNode != null && this.cacheNodeComparator.compare(managedObj, coldestNode) > 0)
					{
						// storing to cache to reserve space for new object.
						this.doManageAction(coldestNode, ManagementState.Managing, 
								(final ManagedObjectQueue<ManagedObjectBase> coldestCandle) -> { 
									this.cache(coldestCandle, coldestNode); 
								});
					}else {						 
						this.persistObject(managedObj);
						managedObj.setManagementState(null);
						shouldTracking = false;
						needcheckRemove = false;
					}
				}
				
				if (shouldTracking)
				{
					ManagedObjectQueue<ManagedObjectBase> candle = null;
					candle = this.pollCandle();
			
					try {
						if (!managedObj.isObsoleted()) {
							candle.add(managedObj);
							this.usedSize.addAndGet(managedObj.estimatedSize);
							managedObj.setManagementState(candle);					
						}else {
							needcheckRemove = false;
							managedObj.setManagementState(null);
						}
					} 
					catch(Exception ex) {
						System.out.println(ex.getMessage());
					}						
					
					this.candlesPool.offer(candle);
				}
			} else {
				this.pollCandle(containerCandle);
				
				managedObj.hotTime = managedObj.hotTime.plus(nextwaitDuration, ChronoField.MILLI_OF_SECOND.getBaseUnit());
				try {					
					if (!managedObj.isObsoleted()) {
						containerCandle.syncPriorityAt(managedObj.indexInCandle);
					}							
					managedObj.setManagementState(containerCandle); // restore management state --> unlock other queueing
				}
				catch (Exception ex) {
					System.out.println(ex.getMessage());
				}
				
				this.candlesPool.offer(containerCandle);
			}			

			if (needcheckRemove && managedObj.isObsoleted()) { // to void other remove failed to be queued while this action running.
				this.removeFromManagement(managedObj);
			}			

			this.cleanUp();
		});
	}
	
	private void removeFromManagement(ManagedObjectBase managedObj) {
		this.doManageAction(managedObj, ManagementState.Managing, (final ManagedObjectQueue<ManagedObjectBase> containerCandle) -> {
			this.pollCandle(containerCandle);
			
			try {
				containerCandle.getAndRemoveAt(managedObj.indexInCandle);
				this.usedSize.addAndGet(-managedObj.estimatedSize);							
				managedObj.setManagementState(AsyncMemManager.obsoletedManageCandle);
			}
			catch(Exception ex) {
				System.out.println(ex.getMessage());
			}
				
			this.candlesPool.offer(containerCandle);
		});
	}	
	
	private boolean doManageAction(ManagedObjectBase managedObj, ManagementState expectedCurrentState, Consumer<ManagedObjectQueue<ManagedObjectBase>> action) {
		return doManageAction(managedObj, EnumSet.of(expectedCurrentState), action);
	}
	/**
	 * execute manage action for managedObj, ensure only one action queued per object, bypass this request if other action queued.
	 */
	private boolean doManageAction(ManagedObjectBase managedObj, EnumSet<ManagementState> expectedCurrentState, Consumer<ManagedObjectQueue<ManagedObjectBase>> action)	
	{
		boolean queued = false;
		ManagedObjectQueue<ManagedObjectBase> containerCandle = null;
		ManagementState state = managedObj.getManagementState();
		if (expectedCurrentState.contains(state)) { 
			synchronized (managedObj) { 
				if (state == managedObj.getManagementState()) // state unchanged. 
				{ 
					containerCandle = managedObj.setManagementState(AsyncMemManager.queuedForManageCandle);
					queued = true;
				}
			}
			
			if(queued)
			{
				action.accept(containerCandle);
			}
		}
		
		return queued;
	}
	
	private boolean isOverCapability()
	{
		return this.usedSize.get() > this.config.getCapacity();
	}
	
	private ManagedObjectQueue<ManagedObjectBase>  pollCandle(){
		try {
			return this.candlesPool.take();
		} catch (InterruptedException e) {
			return null;
		}
	}
	
	private ManagedObjectQueue<ManagedObjectBase>  pollCandle(ManagedObjectQueue<ManagedObjectBase> containerCandle)
	{
		while (!this.candlesPool.remove(containerCandle))
		{
			Thread.yield();
		}
		return containerCandle;
	}
	
	private ManagedObjectBase getColdestCandidate()
	{			
		ManagedObjectBase coldestCandidate = null;
		for (ManagedObjectQueue<ManagedObjectBase> candle : this.candlesSrc)
		{
			ManagedObjectBase node = candle.getPollCandidate();
			if (node != null)
			{
				if (coldestCandidate == null || cacheNodeComparator.compare(coldestCandidate, node) > 0)
				{
					coldestCandidate = node;
				}
			}
		}
		
		return coldestCandidate;
	}
	
	private void persistObject(ManagedObjectBase managedObject)
	{
		if (managedObject.asyncCounter.get() > 0)
		{
			final ReadWriteLock<ManagedObjectBase> lock = managedObject.lockManage();
			if(managedObject.object != null && !managedObject.isObsoleted())
			{
				long expectedDuration = LocalTime.now().until(managedObject.hotTime, ChronoField.MILLI_OF_SECOND.getBaseUnit());
				this.persistence.store(managedObject.key, managedObject.serializer.serialize(managedObject.object), expectedDuration);
				managedObject.object = null;
			}
			lock.unlock();			
		}
	}
	
	/*
	 * need containerCandle as managedObject's containerCandle may be marked as queued.
	 */
	private void cache(ManagedObjectQueue<ManagedObjectBase> containerCandle, ManagedObjectBase managedObject) {
		this.pollCandle(containerCandle);
		
		try {	
			containerCandle.getAndRemoveAt(managedObject.indexInCandle);
			this.usedSize.addAndGet(-managedObject.estimatedSize);
			this.persistObject(managedObject);
			managedObject.setManagementState(null);
		
		} 
		catch(Exception ex) {
			System.out.println(ex.getMessage());
		}
		
		// add back to pool after used.
		this.candlesPool.offer(containerCandle);
	}
	/**
	 * this is expected to be run in manage executor, by queueCleanUp
	 */
	private void cleanUp()
	{
		while (this.isOverCapability())
		{
			boolean isReduced = false;
			// find the coldest candidate
			final ManagedObjectBase coldestObject = this.getColdestCandidate();
			
			// candidate founded
			if (coldestObject != null)
			{							
				isReduced = this.doManageAction(coldestObject, ManagementState.Managing, 
						(final ManagedObjectQueue<ManagedObjectBase> coldestCandle) -> {
							this.cache(coldestCandle, coldestObject);
					});
			}
			
			if (!isReduced)
			{
				Thread.yield();
			}
		}
	}
	
	
	/**
	 * manage actions: tracking/cleanup/stop
	 * only one cleanup for whole manager
	 * only one tracking/cleanup
	 */
	abstract class ManagedObjectBase implements IndexableQueuedObject, ReadWriteLockableObject
	{
		/***
		 * key value to lookup object, this is auto unique generated
		 * also used as key for synchronize access vs management
		 */
		final UUID key;
		
		/**
		 * flow key, this is used for estimate waiting time
		 */
		final String flowKey;
		
		/**
		 * original object
		 */
		volatile Object object;
		
		/**
		 * time object managed
		 */
		volatile LocalDateTime startTime;
		
		/**
		 * time object expected to be retrieved for async, this is average from previous by keyflow
		 */
		volatile LocalDateTime hotTime;
		
		/**
		 * estimated by serializer, size of object
		 */
		final long estimatedSize;
		
		/**
		 * the candle contain this object, used for fast cleanup, removal
		 */
		private volatile ManagedObjectQueue<ManagedObjectBase> containerCandle;
		
		/**
		 * the index of object in candle, used for fast removal
		 */
		volatile int indexInCandle = -1;
		
		volatile int numberOfAccess = 0;
		
		/**
		 * the serializer to ser/des object for persistence.
		 */
		final SerializerGeneral serializer;

		/**
		 * init  ManagedObject 
		 */
		public ManagedObjectBase(String flowKey, long estimatedSize, SerializerGeneral serializer) {
			this.flowKey = flowKey;
			this.key = UUID.randomUUID();
			this.startTime = this.hotTime = LocalDateTime.now();
			this.estimatedSize = estimatedSize;
			this.serializer = serializer;
		}

		/**
		 * if object still being setup. object start to be managed setup closed
		 */
		volatile boolean doneSetup = false;
		
		/**
		 * counting of async flows, object stop to be managed when all aync closed
		 */
		final AtomicInteger asyncCounter = new AtomicInteger(0);
		
		boolean isObsoleted() {
			return this.doneSetup && this.asyncCounter.get() == 0;
		}
		
		/**
		 * get management state to have associated action.
		 * this is for roughly estimate, as not ensured thread-safe.
		 */
		ManagementState getManagementState()
		{
			synchronized (this) {
				ManagedObjectQueue<ManagedObjectBase> c = this.containerCandle;  
				if (c == null)
				{
					return ManagementState.None;
				}else if (c == AsyncMemManager.queuedForManageCandle){
					return ManagementState.Queued;
				}else if (c == AsyncMemManager.obsoletedManageCandle){
					return ManagementState.Obsoleted;
				}else {
					return ManagementState.Managing;
				}
			}
		}
		
		/**
		 * return previous containerCandel
		 */
		ManagedObjectQueue<ManagedObjectBase> setManagementState(ManagedObjectQueue<ManagedObjectBase> containerCandle)
		{
			synchronized (this) {
				ManagedObjectQueue<ManagedObjectBase> prev = this.containerCandle;
				this.containerCandle = containerCandle;
				return prev;				
			}
		}
	
		/**
		 * used for read/write locking this managed object. 
		 */
		private volatile int readWriteCounter = 0;
		
		@Override
		public void setIndexInQueue(int idx)
		{
			this.indexInCandle = idx;
		}
		
		/**
		 * whether this object is available for cleanup
		 */
		@Override
		public boolean isPeekable() {
			return this.readWriteCounter == 0 && this.getManagementState() == ManagementState.Managing && this.indexInCandle >= 0;
		}
		
		/**
		 * read locking, used for async flows access object, to ensure data not interfered
		 */
		ReadWriteLock<ManagedObjectBase> lockRead()
		{
			return new ReadWriteLock.ReadLock<ManagedObjectBase>(this);
		}		
		
		/**
		 * manage locking, used for cleanup, remove process, to ensure data not interfered 
		 */
		ReadWriteLock<ManagedObjectBase> lockManage()
		{
			return new ReadWriteLock.WriteLock<ManagedObjectBase>(this);
		}
		
		public int getLockFactor() {
			return this.readWriteCounter;
		}
		
		public void addLockFactor(int lockfactor) {
			this.readWriteCounter += lockfactor;
		}
		
		public Object getLockerKey() {
			return this.key;
		}
	}
	
	static enum ManagementState
	{
		None,
		Queued,
		Managing,
		Obsoleted
	}
	
	/**
	 * Generic class for ManagedObject
	 */
	class ManagedObject<T> extends ManagedObjectBase
	{
		ManagedObject(String flowKey, T obj, long estimatedSize, SerializerGeneral serializer)
		{
			super(flowKey, estimatedSize, serializer);
			this.object = obj;
		}
	}
	
	/**
	 * Object for async flow {@link ManagedObjectBase#asyncCounter}
	 */
	public class AsyncObject<T> implements asyncMemManager.client.di.AsyncMemManager.AsyncObject<T>
	{
		final ManagedObject<T> managedObject;
		AsyncObject(ManagedObject<T> managedObject) {
			this.managedObject = managedObject;			
			this.managedObject.asyncCounter.addAndGet(1);
		}
		
		/**
		 * run method provided by caller synchronously  
		 */
		@SuppressWarnings("unchecked")
		public <R> R supply(Function<T,R> f) {
			ReadWriteLock<ManagedObjectBase> lock = this.managedObject.lockRead();
			this.loadFromStoreIfNeeded(lock);
			R res = f.apply((T)this.managedObject.object);
			lock.unlock();			
			this.trackIfNeeded();
			return res;
		}		
		
		@SuppressWarnings("unchecked")
		public void apply(Consumer<T> f) {
			ReadWriteLock<ManagedObjectBase> lock = this.managedObject.lockRead();
			this.loadFromStoreIfNeeded(lock);
			f.accept((T)this.managedObject.object);
			lock.unlock();			
			this.trackIfNeeded();
		}
		
		private void loadFromStoreIfNeeded(ReadWriteLock<ManagedObjectBase> currentReadlock) {
			if (this.managedObject.object == null)
			{
				ReadWriteLock<ManagedObjectBase> manageLock = currentReadlock.upgrade();
				if (this.managedObject.object == null) 
				{
					this.managedObject.object = this.managedObject.serializer.deserialize(AsyncMemManager.this.persistence.retrieve(this.managedObject.key));
				}
				
				manageLock.downgrade();
			} 
			
			long waittime = this.managedObject.startTime.until(LocalDateTime.now(), ChronoField.MILLI_OF_SECOND.getBaseUnit());
			AsyncMemManager.this.hotTimeCalculator.stats(AsyncMemManager.this.config, this.managedObject.flowKey, this.managedObject.numberOfAccess, waittime);
			this.managedObject.startTime = LocalDateTime.now();
			this.managedObject.numberOfAccess++;
		}
		
		private void trackIfNeeded() {
			if (this.managedObject.asyncCounter.get() > 0 && this.managedObject.doneSetup)
			{
				AsyncMemManager.this.track(this.managedObject);
			}
		}
		
		@Override
		public void close() throws Exception {
			if (this.managedObject.asyncCounter.addAndGet(-1) == 0 && this.managedObject.doneSetup)
			{
				AsyncMemManager.this.removeFromManagement(this.managedObject);
			}
		}		
	}
	
	/**
	 * Object for setup flow {@link ManagedObjectBase#beingSetup}
	 */	
	public class SetupObject<T> implements asyncMemManager.client.di.AsyncMemManager.SetupObject<T>
	{
		final ManagedObject<T> managedObject;
		SetupObject(ManagedObject<T> managedObject) {
			this.managedObject = managedObject;
		}
		
		/**
		 * get object for async flow usage
		 */
		public AsyncObject<T> asyncObject(){
			return new AsyncObject<T>(this.managedObject);
		}
		
		/**
		 * get object for setup flow
		 */
		@SuppressWarnings("unchecked")
		public T o() {
			return (T) managedObject.object;
		}

		/**
		 * finished setup flow
		 */
		@Override
		public void close() throws Exception {
			this.managedObject.doneSetup = true;
			if (this.managedObject.asyncCounter.get() > 0)
			{
				AsyncMemManager.this.track(this.managedObject);
			}
		}
	}
}
