/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.support;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.PojoUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.RpcResult;
import com.alibaba.fastjson.JSON;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final public class MockInvoker<T> implements Invoker<T> {
    private final static ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    private final static Map<String, Invoker<?>> mocks = new ConcurrentHashMap<String, Invoker<?>>();
    private final static Map<String, Throwable> throwables = new ConcurrentHashMap<String, Throwable>();

    private final URL url;

    public MockInvoker(URL url) {
        this.url = url;
    }

    public static Object parseMockValue(String mock) throws Exception {
        return parseMockValue(mock, null);
    }

    public static Object parseMockValue(String mock, Type[] returnTypes) throws Exception {
        Object value = null;
        // 未赋值的对象，即 new XXX() 对象
        if ("empty".equals(mock)) {
            value = ReflectUtils.getEmptyObject(returnTypes != null && returnTypes.length > 0 ? (Class<?>) returnTypes[0] : null);
        } else if ("null".equals(mock)) {
            value = null;
        } else if ("true".equals(mock)) {
            value = true;
        } else if ("false".equals(mock)) {
            value = false;
        } else if (mock.length() >= 2 && (mock.startsWith("\"") && mock.endsWith("\"")
                || mock.startsWith("\'") && mock.endsWith("\'"))) {
            value = mock.subSequence(1, mock.length() - 1);
        } else if (returnTypes != null && returnTypes.length > 0 && returnTypes[0] == String.class) {
            value = mock;
        } else if (StringUtils.isNumeric(mock)) {
            value = JSON.parse(mock);
        } else if (mock.startsWith("{")) {
            value = JSON.parseObject(mock, Map.class);
        } else if (mock.startsWith("[")) {
            value = JSON.parseObject(mock, List.class);
        } else {
            value = mock;
        }
        if (returnTypes != null && returnTypes.length > 0) {
            value = PojoUtils.realize(value, (Class<?>) returnTypes[0], returnTypes.length > 1 ? returnTypes[1] : null);
        }
        return value;
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        // 获取mock值(会从URL中的methodName.mock参数或mock参数获取)
        String mock = getUrl().getParameter(invocation.getMethodName() + "." + Constants.MOCK_KEY);
        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(this);
        }

        // 如果mock为空
        if (StringUtils.isBlank(mock)) {
            mock = getUrl().getParameter(Constants.MOCK_KEY);
        }

        // 没有配置mock值，直接抛出异常
        if (StringUtils.isBlank(mock)) {
            throw new RpcException(new IllegalAccessException("mock can not be null. url :" + url));
        }

        // mock值进行处理，去除"force:"、"fail:"前缀等
        mock = normallizeMock(URL.decode(mock));

        // 等于 "return " ，返回值为空的 RpcResult 对象
        if (Constants.RETURN_PREFIX.trim().equalsIgnoreCase(mock.trim())) {
            RpcResult result = new RpcResult();
            result.setValue(null);
            return result;

        // mock值以return开头
        } else if (mock.startsWith(Constants.RETURN_PREFIX)) {
            mock = mock.substring(Constants.RETURN_PREFIX.length()).trim();
            mock = mock.replace('`', '"');
            try {
                // 获取响应结果的类型
                Type[] returnTypes = RpcUtils.getReturnTypes(invocation);
                // 根据结果类型，对mock值中结果值进行转换
                Object value = parseMockValue(mock, returnTypes);
                // 将固定的mock值设置到Result中
                return new RpcResult(value);
            } catch (Exception ew) {
                throw new RpcException("mock return invoke error. method :" + invocation.getMethodName() + ", mock:" + mock + ", url: " + url, ew);
            }

        // mock值以throw开头
        } else if (mock.startsWith(Constants.THROW_PREFIX)) {
            mock = mock.substring(Constants.THROW_PREFIX.length()).trim();
            mock = mock.replace('`', '"');
            if (StringUtils.isBlank(mock)) {
                // 未指定异常类型，直接抛出RpcException
                throw new RpcException(" mocked exception for Service degradation. ");
            } else { // user customized class
                // 抛出自定义异常
                Throwable t = getThrowable(mock);
                throw new RpcException(RpcException.BIZ_EXCEPTION, t);
            }
        } else { //impl mock
            try {
                // 创建 Invoker 对象
                Invoker<T> invoker = getInvoker(mock);
                // 执行 Invoker 对象的调用逻辑， 得到mock结果
                return invoker.invoke(invocation);
            } catch (Throwable t) {
                throw new RpcException("Failed to create mock implemention class " + mock, t);
            }
        }
    }

    private Throwable getThrowable(String throwstr) {
        // 从缓存中，获得 Throwable 对象
        Throwable throwable = (Throwable) throwables.get(throwstr);
        if (throwable != null) {
            return throwable;
        } else {
            // 不存在，创建 Throwable 对象
            Throwable t = null;
            try {
                // 获得异常类
                Class<?> bizException = ReflectUtils.forName(throwstr);
                Constructor<?> constructor;
                constructor = ReflectUtils.findConstructor(bizException, String.class);
                // 创建 Throwable 对象
                t = (Throwable) constructor.newInstance(new Object[]{" mocked exception for Service degradation. "});
                if (throwables.size() < 1000) {
                    throwables.put(throwstr, t);
                }
            } catch (Exception e) {
                throw new RpcException("mock throw error :" + throwstr + " argument error.", e);
            }
            return t;
        }
    }

    @SuppressWarnings("unchecked")
    private Invoker<T> getInvoker(String mockService) {
        // 从缓存中，获得 Invoker 对象
        Invoker<T> invoker = (Invoker<T>) mocks.get(mockService);
        if (invoker != null) {
            return invoker;
        } else {
            // 1. 获得接口类
            Class<T> serviceType = (Class<T>) ReflectUtils.forName(url.getServiceInterface());

            // 2. 若为 `true` `default` ，修改修改为对应接口 + "Mock" 类。这种情况出现在原始 `mock = fail:true` 或 `mock = force:true` 等情况
            if (ConfigUtils.isDefault(mockService)) {
                // 如果mock为 true 或 default 值，会在服务接口后添加Mock字符串，得到对应的实现类名称，并进行实例化
                mockService = serviceType.getName() + "Mock";
            }

            // 3. 获得 Mock 类
            Class<?> mockClass = ReflectUtils.forName(mockService);

            // 4. 检查mockClass是否实现了接口类
            if (!serviceType.isAssignableFrom(mockClass)) {
                throw new IllegalArgumentException("The mock implemention class " + mockClass.getName() + " not implement interface " + serviceType.getName());
            }

            try {
                // 5.创建 Mock 对象
                T mockObject = (T) mockClass.newInstance();
                // 6. 创建 Mock 对应，对应的 Invoker 对象
                invoker = proxyFactory.getInvoker(mockObject, (Class<T>) serviceType, url);

                // 7. 添加到缓存
                if (mocks.size() < 10000) {
                    // 写入缓存
                    mocks.put(mockService, invoker);
                }
                return invoker;
            } catch (InstantiationException e) {
                throw new IllegalStateException("No such empty constructor \"public " + mockClass.getSimpleName() + "()\" in mock implemention class " + mockClass.getName(), e);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    //mock=fail:throw
    //mock=fail:return
    //mock=xx.Service
    private String normallizeMock(String mock) {
        // 若为空，直接返回
        if (mock == null || mock.trim().length() == 0) {
            return mock;

        // 若果为 "true" "default" "fail" "force" 四种字符串，修改为对应接口 + "Mock" 类
        } else if (ConfigUtils.isDefault(mock) || "fail".equalsIgnoreCase(mock.trim()) || "force".equalsIgnoreCase(mock.trim())) {
            mock = url.getServiceInterface() + "Mock";
        }

        // 若以 "fail:" 开头，去掉该开头
        if (mock.startsWith(Constants.FAIL_PREFIX)) {
            mock = mock.substring(Constants.FAIL_PREFIX.length()).trim();

        // 若以 "force:" 开头，去掉该开头
        } else if (mock.startsWith(Constants.FORCE_PREFIX)) {
            mock = mock.substring(Constants.FORCE_PREFIX.length()).trim();
        }
        return mock;
    }

    @Override
    public URL getUrl() {
        return this.url;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void destroy() {
        //do nothing
    }

    @Override
    public Class<T> getInterface() {
        //FIXME
        return null;
    }
}
