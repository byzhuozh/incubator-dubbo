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
package org.apache.dubbo.rpc.cluster.router;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Router;

import java.util.ArrayList;
import java.util.List;

/**
 * A specific Router designed to realize mock feature.
 * If a request is configured to use mock, then this router guarantees that only the invokers with protocol MOCK appear in final the invoker list, all other invokers will be excluded.
 *
 */
public class MockInvokersSelector implements Router {

    @Override
    public <T> List<Invoker<T>> route(final List<Invoker<T>> invokers,
                                      URL url, final Invocation invocation) throws RpcException {
        if (invocation.getAttachments() == null) {
            // attachments为null，会过滤掉 MockInvoker，只返回正常的 Invoker 对象
            return getNormalInvokers(invokers);
        } else {
            // 获得 "invocation.need.mock" 配置项
            String value = invocation.getAttachments().get(Constants.INVOCATION_NEED_MOCK);
            if (value == null)
                // invocation.need.mock为 null，会过滤掉 MockInvoker，只返回正常的Invoker对象
                return getNormalInvokers(invokers);
            else if (Boolean.TRUE.toString().equalsIgnoreCase(value)) {
                // invocation.need.mock为 true， 获得 MockedInvoker集合
                return getMockedInvokers(invokers);
            }
        }
        // invocation.need.mock为 false，则会将 MockInvoker 和 正常的Invoker一起返回
        return invokers;
    }

    /**
     * 从当前注册中心的服务列表中 筛选出 mock服务并返回
     */
    private <T> List<Invoker<T>> getMockedInvokers(final List<Invoker<T>> invokers) {
        // 不包含 MockInvoker 的情况下，直接返回 null
        if (!hasMockProviders(invokers)) {
            return null;
        }

        // 过滤掉普通 kInvoker ，创建 MockInvoker 集合
        List<Invoker<T>> sInvokers = new ArrayList<Invoker<T>>(1);
        for (Invoker<T> invoker : invokers) {
            // 过滤出协议头为 mock 的 invoker （
            // 备注：实际情况,服务端是无法发布 mock 协议的服务，原因在于 MockProtocol#export 方法会直接抛出异常，
            // 并且MockProtocol 被定义为了 final，这意味着没办法重写该方法。）
            if (invoker.getUrl().getProtocol().equals(Constants.MOCK_PROTOCOL)) {
                sInvokers.add(invoker);
            }
        }
        return sInvokers;
    }

    private <T> List<Invoker<T>> getNormalInvokers(final List<Invoker<T>> invokers) {
        // 不包含 MockInvoker 的情况下，直接返回 `invokers` 集合
        if (!hasMockProviders(invokers)) {
            return invokers;
        } else {
            // 若包含 MockInvoker 的情况下，过滤掉 MockInvoker ，创建普通 Invoker 集合
            List<Invoker<T>> sInvokers = new ArrayList<Invoker<T>>(invokers.size());
            for (Invoker<T> invoker : invokers) {
                if (!invoker.getUrl().getProtocol().equals(Constants.MOCK_PROTOCOL)) {
                    sInvokers.add(invoker);
                }
            }
            return sInvokers;
        }
    }

    private <T> boolean hasMockProviders(final List<Invoker<T>> invokers) {
        boolean hasMockProvider = false;
        for (Invoker<T> invoker : invokers) {
            // 判断协议头是否为 "mock"
            if (invoker.getUrl().getProtocol().equals(Constants.MOCK_PROTOCOL)) {
                hasMockProvider = true;
                break;
            }
        }
        return hasMockProvider;
    }

    @Override
    public URL getUrl() {
        return null;
    }

    @Override
    public int compareTo(Router o) {
        return 1;
    }

}
