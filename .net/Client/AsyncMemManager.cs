﻿namespace AsyncMemManager.Client
{
    using DI;
    using System;
    using System.Collections.Generic;
    using System.Collections.Concurrent;
    using System.Threading;

    using asyncMemManager.Common;

    public class AsyncMemManager : IAsyncMemManager
    {        
        // this is for special marker only.
        private static readonly ManagedObjectQueue<ManagedObjectBase> queuedForManageCandle = new ManagedObjectQueue<ManagedObjectBase>(0, null);
        private static readonly ManagedObjectQueue<ManagedObjectBase> obsoletedManageCandle = new ManagedObjectQueue<ManagedObjectBase>(0, null);

        private Configuration config;
        private IHotTimeCalculator hotTimeCalculator;
        private IPersistence persistence;
        private BlockingCollection<ManagedObjectQueue<ManagedObjectBase>> candlesPool;	
        private List<ManagedObjectQueue<ManagedObjectBase>> candlesSrc;
        private long usedSize = 0;
        private readonly IComparer<ManagedObjectBase> cacheNodeComparator = new CandleComparer();


        public AsyncMemManager(Configuration config,
                                    IHotTimeCalculator coldTimeCalculator, 
                                    IPersistence persistence) 
        {
            this.config = config;
            this.hotTimeCalculator = coldTimeCalculator;
            this.persistence = persistence;
            this.candlesPool = new BlockingCollection<ManagedObjectQueue<ManagedObjectBase>>(this.config.CandlePoolSize);
            this.candlesSrc = new List<ManagedObjectQueue<ManagedObjectBase>>(this.config.CandlePoolSize);
            
            int numberOfManagementThread = this.config.CandlePoolSize;
            numberOfManagementThread = numberOfManagementThread > 0 ? numberOfManagementThread : 1;
            
            int initcandleSize = this.config.InitialSize / this.config.CandlePoolSize;
            initcandleSize = initcandleSize > 0 ? initcandleSize : this.config.InitialSize;
            
            // init candle pool
            for(int i = 0; i < config.CandlePoolSize; i++)
            {
                ManagedObjectQueue<ManagedObjectBase> candle = new ManagedObjectQueue<ManagedObjectBase>(initcandleSize, this.cacheNodeComparator); // thread-safe ensured by candlesPool
                this.candlesPool.Add(candle);
                this.candlesSrc.Add(candle);
            }
        }

        public ISetupObject<T> Manage<T>(string flowKey, T obj, IAsyncMemSerializer<T> serializer) 
        {
            		// init key, mapKey, newnode
            if (obj == null)
            {
                return null;
            }
            
            SerializerGeneral baseSerializer = SerializerGeneral.GetSerializerBaseInstance(serializer);
            long estimatedSize = serializer.EstimateObjectSize(obj);
            
            ManagedObject<T> managedObj = new ManagedObject<T>(flowKey, obj,  estimatedSize, baseSerializer);
            
            return new SetupObject<T>(this, managedObj);
        }  
        
        public string DebugInfo()
        {
            return string.Empty;
        }        

        public void Dispose(){
            for (int i=0; i < this.candlesSrc.Count; i++)
            {
                this.candlesPool.Take();
            }
		
            foreach (ManagedObjectQueue<ManagedObjectBase> candle in this.candlesSrc) {
                while (candle.Size > 0)
                {
                    ManagedObjectBase managedObj = candle.GetAndRemoveAt(candle.Size - 1);		
                    Interlocked.Add(ref this.usedSize, -managedObj.estimatedSize);	
                    this.persistence.Remove(managedObj.key);
                }
            }
        }   

        private class AsyncMemManagerContainObject
        {
            protected readonly AsyncMemManager manager;
            protected AsyncMemManagerContainObject(AsyncMemManager m)
            {
                this.manager = m;
            }
        }
	
        private class SetupObject<T> : AsyncMemManagerContainObject, ISetupObject<T>
        {            
            public SetupObject(AsyncMemManager manager, ManagedObjectBase obj) : base (manager){
            }

            public IAsyncObject<T> AsyncO()
            {
                return null;
            }

            public T O()
            {
                return default(T);
            }

            public void Dispose(){
                
            }
        }
        
        private class AsyncObject<T> : AsyncMemManagerContainObject, IAsyncObject<T>
        {		           
            public AsyncObject(AsyncMemManager manager, ManagedObjectBase obj) : base (manager){
            }

            public R Supply<R>(Func<T,R> f)
            {
                return default(R);
            }
            public void Apply(Action<T> f)
            {
                return ;
            }

            public void Dispose(){

            }
        }
        
        private abstract class ManagedObjectBase : IndexableQueuedObject, IReadWriteLockableObject
        {
            /***
            * key value to lookup object, this is auto unique generated
            * also used as key for synchronize access vs management
            */
            internal readonly Guid key;
            
            /**
            * flow key, this is used for estimate waiting time
            */
            internal readonly string flowKey;
            
            /**
            * original object
            */
            internal volatile object obj;
            
            /**
            * time object managed
            */
            internal DateTime startTime;
            
            /**
            * time object expected to be retrieved for async, this is average from previous by keyflow
            */
            internal DateTime hotTime;
            
            /**
            * estimated by serializer, size of object
            */
            internal readonly long estimatedSize;
            
            /**
            * the candle contain this object, used for fast cleanup, removal
            */
            private volatile ManagedObjectQueue<ManagedObjectBase> containerCandle;
            
            /**
            * the index of object in candle, used for fast removal
            */
            internal volatile int indexInCandle = -1;
            
            internal volatile int numberOfAccess = 0;
            
            /**
            * the serializer to ser/des object for persistence.
            */
            internal readonly SerializerGeneral serializer;

            /**
            * init  ManagedObject 
            */
            public ManagedObjectBase(string flowKey, long estimatedSize, SerializerGeneral serializer) {
                this.flowKey = flowKey;
                this.key = Guid.NewGuid();
                this.startTime = this.hotTime = DateTime.Now;
                this.estimatedSize = estimatedSize;
                this.serializer = serializer;
            }

            /**
            * if object still being setup. object start to be managed setup closed
            */
            internal volatile bool doneSetup = false;
            
            /**
            * counting of async flows, object stop to be managed when all aync closed
            */
            internal long asyncCounter = 0;
            
            internal bool IsObsoleted() {
                return this.doneSetup && Interlocked.Read(ref this.asyncCounter) == 0;
            }
            
            /**
            * get management state to have associated action.
            * this is for roughly estimate, as not ensured thread-safe.
            */
            ManagementState ManagementState
            {
                get{
                    lock (this) {
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
            }
            
            /**
            * return previous containerCandel
            */
            ManagedObjectQueue<ManagedObjectBase> SetManagementState(ManagedObjectQueue<ManagedObjectBase> containerCandle)
            {
                lock (this) {
                    ManagedObjectQueue<ManagedObjectBase> prev = this.containerCandle;
                    this.containerCandle = containerCandle;
                    return prev;				
                }
            }
        
            /**
            * used for read/write locking this managed object. 
            */
            private volatile int readWriteCounter = 0;
            
            public void SetIndexInQueue(int idx)
            {
                this.indexInCandle = idx;
            }
            
            /**
            * whether this object is available for cleanup
            */
            public bool IsPeekable() {
                return this.readWriteCounter == 0 && this.ManagementState == ManagementState.Managing && this.indexInCandle >= 0;
            }
            
            /**
            * read locking, used for async flows access object, to ensure data not interfered
            */
            ReadWriteLock<ManagedObjectBase> LockRead()
            {
                return new ReadWriteLock<ManagedObjectBase>.ReadLock(this);
            }
            
            /**
            * manage locking, used for cleanup, remove process, to ensure data not interfered 
            */
            ReadWriteLock<ManagedObjectBase> LockManage()
            {
                return new ReadWriteLock<ManagedObjectBase>.WriteLock(this);
            }
            
            public int LockStatus => this.readWriteCounter;
            
            public void AddLockFactor(int lockfactor) {
                this.readWriteCounter += lockfactor;
            }
            
            public object LockerKey => this.key;
        }
        
        private enum ManagementState
        {
            None,
            Queued,
            Managing,
            Obsoleted
        }
	
	    /**
	        * Generic class for ManagedObject
        */
        private class ManagedObject<T> : ManagedObjectBase
        {
            public ManagedObject(string flowKey, T obj, long estimatedSize, SerializerGeneral serializer)
                : base(flowKey, estimatedSize, serializer)
            {                
                this.obj = obj;
            }
        } 

        private class CandleComparer : IComparer<ManagedObjectBase> 
        {
            public int Compare(ManagedObjectBase n1, ManagedObjectBase n2){
                return n2.IsObsoleted() ? 1 : 
                        n1.IsObsoleted() ? -1 : 
                            n2.hotTime.CompareTo(n1.hotTime);
            }
        }

        private class SerializerGeneral
        {	
            private static IDictionary<Type, SerializerGeneral> instances = new ConcurrentDictionary<Type, SerializerGeneral>();
            
            private Func<object, string> serialzeFunc;
            private Func<string, object> deserializeFunc;
            private Func<object, long> estimateObjectSizeFunc;
            
            // it's ok to in-thread safe here, as object override wouldn't cause any issue.
            public static SerializerGeneral GetSerializerBaseInstance<T>(IAsyncMemSerializer<T> serializer)
            {
                
                if(SerializerGeneral.instances.TryGetValue(serializer.GetType(), out SerializerGeneral inst) 
                    && inst == null)
                {
                    inst = new SerializerGeneral();

                    inst.serialzeFunc = (obj) => {
                        return serializer.Serialize((T)obj);
                    };		
                    
                    inst.deserializeFunc = (data) => {
                        return serializer.Deserialize(data);
                    };
                    
                    inst.estimateObjectSizeFunc = (obj) => {
                        return serializer.EstimateObjectSize((T)obj);
                    };

                    SerializerGeneral.instances.Add(serializer.GetType(), inst);
                }
                
                return SerializerGeneral.instances[serializer.GetType()];
            }

            private SerializerGeneral()
            {}
                       
            public string Serialize(object obj)
            {
                return this.serialzeFunc(obj);
            }
            
            public T deserialize<T>(string data)
            {
                return (T)this.deserializeFunc(data);
            }
            
            public long estimateObjectSize(object obj)
            {
                return this.estimateObjectSizeFunc(obj);
            }
        }        
    }
}
