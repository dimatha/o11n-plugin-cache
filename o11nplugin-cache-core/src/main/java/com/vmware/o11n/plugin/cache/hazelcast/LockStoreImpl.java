package com.vmware.o11n.plugin.cache.hazelcast;

import com.hazelcast.concurrent.lock.*;
import com.hazelcast.concurrent.lock.operations.AwaitOperation;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.DataSerializable;
import com.hazelcast.spi.ObjectNamespace;
import com.hazelcast.util.ConcurrencyUtil;
import com.hazelcast.util.ConstructorFunction;
import com.hazelcast.util.scheduler.EntryTaskScheduler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class LockStoreImpl implements DataSerializable, LockStore {

    private final transient ConstructorFunction<Data, LockResourceImpl> lockConstructor =
            new ConstructorFunction<Data, LockResourceImpl>() {
                public LockResourceImpl createNew(Data key) {
                    return new LockResourceImpl(key, LockStoreImpl.this);
                }
            };

    private final ConcurrentMap<Data, LockResourceImpl> locks = new ConcurrentHashMap<Data, LockResourceImpl>();
    private ObjectNamespace namespace;
    private int backupCount;
    private int asyncBackupCount;

    private LockService lockService;
    private EntryTaskScheduler entryTaskScheduler;

    public LockStoreImpl() {
    }

    public LockStoreImpl(LockService lockService, ObjectNamespace name,
                         EntryTaskScheduler entryTaskScheduler, int backupCount, int asyncBackupCount) {
        this.lockService = lockService;
        this.namespace = name;
        this.entryTaskScheduler = entryTaskScheduler;
        this.backupCount = backupCount;
        this.asyncBackupCount = asyncBackupCount;
    }

    @Override
    public boolean lock(Data key, String caller, long threadId, long referenceId, long leaseTime) {
        leaseTime = getLeaseTime(leaseTime);
        LockResourceImpl lock = getLock(key);
        return lock.lock(caller, threadId, referenceId, leaseTime, false);
    }

    private long getLeaseTime(long leaseTime) {
        long maxLeaseTimeInMillis = lockService.getMaxLeaseTimeInMillis();
        if (leaseTime > maxLeaseTimeInMillis) {
            throw new IllegalArgumentException("Max allowed lease time: " + maxLeaseTimeInMillis + "ms. "
                    + "Given lease time: " + leaseTime + "ms.");
        }
        if (leaseTime < 0) {
            leaseTime = maxLeaseTimeInMillis;
        }
        return leaseTime;
    }

    @Override
    public boolean txnLock(Data key, String caller, long threadId, long referenceId, long leaseTime) {
        LockResourceImpl lock = getLock(key);
        return lock.lock(caller, threadId, referenceId, leaseTime, true);
    }

    @Override
    public boolean extendLeaseTime(Data key, String caller, long threadId, long leaseTime) {
        LockResourceImpl lock = locks.get(key);
        if (lock == null) {
            return false;
        }
        return lock.extendLeaseTime(caller, threadId, leaseTime);
    }

    public LockResourceImpl getLock(Data key) {
        return ConcurrencyUtil.getOrPutIfAbsent(locks, key, lockConstructor);
    }

    @Override
    public boolean isLocked(Data key) {
        LockResource lock = locks.get(key);
        return lock != null && lock.isLocked();
    }

    @Override
    public boolean isLockedBy(Data key, String caller, long threadId) {
        LockResource lock = locks.get(key);
        if (lock == null) {
            return false;
        }
        return lock.isLockedBy(caller, threadId);
    }

    @Override
    public int getLockCount(Data key) {
        LockResource lock = locks.get(key);
        if (lock == null) {
            return 0;
        } else {
            return lock.getLockCount();
        }
    }

    @Override
    public long getRemainingLeaseTime(Data key) {
        LockResource lock = locks.get(key);
        if (lock == null) {
            return -1L;
        } else {
            return lock.getRemainingLeaseTime();
        }
    }

    @Override
    public boolean canAcquireLock(Data key, String caller, long threadId) {
        LockResourceImpl lock = locks.get(key);
        if (lock == null) {
            return true;
        } else {
            return lock.canAcquireLock(caller, threadId);
        }
    }

    @Override
    public boolean isTransactionallyLocked(Data key) {
        LockResourceImpl lock = locks.get(key);
        return lock != null && lock.isTransactional() && lock.isLocked();
    }

    @Override
    public boolean unlock(Data key, String caller, long threadId, long referenceId) {
        LockResourceImpl lock = locks.get(key);
        if (lock == null) {
            return false;
        }

        boolean result = false;
        if (lock.canAcquireLock(caller, threadId)) {
            if (lock.unlock(caller, threadId, referenceId)) {
                result = true;
            }
        }
        if (lock.isRemovable()) {
            locks.remove(key);
        }
        return result;
    }

    @Override
    public boolean forceUnlock(Data key) {
        LockResourceImpl lock = locks.get(key);
        if (lock == null) {
            return false;
        } else {
            lock.clear();
            if (lock.isRemovable()) {
                locks.remove(key);
                lock.cancelEviction();
            }
            return true;
        }
    }

    public int getVersion(Data key) {
        LockResourceImpl lock = locks.get(key);
        if (lock != null) {
            return lock.getVersion();
        }
        return -1;
    }

    public Collection<LockResource> getLocks() {
        return Collections.<LockResource>unmodifiableCollection(locks.values());
    }

    @Override
    public Set<Data> getLockedKeys() {
        Set<Data> keySet = new HashSet<Data>(locks.size());
        for (Map.Entry<Data, LockResourceImpl> entry : locks.entrySet()) {
            Data key = entry.getKey();
            LockResource lock = entry.getValue();
            if (lock.isLocked()) {
                keySet.add(key);
            }
        }
        return keySet;
    }

    void scheduleEviction(Data key, int version, long leaseTime) {
        entryTaskScheduler.schedule(leaseTime, key, version);
    }

    void cancelEviction(Data key) {
        entryTaskScheduler.cancel(key);
    }

    void setLockService(LockServiceImpl lockService) {
        this.lockService = lockService;
    }

    void setEntryTaskScheduler(EntryTaskScheduler entryTaskScheduler) {
        this.entryTaskScheduler = entryTaskScheduler;
    }

    public void clear() {
        locks.clear();
        entryTaskScheduler.cancelAll();
    }

    public ObjectNamespace getNamespace() {
        return namespace;
    }

    public int getBackupCount() {
        return backupCount;
    }

    public int getAsyncBackupCount() {
        return asyncBackupCount;
    }

    public int getTotalBackupCount() {
        return backupCount + asyncBackupCount;
    }

    public boolean addAwait(Data key, String conditionId, String caller, long threadId) {
        LockResourceImpl lock = getLock(key);
        return lock.addAwait(conditionId, caller, threadId);
    }

    public boolean removeAwait(Data key, String conditionId, String caller, long threadId) {
        LockResourceImpl lock = getLock(key);
        return lock.removeAwait(conditionId, caller, threadId);
    }

    public boolean startAwaiting(Data key, String conditionId, String caller, long threadId) {
        LockResourceImpl lock = getLock(key);
        return lock.startAwaiting(conditionId, caller, threadId);
    }

    public int getAwaitCount(Data key, String conditionId) {
        LockResourceImpl lock = getLock(key);
        return lock.getAwaitCount(conditionId);
    }

    public void registerSignalKey(ConditionKey conditionKey) {
        LockResourceImpl lock = getLock(conditionKey.getKey());
        lock.registerSignalKey(conditionKey);
    }

    public ConditionKey getSignalKey(Data key) {
        LockResourceImpl lock = locks.get(key);
        if (lock == null) {
            return null;
        } else {
            return lock.getSignalKey();
        }
    }

    public void removeSignalKey(ConditionKey conditionKey) {
        LockResourceImpl lock = locks.get(conditionKey.getKey());
        if (lock != null) {
            lock.removeSignalKey(conditionKey);
        }
    }

    public void registerExpiredAwaitOp(AwaitOperation awaitResponse) {
        Data key = awaitResponse.getKey();
        LockResourceImpl lock = getLock(key);
        lock.registerExpiredAwaitOp(awaitResponse);
    }

    public AwaitOperation pollExpiredAwaitOp(Data key) {
        LockResourceImpl lock = locks.get(key);
        if (lock == null) {
            return null;
        } else {
            return lock.pollExpiredAwaitOp();
        }
    }

    @Override
    public String getOwnerInfo(Data key) {
        final LockResource lock = locks.get(key);
        if (lock == null) {
            return "<not-locked>";
        } else {
            return "Owner: " + lock.getOwner() + ", thread-id: " + lock.getThreadId();
        }
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeObject(namespace);
        out.writeInt(backupCount);
        out.writeInt(asyncBackupCount);
        int len = locks.size();
        out.writeInt(len);
        if (len > 0) {
            for (LockResourceImpl lock : locks.values()) {
                lock.writeData(out);
            }
        }
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        namespace = in.readObject();
        backupCount = in.readInt();
        asyncBackupCount = in.readInt();
        int len = in.readInt();
        if (len > 0) {
            for (int i = 0; i < len; i++) {
                LockResourceImpl lock = new LockResourceImpl();
                lock.readData(in);
                lock.setLockStore(this);
                locks.put(lock.getKey(), lock);
            }
        }
    }

    @Override
    public String toString() {
        return "LockStoreImpl{"
                + "namespace=" + namespace
                + ", backupCount=" + backupCount
                + ", asyncBackupCount=" + asyncBackupCount
                + '}';
    }
}