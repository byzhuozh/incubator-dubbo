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

package org.apache.dubbo.common.threadpool.support.eager;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EagerThreadPoolExecutor
 *
 * 当所有核心线程数都处于忙碌状态，优先创建新线程执行任务
 */
public class EagerThreadPoolExecutor extends ThreadPoolExecutor {

    /**
     * task count
     * 记录正在执行+队列等待执行的任务
     */
    private final AtomicInteger submittedTaskCount = new AtomicInteger(0);

    public EagerThreadPoolExecutor(int corePoolSize,   //默认值0
                                   int maximumPoolSize,    //默认无限大
                                   long keepAliveTime,  //默认1分钟
                                   TimeUnit unit, TaskQueue<Runnable> workQueue,  //默认容量为1
                                   ThreadFactory threadFactory,
                                   RejectedExecutionHandler handler) {  //AbortPolicyWithReport 拒绝策略，会打印堆栈到指定路径
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
    }

    public int getSubmittedTaskCount() {
        return submittedTaskCount.get();
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        // 执行任务数依次递减
        submittedTaskCount.decrementAndGet();
    }

    @Override
    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException();
        }
        // do not increment in method beforeExecute!
        //执行任务前，submittedTaskCount++
        submittedTaskCount.incrementAndGet();
        try {
            //调用JDK原生线程池执行
            super.execute(command);
        } catch (RejectedExecutionException rx) {
            // retry to offer the task into queue.
            //失败尝试重新加入到workqueue
            final TaskQueue queue = (TaskQueue) super.getQueue();
            try {
                if (!queue.retryOffer(command, 0, TimeUnit.MILLISECONDS)) {
                    //如果重新加入失败，那么抛出异常，并且统计数-1
                    submittedTaskCount.decrementAndGet();
                    throw new RejectedExecutionException("Queue capacity is full.", rx);
                }
            } catch (InterruptedException x) {
                // 提交失败，之前的加1要扣减回来
                submittedTaskCount.decrementAndGet();
                throw new RejectedExecutionException(x);
            }
        } catch (Throwable t) {
            // decrease any way
            submittedTaskCount.decrementAndGet();
            throw t;
        }
    }
}
