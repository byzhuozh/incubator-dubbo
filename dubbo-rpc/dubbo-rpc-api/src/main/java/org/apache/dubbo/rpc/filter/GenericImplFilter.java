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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.beanutil.JavaBeanAccessor;
import org.apache.dubbo.common.beanutil.JavaBeanDescriptor;
import org.apache.dubbo.common.beanutil.JavaBeanSerializeUtil;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.PojoUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.service.GenericException;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * GenericImplInvokerFilter
 * <p>
 * 服务消费者的泛化调用过滤器
 */
@Activate(group = Constants.CONSUMER, value = Constants.GENERIC_KEY, order = 20000)
public class GenericImplFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(GenericImplFilter.class);

    // 泛化调用的参数类型
    private static final Class<?>[] GENERIC_PARAMETER_TYPES = new Class<?>[]{String.class, String[].class, Object[].class};

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 获得 `generic` 配置项
        String generic = invoker.getUrl().getParameter(Constants.GENERIC_KEY);
        // 1. 判断服务端是否是泛化实现, 服务端是泛化暴露，客户端不是使用泛化调用场景
        // generic 满足三种泛化情况之一 && 调用方法名不为 $invoke && 参数类型为 RpcInvocation
        if (ProtocolUtils.isGeneric(generic)
                && !Constants.$INVOKE.equals(invocation.getMethodName())
                && invocation instanceof RpcInvocation) {

            // 1.1 获取泛化调用的参数 ：调用方法名、调用参数类型、调用参数值等
            RpcInvocation invocation2 = (RpcInvocation) invocation;
            String methodName = invocation2.getMethodName();
            Class<?>[] parameterTypes = invocation2.getParameterTypes();
            Object[] arguments = invocation2.getArguments();

            // 获得参数类型数组
            String[] types = new String[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                types[i] = ReflectUtils.getName(parameterTypes[i]);
            }

            Object[] args;
            // 1.2 判断序列化方式，进行序列化
            // 如果是 Bean 序列化方式，则使用 JavaBeanSerializeUtil 进行序列化
            if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                args = new Object[arguments.length];
                for (int i = 0; i < arguments.length; i++) {
                    args[i] = JavaBeanSerializeUtil.serialize(arguments[i], JavaBeanAccessor.METHOD);
                }
            } else {
                // 否则(generic = true || nativejava) 使用PojoUtils 进行序列化
                args = PojoUtils.generalize(arguments);
            }

            // 修改调用方法的名字为 `$invoke`
            // 目的是为了让 GenericFilter 能识别出这次调用是泛化调用。
            invocation2.setMethodName(Constants.$INVOKE);
            // 设置调用方法的参数类型为 `GENERIC_PARAMETER_TYPES`
            invocation2.setParameterTypes(GENERIC_PARAMETER_TYPES);
            // 设置调用方法的参数数组，分别为方法名、参数类型数组、参数数组
            invocation2.setArguments(new Object[]{methodName, types, args});

            // 1.3 进行泛化调用
            Result result = invoker.invoke(invocation2);

            // 1.4 如果泛化调用没有异常, 则将结果集反序列化后返回。
            if (!result.hasException()) {
                Object value = result.getValue();
                try {
                    // 获得对应的方法 Method 对象
                    Method method = invoker.getInterface().getMethod(methodName, parameterTypes);

                    // 对结果进行反序列化
                    if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                        if (value == null) {
                            return new RpcResult(value);
                        } else if (value instanceof JavaBeanDescriptor) {
                            return new RpcResult(JavaBeanSerializeUtil.deserialize((JavaBeanDescriptor) value));
                        } else {  // 必须是 JavaBeanDescriptor 返回
                            throw new RpcException(
                                    "The type of result value is " +
                                            value.getClass().getName() +
                                            " other than " +
                                            JavaBeanDescriptor.class.getName() +
                                            ", and the result is " +
                                            value);
                        }
                    } else {
                        return new RpcResult(PojoUtils.realize(value, method.getReturnType(), method.getGenericReturnType()));
                    }
                } catch (NoSuchMethodException e) {
                    throw new RpcException(e.getMessage(), e);
                }
            // 反序列化异常结果
            } else if (result.getException() instanceof GenericException) {
                // 返回异常是 GenericException 类型，则说明是泛化异常而非调用过程中异常。进行处理
                GenericException exception = (GenericException) result.getException();
                try {
                    String className = exception.getExceptionClass();
                    Class<?> clazz = ReflectUtils.forName(className);
                    Throwable targetException = null;
                    Throwable lastException = null;
                    try {
                        // 创建原始异常
                        targetException = (Throwable) clazz.newInstance();
                    } catch (Throwable e) {
                        lastException = e;
                        for (Constructor<?> constructor : clazz.getConstructors()) {
                            try {
                                targetException = (Throwable) constructor.newInstance(new Object[constructor.getParameterTypes().length]);
                                break;
                            } catch (Throwable e1) {
                                lastException = e1;
                            }
                        }
                    }
                    // 设置异常的明细
                    if (targetException != null) {
                        try {
                            Field field = Throwable.class.getDeclaredField("detailMessage");
                            if (!field.isAccessible()) {
                                field.setAccessible(true);
                            }
                            field.set(targetException, exception.getExceptionMessage());
                        } catch (Throwable e) {
                            logger.warn(e.getMessage(), e);
                        }
                        // 创建新的异常 RpcResult 对象
                        result = new RpcResult(targetException);
                    } else if (lastException != null) {
                        // 创建原始异常失败，抛出异常
                        throw lastException;
                    }
                } catch (Throwable e) {  // 若发生异常，包装成 RpcException 异常，抛出。
                    throw new RpcException("Can not deserialize exception " + exception.getExceptionClass() + ", message: " + exception.getExceptionMessage(), e);
                }
            }
            return result;
        }

        // 2. 判断消费者是否开启了泛化调用, 服务端非泛化暴露，消费使用泛化调用
        // 调用方法名为 $invoke && invocation参数有三个 && generic 参数满足三种泛化方式之一
        if (invocation.getMethodName().equals(Constants.$INVOKE)   // 方法名为 `$invoke`
                && invocation.getArguments() != null
                && invocation.getArguments().length == 3
                && ProtocolUtils.isGeneric(generic)) {

            // 2.1 序列化参数
            Object[] args = (Object[]) invocation.getArguments()[2];
            // `nativejava` ，校验方法参数都为 byte[]
            if (ProtocolUtils.isJavaGenericSerialization(generic)) {

                for (Object arg : args) {
                    if (!(byte[].class == arg.getClass())) {
                        error(generic, byte[].class.getName(), arg.getClass().getName());
                    }
                }
            // `bean` ，校验方法参数为 JavaBeanDescriptor
            } else if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                for (Object arg : args) {
                    if (!(arg instanceof JavaBeanDescriptor)) {
                        error(generic, JavaBeanDescriptor.class.getName(), arg.getClass().getName());
                    }
                }
            }

            // 通过隐式参数，传递 `generic` 配置项
            ((RpcInvocation) invocation).setAttachment(
                    Constants.GENERIC_KEY, invoker.getUrl().getParameter(Constants.GENERIC_KEY));
        }

        // 普通调用
        return invoker.invoke(invocation);
    }

    private void error(String generic, String expected, String actual) throws RpcException {
        throw new RpcException(
                "Generic serialization [" +
                        generic +
                        "] only support message type " +
                        expected +
                        " and your message type is " +
                        actual);
    }

}
