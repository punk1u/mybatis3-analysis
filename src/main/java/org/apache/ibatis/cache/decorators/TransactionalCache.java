/*
 *    Copyright 2009-2021 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.cache.decorators;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 *
 * 二级缓存装饰器，可以为Cache实例增加事务功能，用于解决脏读问题
 *
 *
 * The 2nd level cache transactional buffer.
 * <p>
 * This class holds all cache entries that are to be added to the 2nd level cache during a Session.
 * Entries are sent to the cache when commit is called or discarded if the Session is rolled back.
 * Blocking cache support has been added. Therefore any get() that returns a cache miss
 * will be followed by a put() so any lock associated with the key can be released.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class TransactionalCache implements Cache {

  private static final Log log = LogFactory.getLog(TransactionalCache.class);

  private final Cache delegate;
  private boolean clearOnCommit;
  /**
   * 在事务被提交前，所有从数据库中查询的结果将缓存中此集合中,
   * 之所以要将结果保存在这个集合中而不是delegate所表示的缓存中，
   * 是因为直接存到delegate会导致脏数据问题
   *
   * 如果没有这个集合作为额外的缓存，只有一个delegate共享缓存的话，
   * 假如事务A对记录A进行了更新。下一个时刻事务A从数据库查询记录
   * A，并将记录A写入缓存中。再然后，事务B查询记录 A，由于缓存中存在记录A，事务
   * B直接从缓存中取数据。这个时候，脏数据问题就发生了。事务B在事务A未提交情况
   * 下，读取到了事务A所修改的记录。
   *
   * 而有了这个集合后，在第一个时刻，事务A和B同时查询记录A。此时共享缓存中还没没有数据，所以两
   * 个事务均会向数据库发起查询请求，并将查询结果存储到各自的事务缓存中。第二个时刻，事务
   * A更新记录 A，这里把更新后的记录A记为A′，因为更新操作会导致二级缓存（即事务缓存失效）,
   * 所以此时事务A和事务B的二级事务缓存都为空。第三个时刻，两个事务再次进行查询。此时，
   * 事务A读取到的记录为修改后的A′值，而事务B读取到的记录仍为原值A。第四个时刻，事务A
   * 被提交，并将事务缓存A中的内容转存到共享缓存中。第五个时刻，事务B再次查询记录A,
   * 由于共享缓存中有相应的数据，所以直接取缓存数据即可。因此得到记录 A′，而非记录 A。
   * 但由于事务 A 已经提交，所以事务 B 读取到的记录 A′ 并非是脏数据。就这样，MyBaits依靠着事务缓存解决了
   * 脏读问题，事务间只能读取到其他事务提交后的内容，这相当于事务隔离级别中的“读已提交（Read Committed）”。
   * 需要注意的是，MyBatis缓存事务机制只能解决脏读问题，并不能解决“不可重复读”问题。例如，在上面的例子中，事务B
   * 在被提交前进行了三次查询。前两次查询得到的结果为记录A，最后一次查询得到的结果为A′，最后一次的查询结果与前两次
   * 的查询结果不同，这就会导致“不可重复读”问题。MyBatis的缓存事务机制最高只支持“读已提交”，并不能解决“不可重复读”问题。
   * 即使数据库使用了更高的隔离级别解决了这个问题，但因 MyBatis 缓存事务机制级别较低。此时仍然会导致“不可重复读”问题的发生，
   * 这个在开发中需要注意一下
   */
  private final Map<Object, Object> entriesToAddOnCommit;
  /**
   * 在事务被提交前，当缓存未命中时，CacheKey将会被存储在此集合中
   */
  private final Set<Object> entriesMissedInCache;

  public TransactionalCache(Cache delegate) {
    this.delegate = delegate;
    this.clearOnCommit = false;
    this.entriesToAddOnCommit = new HashMap<>();
    this.entriesMissedInCache = new HashSet<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  @Override
  public Object getObject(Object key) {
    // issue #116
    /**
     * 查询delegate所代表的缓存
     */
    Object object = delegate.getObject(key);
    if (object == null) {
      /**
       * 缓存未命中，则将key存入到entriesMissedInCache中
       */
      entriesMissedInCache.add(key);
    }
    // issue #146
    if (clearOnCommit) {
      return null;
    } else {
      return object;
    }
  }

  @Override
  public void putObject(Object key, Object object) {
    /**
     * 将键值对存入到entriesToAddOnCommit中，而非delegate缓存中
     */
    entriesToAddOnCommit.put(key, object);
  }

  @Override
  public Object removeObject(Object key) {
    return null;
  }

  @Override
  public void clear() {
    clearOnCommit = true;
    /**
     * 清空entriesToAddOnCommit
     */
    entriesToAddOnCommit.clear();
  }

  /**
   * 事务提交时的调用方法
   */
  public void commit() {
    if (clearOnCommit) {
      delegate.clear();
    }
    /**
     * 刷新未缓存的结果到delegate缓存中
     */
    flushPendingEntries();
    /**
     * 清空entriesToAddOnCommit 和 entriesMissedInCache
     */
    reset();
  }

  public void rollback() {
    unlockMissedEntries();
    reset();
  }

  private void reset() {
    clearOnCommit = false;
    entriesToAddOnCommit.clear();
    entriesMissedInCache.clear();
  }

  /**
   * 将entriesToAddOnCommit中的内容转存到delegate缓存中
   */
  private void flushPendingEntries() {
    for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
      delegate.putObject(entry.getKey(), entry.getValue());
    }
    for (Object entry : entriesMissedInCache) {
      if (!entriesToAddOnCommit.containsKey(entry)) {
        delegate.putObject(entry, null);
      }
    }
  }

  private void unlockMissedEntries() {
    for (Object entry : entriesMissedInCache) {
      try {
        delegate.removeObject(entry);
      } catch (Exception e) {
        log.warn("Unexpected exception while notifying a rollback to the cache adapter. "
            + "Consider upgrading your cache adapter to the latest version. Cause: " + e);
      }
    }
  }

}
