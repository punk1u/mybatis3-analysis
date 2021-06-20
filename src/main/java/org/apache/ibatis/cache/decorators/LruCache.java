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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ibatis.cache.Cache;

/**
 * Lru（最近最少使用）缓存装饰器
 * Lru (least recently used) cache decorator.
 *
 * @author Clinton Begin
 */
public class LruCache implements Cache {

  private final Cache delegate;
  private Map<Object, Object> keyMap;
  private Object eldestKey;


  public LruCache(Cache delegate) {
    this.delegate = delegate;
    /**
     * Lru缓存默认缓存个数为1024个元素
     */
    setSize(1024);
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  /**
   * 指定这个Lru（最少使用）缓存可以存储的元素个数
   * @param size
   */
  public void setSize(final int size) {
    /**
     * 初始化keyMap，keyMap的类型继承自LinkedHashMap，
     * 并覆盖了removeEldestEntry方法
     */
    keyMap = new LinkedHashMap<Object, Object>(size, .75F, true) {
      private static final long serialVersionUID = 4267176411845948333L;

      /**
       * LinkedHashMap在插入新的键值对时会调用该方法，以决定是否在插入新的键值对后，移除老的键值对
       * @param eldest
       * @return
       */
      @Override
      protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
        boolean tooBig = size() > size;
        if (tooBig) {
          /**
           * 获取将要被移除缓存项的键值
           */
          eldestKey = eldest.getKey();
        }
        return tooBig;
      }
    };
  }

  @Override
  public void putObject(Object key, Object value) {
    /**
     * 存储缓存项，这里为了实现Lru，当被装饰类的容量超出了keyMap的所规定的容量（由构造方法传入）后，
     * keyMap会移除最长时间未被访问的键，并将该键保存到eldestKey中，然后由cycleKeyList方法将eldestKey
     * 传给被装饰类的removeObject方法，移除相应的缓存
     */
    delegate.putObject(key, value);
    cycleKeyList(key);
  }

  @Override
  public Object getObject(Object key) {
    /**
     * 刷新key在keyMap中的位置
     */
    keyMap.get(key); // touch
    /**
     * 从被装饰类中获取相应缓存项
     */
    return delegate.getObject(key);
  }

  @Override
  public Object removeObject(Object key) {
    /**
     * 从被装饰类中移除相应的缓存项
     */
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    delegate.clear();
    keyMap.clear();
  }

  private void cycleKeyList(Object key) {
    /**
     * 存储key到keyMap中
     */
    keyMap.put(key, key);
    if (eldestKey != null) {
      /**
       * 从被装饰类中移除相应的缓存项
       */
      delegate.removeObject(eldestKey);
      eldestKey = null;
    }
  }

}
