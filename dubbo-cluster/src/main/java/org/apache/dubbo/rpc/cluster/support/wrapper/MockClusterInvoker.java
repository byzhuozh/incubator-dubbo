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
package org.apache.dubbo.rpc.cluster.support.wrapper;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.RpcResult;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.support.MockInvoker;

import java.util.List;

public class MockClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(MockClusterInvoker.class);
    // RegistryDirectory
    private final Directory<T> directory;

    // FailoverClusterInvoker
    private final Invoker<T> invoker;

    public MockClusterInvoker(Directory<T> directory, Invoker<T> invoker) {
        this.directory = directory;
        this.invoker = invoker;
    }

    @Override
    public URL getUrl() {
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        this.invoker.destroy();
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        Result result = null;

        // directory --> RegistryDirectory
        // 获得 "mock" 配置项，有多种配置方式
        String value = directory.getUrl().getMethodParameter(invocation.getMethodName(), Constants.MOCK_KEY, Boolean.FALSE.toString()).trim();
        //若mock参数未配置或是配置为false，则不会开启Mock机制，直接调用底层的Invoker
        if (value.length() == 0 || value.equalsIgnoreCase("false")) {  //没有mock
            //no mock   invoker --> FailoverClusterInvoker
            // 直接调用
            result = this.invoker.invoke(invocation);
        } else if (value.startsWith("force")) {  // 如果强制服务降级
            // 若mock参数配置为force，则表示强制mock，直接调用doMockInvoke()方法
            if (logger.isWarnEnabled()) {
                logger.info("force-mock: " + invocation.getMethodName() + " force-mock enabled , url : " + directory.getUrl());
            }
            //force:direct mock
            // 直接调用 Mock Invoker ，执行本地 Mock 逻辑
            result = doMockInvoke(invocation, null);
        } else {
            // 如果mock配置的不是force，那配置的就是fail，会继续调用Invoker对象的invoke()方法进行请求
            //fail-mock   失败服务降级
            try {
                result = this.invoker.invoke(invocation);
            } catch (RpcException e) {
                if (e.isBiz()) {
                    // 如果是业务异常，会直接抛出
                    throw e;
                } else {
                    if (logger.isWarnEnabled()) {
                        logger.warn("fail-mock: " + invocation.getMethodName() + " fail-mock enabled , url : " + directory.getUrl(), e);
                    }
                    // 调用失败，则服务降级，如果是非业务异常，会调用doMockInvoke()方法返回mock结果
                    result = doMockInvoke(invocation, e);
                }
            }
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Result doMockInvoke(Invocation invocation, RpcException e) {
        Result result = null;
        Invoker<T> minvoker;

        // 调用selectMockInvoker()方法过滤得到MockInvoker
        List<Invoker<T>> mockInvokers = selectMockInvoker(invocation);
        // 实际情况 mockInvokers 是为空的，因为在进行协议注册的时候，MockProtocol#export 方法并没有实现
        if (mockInvokers == null || mockInvokers.isEmpty()) {
            // 如果selectMockInvoker()方法未返回MockInvoker对象，则创建一个MockInvoker
            minvoker = (Invoker<T>) new MockInvoker(directory.getUrl());
        } else {
            minvoker = mockInvokers.get(0);
        }
        try {
            // 调用MockInvoker.invoke()方法进行mock
            result = minvoker.invoke(invocation);
        } catch (RpcException me) {
            if (me.isBiz()) {
                // 如果是业务异常，则在Result中设置该异常
                result = new RpcResult(me.getCause());
            } else {
                throw new RpcException(me.getCode(), getMockExceptionMessage(e, me), me.getCause());
            }
        } catch (Throwable me) {
            throw new RpcException(getMockExceptionMessage(e, me), me.getCause());
        }
        return result;
    }

    private String getMockExceptionMessage(Throwable t, Throwable mt) {
        String msg = "mock error : " + mt.getMessage();
        if (t != null) {
            msg = msg + ", invoke error is :" + StringUtils.toString(t);
        }
        return msg;
    }

    /**
     * Return MockInvoker
     * Contract：
     * directory.list() will return a list of normal invokers if Constants.INVOCATION_NEED_MOCK is present in invocation, otherwise, a list of mock invokers will return.
     * if directory.list() returns more than one mock invoker, only one of them will be used.
     *
     * @param invocation
     * @return
     */
    private List<Invoker<T>> selectMockInvoker(Invocation invocation) {
        List<Invoker<T>> invokers = null;
        // 如果是 RPC 调用
        if (invocation instanceof RpcInvocation) {
            // 将Invocation附属信息中的invocation.need.mock属性设置为true
            // Note the implicit contract (although the description is added to the interface declaration, but extensibility is a problem. The practice placed in the attachement needs to be improved)
            //翻译：注意隐式契约（尽管描述被添加到接口声明中，但是可扩展性是一个问题。附件中的做法需要改进）
            ((RpcInvocation) invocation).setAttachment(Constants.INVOCATION_NEED_MOCK, Boolean.TRUE.toString());
            // directory will return a list of normal invokers if Constants.INVOCATION_NEED_MOCK is present in invocation, otherwise, a list of mock invokers will return.
            // 如果调用中存在Constants.INVOCATION_NEED_MOCK，则目录将返回正常调用者列表，否则，将返回模拟调用者列表。
            try {
                invokers = directory.list(invocation);
            } catch (RpcException e) {
                if (logger.isInfoEnabled()) {
                    logger.info("Exception when try to invoke mock. Get mock invokers error for service:"
                            + directory.getUrl().getServiceInterface() + ", method:" + invocation.getMethodName()
                            + ", will contruct a new mock with 'new MockInvoker()'.", e);
                }
            }
        }
        return invokers;
    }

    @Override
    public String toString() {
        return "invoker :" + this.invoker + ",directory: " + this.directory;
    }
}
