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
package org.apache.ibatis.binding;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.binding.MapperProxy.MapperMethodInvoker;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Lasse Voss
 */
public class MapperProxyFactory<T> {

  /**
   * 当前MapperProxyFactory工厂类最终要生成的代理对象对应的mapper接口类
   */
  private final Class<T> mapperInterface;
  /**
   * Mapper接口中的方法与最终调用该方法时的执行对象MapperMethod的封装对象MapperMethodInvoker的映射集合
   */
  private final Map<Method, MapperMethodInvoker> methodCache = new ConcurrentHashMap<>();

  public MapperProxyFactory(Class<T> mapperInterface) {
    this.mapperInterface = mapperInterface;
  }

  public Class<T> getMapperInterface() {
    return mapperInterface;
  }

  public Map<Method, MapperMethodInvoker> getMethodCache() {
    return methodCache;
  }


  @SuppressWarnings("unchecked")
  protected T newInstance(MapperProxy<T> mapperProxy) {
    /**
     * 通过JDK动态代理创建代理对象
     */
    return (T) Proxy.newProxyInstance(mapperInterface.getClassLoader(), new Class[] { mapperInterface }, mapperProxy);
  }

  /**
   * 创建MapperProxy对象，MapperProxy实现了InvocationHandler接口，代理逻辑封装在此类中
   * @param sqlSession
   * @return
   */
  public T newInstance(SqlSession sqlSession) {
    /**
     * 创建MapperProxy对象，该对象实现了InvocationHandler接口，所以可以直接用来创建JDK动态代理对象
     */
    final MapperProxy<T> mapperProxy = new MapperProxy<>(sqlSession, mapperInterface, methodCache);
    /**
     * 通过JDK动态代理创建代理对象
     */
    return newInstance(mapperProxy);
  }

}
