# Cache plug-in for vRealize Orchestrator
The Cache plug-in for vRealize Orchestrator is a a plug-in which provides distributed in-memory data structures such as maps, queues sets etc.

Basic features of the plug-in:

 * Distributed in-memory data structures with TTL/lease options
 * Distributed unique ID generation
 * Distributed locking
 * Automatic auto-discovery with other vRO nodes which belong to the cluster

### Plugin download
[o11nplugin-cache-1.0.0.vmoapp](https://github.com/dimitrovvlado/o11n-plugin-cache/blob/master/dist/o11nplugin-cache.vmoapp?raw=true) 

### Supported platform version
The Cache plug-in supports vRO 6.0 and later.

### Sample scripting

#####Using lists:
```javascript
//Add a value in the default list
CacheManager.listService.add("my-value");

//Get the first value from the list
var value = CacheManager.listService.get(0);

//Clear the default list
CacheManager.listService.clear();
```

#####Using sets:
```javascript
//Add a value in the default set
CacheManager.setService.add("my-value");

//Get the elements from the default set
var elements = CacheManager.setService.elements();

//Clear the default set
CacheManager.setService.clear();
```

#####Using maps:
```javascript
//Put a value in the default map without TTL
CacheManager.mapService.put("my-key", "my-value");

//Put a value in the default map with a TTL of 2 minutes
CacheManager.mapService.put("my-key", "my-other-value", 2, CacheTimeUnit.MINUTES);

//Get the value from the map
var value = CacheManager.mapService.get("my-key");
```

#####Using queues:
```javascript
//Offer a value in the default queue, waiting up to 2 minutes if necessary for space to become available
CacheManager.queueService.offer("my-value", 2, CacheTimeUnit.MINUTES);

//Offer a value in the default queue, waiting if necessary for space to become available
CacheManager.queueService.offer("my-value");

//Poll a value from the default queue
var value = CacheManager.queueService.poll();

//Put a value from the default queue, waiting if necessary for space to become available
CacheManager.queueService.put("my-value");

//Retrieve and remove the head of the queue. Throws an exception if queue is empty
val value = CacheManager.queueService.remove();
```

#####Using ring buffers:
```javascript
//Adds an item to the tail of the ringbuffer 
CacheManager.ringbufferService.add("my-value");
//Get the sequence of the head
var sequence = CacheManager.ringbufferService.headSequence();
while(true){
    //Reads the item from the ringbuffer
    var item = CacheManager.ringbufferService.readOne(sequence);
    sequence++;
}
```

#####Using ID generators:
```javascript
//Create a unique ID with the default generator
var id = IdGeneratorManager.idGeneratorService.newId();

//Create a unique ID with a named ID generator
var id = IdGeneratorManager.idGeneratorService.newIdForGenerator("my-id-generator");
```

#####Using locks:
```javascript
//Acquires a lock by current workflow token id for 2 minutes
LockManager.lockService.lock(workflow.id, 2, CacheTimeUnit.MINUTES);

//Releases the lock. Will fail if invoked from another workflow
LockManager.lockService.unlock(workflow.id);

//Tries to acquire a lock if it is free within 10 seconds. Returns true if succeeded
var result = LockManager.lockService.tryLock(workflow.id, 10, CacheTimeUnit.SECONDS);

//Tries to acquire a lock for 2 minutes, if it is free within 10 seconds. Returns true if succeeded
var resut = LockManager.lockService.tryLockWithLease(workflow.id, 10, CacheTimeUnit.SECONDS, 2, CacheTimeUnit.MINUTES);
```

### Dependencies
The Cache plug-in for vRO uses the following third-party libraries:
* [Hazelcast](https://hazelcast.com/) - an in-memory data grid middleware, distributed under the Apache License, Version 2.0.
* [Google Guava](https://github.com/google/guava) - core libraries for Java-based projects, distributed under the Apache License, Version 2.0