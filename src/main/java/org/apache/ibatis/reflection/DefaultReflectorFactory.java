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
package org.apache.ibatis.reflection;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.ibatis.util.MapUtil;

/**
 * ReflectorFactory的实现类，用于创建Reflector，同时兼有缓存的功能
 */
public class DefaultReflectorFactory implements ReflectorFactory {
  private boolean classCacheEnabled = true;

  /**
   * 目标类和反射器映射缓存
   */
  private final ConcurrentMap<Class<?>, Reflector> reflectorMap = new ConcurrentHashMap<>();

  public DefaultReflectorFactory() {
  }

  @Override
  public boolean isClassCacheEnabled() {
    return classCacheEnabled;
  }

  @Override
  public void setClassCacheEnabled(boolean classCacheEnabled) {
    this.classCacheEnabled = classCacheEnabled;
  }

  @Override
  public Reflector findForClass(Class<?> type) {
    /**
     * classCacheEnabled默认为true
     */
    if (classCacheEnabled) {
      // synchronized (type) removed see issue #461
      /**
       * 尝试从缓存获取目标类的Reflector，如果不存在，则创建并保存
       */
      return MapUtil.computeIfAbsent(reflectorMap, type, Reflector::new);
    } else {
      /**
       * 表示不使用缓存来存储相关目标类的Reflector对象，每次都新建Reflector对象
       */
      return new Reflector(type);
    }
  }

}
