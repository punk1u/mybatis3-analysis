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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;

/**
 * 具有阻塞功能的缓存类BlockingCache
 *
 * 当指定 key 对应元素不存在于缓存中时，BlockingCache 会根据 lock 进行加锁。
 * 此时，其他线程将会进入等待状态，直到与 key 对应的元素被填充到缓存中。
 * 而不是让所有线程都去访问数据库。
 *
 * <p>Simple blocking decorator
 *
 * <p>Simple and inefficient version of EhCache's BlockingCache decorator.
 * It sets a lock over a cache key when the element is not found in cache.
 * This way, other threads will wait until this element is filled instead of hitting the database.
 *
 * <p>By its nature, this implementation can cause deadlock when used incorrecly.
 *
 * @author Eduardo Macarron
 *
 */
public class BlockingCache implements Cache {

  /**
   * 锁等待的时间
   */
  private long timeout;
  private final Cache delegate;
  private final ConcurrentHashMap<Object, CountDownLatch> locks;

  public BlockingCache(Cache delegate) {
    this.delegate = delegate;
    this.locks = new ConcurrentHashMap<>();
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
  public void putObject(Object key, Object value) {
    try {
      /**
       * 存储缓存项
       */
      delegate.putObject(key, value);
    } finally {
      /**
       * 释放锁
       */
      releaseLock(key);
    }
  }

  @Override
  public Object getObject(Object key) {
    /**
     * 请求锁
     */
    acquireLock(key);
    Object value = delegate.getObject(key);
    /**
     * 若缓存命中，则释放锁。未命中则不释放锁
     */
    if (value != null) {
      /**
       * 释放锁
       */
      releaseLock(key);
    }
    return value;
  }

  @Override
  public Object removeObject(Object key) {
    // despite of its name, this method is called only to release locks
    /**
     * 释放锁
     */
    releaseLock(key);
    return null;
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  private void acquireLock(Object key) {
    /**
     * 设置state为1
     */
    CountDownLatch newLatch = new CountDownLatch(1);
    while (true) {
      /**
       * 尝试将这个缓存key对应的锁放入锁Map中
       */
      CountDownLatch latch = locks.putIfAbsent(key, newLatch);
      /**
       * 如果添加锁成功，直接退出当前方法
       * 否则，说明之前已经存在这个key的缓存锁了，执行后面的等待逻辑
       */
      if (latch == null) {
        break;
      }
      try {
        /**
         * 判断state属性是否为0，如果为0，则继续往下执行，
         * 如果不为0，则使当前线程进入等待状态，直到某个线程将state属性置为0，
         * 然后再唤醒再await()方法中等待的线程
         */
        if (timeout > 0) {
          boolean acquired = latch.await(timeout, TimeUnit.MILLISECONDS);
          if (!acquired) {
            throw new CacheException(
                "Couldn't get a lock in " + timeout + " for the key " + key + " at the cache " + delegate.getId());
          }
        } else {
          latch.await();
        }
      } catch (InterruptedException e) {
        throw new CacheException("Got interrupted while trying to acquire lock for key " + key, e);
      }
    }
  }

  private void releaseLock(Object key) {
    CountDownLatch latch = locks.remove(key);
    if (latch == null) {
      throw new IllegalStateException("Detected an attempt at releasing unacquired lock. This should never happen.");
    }
    latch.countDown();
  }

  public long getTimeout() {
    return timeout;
  }

  public void setTimeout(long timeout) {
    this.timeout = timeout;
  }
}
