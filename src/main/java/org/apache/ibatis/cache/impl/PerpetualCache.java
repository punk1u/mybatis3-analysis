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
package org.apache.ibatis.cache.impl;

import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;

/**
 * 提供基本缓存功能的缓存类
 * 一级缓存对象，一级缓存所存储的查询结果会在MyBatis执行
 * 更新操作（INSERT|UPDATE|DELETE），以及提交和回滚事务时被清空
 * @author Clinton Begin
 */
public class PerpetualCache implements Cache {

  private final String id;

  /**
   * 存储缓存数据的HashMap
   */
  private final Map<Object, Object> cache = new HashMap<>();

  public PerpetualCache(String id) {
    this.id = id;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public int getSize() {
    return cache.size();
  }

  /**
   * 存储键值对到HashMap的方法
   * @param key
   * @param value
   */
  @Override
  public void putObject(Object key, Object value) {
    cache.put(key, value);
  }

  /**
   * 查找缓存项
   * @param key
   *          The key
   * @return
   */
  @Override
  public Object getObject(Object key) {
    return cache.get(key);
  }

  /**
   * 移除缓存项
   * @param key
   *          The key
   * @return
   */
  @Override
  public Object removeObject(Object key) {
    return cache.remove(key);
  }

  @Override
  public void clear() {
    cache.clear();
  }

  @Override
  public boolean equals(Object o) {
    if (getId() == null) {
      throw new CacheException("Cache instances require an ID.");
    }
    if (this == o) {
      return true;
    }
    if (!(o instanceof Cache)) {
      return false;
    }

    Cache otherCache = (Cache) o;
    return getId().equals(otherCache.getId());
  }

  @Override
  public int hashCode() {
    if (getId() == null) {
      throw new CacheException("Cache instances require an ID.");
    }
    return getId().hashCode();
  }

}
