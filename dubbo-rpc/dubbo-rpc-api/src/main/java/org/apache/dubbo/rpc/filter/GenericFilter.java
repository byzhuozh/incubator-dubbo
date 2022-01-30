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
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.io.UnsafeByteArrayInputStream;
import org.apache.dubbo.common.io.UnsafeByteArrayOutputStream;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.PojoUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.RpcResult;
import org.apache.dubbo.rpc.service.GenericException;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.io.IOException;
import java.lang.reflect.Method;

/**
 * GenericInvokerFilter.
 *
 * 服务消费者的泛化调用过滤器
 */
@Activate(group = Constants.PROVIDER, order = -20000)
public class GenericFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
        // 1. 消费者进行泛化调用
        // 调用方法为 $invoke && 参数有三个 && 调用接口不是 GenericService
        // 如果方法名为$invoker，并且只有3个参数，
        // 并且服务端暴露的invoker不是GenericService的相关类
        // 则认为本次服务调用时客户端泛化引用服务端，客户端的泛化调用，
        // 需要将请求参数反序列化为该接口真实的pojo对象。
        if (inv.getMethodName().equals(Constants.$INVOKE)
                && inv.getArguments() != null
                && inv.getArguments().length == 3
                && !GenericService.class.isAssignableFrom(invoker.getInterface())) {  // 非泛化实现的调用

            // 1.1 参数解析
            // 调用方法名
            String name = ((String) inv.getArguments()[0]).trim();
            // 调用方法参数类型
            String[] types = (String[]) inv.getArguments()[1];
            // 调用参数值
            Object[] args = (Object[]) inv.getArguments()[2];

            try {
                // 通过反射获取方法实例
                Method method = ReflectUtils.findMethodByMethodSignature(invoker.getInterface(), name, types);
                // 获得方法参数类型和方法参数数组
                Class<?>[] params = method.getParameterTypes();
                if (args == null) {
                    args = new Object[params.length];
                }

                // 获取泛化调用的泛化类型
                String generic = inv.getAttachment(Constants.GENERIC_KEY);

                // 泛化类型为空，则从上下文获取
                if (StringUtils.isBlank(generic)) {
                    generic = RpcContext.getContext().getAttachment(Constants.GENERIC_KEY);
                }

                // generic 为空 || 默认情况，则使用 generic=true 的方式
                if (StringUtils.isEmpty(generic)
                        || ProtocolUtils.isDefaultGenericSerialization(generic)) {
                    args = PojoUtils.realize(args, params, method.getGenericParameterTypes());

                } else if (ProtocolUtils.isJavaGenericSerialization(generic)) {
                    // generic = nativjava 的方式
                    for (int i = 0; i < args.length; i++) {
                        if (byte[].class == args[i].getClass()) {
                            try {
                                UnsafeByteArrayInputStream is = new UnsafeByteArrayInputStream((byte[]) args[i]);
                                args[i] = ExtensionLoader.getExtensionLoader(Serialization.class)
                                        .getExtension(Constants.GENERIC_SERIALIZATION_NATIVE_JAVA)
                                        .deserialize(null, is).readObject();
                            } catch (Exception e) {
                                throw new RpcException("Deserialize argument [" + (i + 1) + "] failed.", e);
                            }
                        } else {
                            throw new RpcException(
                                    "Generic serialization [" +
                                            Constants.GENERIC_SERIALIZATION_NATIVE_JAVA +
                                            "] only support message type " +
                                            byte[].class +
                                            " and your message type is " +
                                            args[i].getClass());
                        }
                    }
                // // generic = bean的方式
                } else if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] instanceof JavaBeanDescriptor) {
                            args[i] = JavaBeanSerializeUtil.deserialize((JavaBeanDescriptor) args[i]);
                        } else {
                            throw new RpcException(
                                    "Generic serialization [" +
                                            Constants.GENERIC_SERIALIZATION_BEAN +
                                            "] only support message type " +
                                            JavaBeanDescriptor.class.getName() +
                                            " and your message type is " +
                                            args[i].getClass().getName());
                        }
                    }
                }

                // 进行服务调用。这里会先传递给下一个 filter,最后进行服务调用
                Result result = invoker.invoke(new RpcInvocation(method, args, inv.getAttachments()));

                // 若是异常结果，并且非 GenericException 异常，则使用 GenericException 包装
                if (result.hasException()
                        && !(result.getException() instanceof GenericException)) {
                    return new RpcResult(new GenericException(result.getException()));
                }

                // 【第三步】`nativejava` ，序列化结果，结果 => byte[]
                if (ProtocolUtils.isJavaGenericSerialization(generic)) {
                    try {
                        UnsafeByteArrayOutputStream os = new UnsafeByteArrayOutputStream(512);
                        ExtensionLoader.getExtensionLoader(Serialization.class)
                                .getExtension(Constants.GENERIC_SERIALIZATION_NATIVE_JAVA)
                                .serialize(null, os).writeObject(result.getValue());
                        return new RpcResult(os.toByteArray());
                    } catch (IOException e) {
                        throw new RpcException("Serialize result failed.", e);
                    }

                // 【第三步】`bean` ，序列化结果，结果 => JavaBeanDescriptor
                } else if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                    return new RpcResult(JavaBeanSerializeUtil.serialize(result.getValue(), JavaBeanAccessor.METHOD));
                } else {
                    // 【第三步】`true` ，序列化结果，仅有 POJO => Map
                    return new RpcResult(PojoUtils.generalize(result.getValue()));
                }
            } catch (NoSuchMethodException e) {
                throw new RpcException(e.getMessage(), e);
            } catch (ClassNotFoundException e) {
                throw new RpcException(e.getMessage(), e);
            }
        }
        // 普通调用
        return invoker.invoke(inv);
    }

}
