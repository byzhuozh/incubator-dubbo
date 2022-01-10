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

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * TaskQueue in the EagerThreadPoolExecutor
 * It offer a task if the executor's submittedTaskCount less than currentPoolThreadSize
 * or the currentPoolThreadSize more than executor's maximumPoolSize.
 * That can make the executor create new worker
 * when the task num is bigger than corePoolSize but less than maximumPoolSize.
 */
public class TaskQueue<R extends Runnable> extends LinkedBlockingQueue<Runnable> {

    private static final long serialVersionUID = -2635853580887179627L;

    //和dubbo自己的EagerThreadPoolExecutor 深度配合，两者合作实现这种调度
    private EagerThreadPoolExecutor executor;

    public TaskQueue(int capacity) {
        super(capacity);
    }

    public void setExecutor(EagerThreadPoolExecutor exec) {
        executor = exec;
    }

    // 覆盖JDK默认的offer方法，融入了 EagerThreadPoolExecutor 的属性读取
    @Override
    public boolean offer(Runnable runnable) {
        //TaskQueue持有executor引用，用于获取当前提交任务数
        if (executor == null) {
            throw new RejectedExecutionException("The task queue does not have executor!");
        }

        //当前线程池中的worker数（线程数）
        int currentPoolThreadSize = executor.getPoolSize();
        //如果提交任务数小于当前工作线程数，说明当前工作线程足够处理任务，将提交的任务插入到工作队列
        // have free worker. put task into queue to let the worker deal with task.
        if (executor.getSubmittedTaskCount() < currentPoolThreadSize) {
            return super.offer(runnable);  //提交任务数<当前线程数，直接提交到队列，等待执行
        }

        //如果提交任务数大于当前工作线程数并且小于最大线程数，说明提交的任务量线程已经处理不过来，那么需要增加线程数，返回false
        // return false to let executor create new worker.
        if (currentPoolThreadSize < executor.getMaximumPoolSize()) {
            return false;  //当前线程数 < 小于线程池设定的最大允许线程数，则需要创建新的worker（线程）进行执行
        }

        // currentPoolThreadSize >= max 提交任务数 >= 当前线程数，且线程池线程数已达到最大值；那么提交到队列等待执行
        //工作线程数到达最大线程数，插入到workqueue
        // currentPoolThreadSize >= max
        return super.offer(runnable);

        // 1：currentPoolThreadSize < 核心线程数 ：如果走了第一步，任务入列，立马被线程worker取走运行，可减少线程创建带来的资源损耗；原生的JDK线程池，会继续创建线程
        // 2：currentPoolThreadSize >= 核心线程数 && currentPoolThreadSize <= 允许最大线程数
        //2.1：队列已满：和原生JDK线程池无差别，都需要创建线程
        //2.2：队列未满：原生JDK线程池入列，等待worker取出运行（此时线程池中的线程正在处理其他任务）；而EagerThreadPoolExecutor 则需要创建线程
        // 3：currentPoolThreadSize > 最大线程数：和原生的无差别，都需要入列等待执行。
        // 总结：相对原生线程池来说，理论上来说，任务处理效率来说稍微高点；反应到RPC来说，响应会快点
        // 默认值：核心线程数=0；最大线程数不做限制；队列容量为1；线程空间回收时间为1分钟；不建议使用其默认值，在高并发下，线程数会不断增长，增大系统风险性；
        // 如果需要使用该线程池策略，最好基于实际压测情况来选择合适的参数设置；个人推测这也是dubbo不管是服务侧还是消费侧，都不选择该线程池作为默认线程池的原因。
    }

    /**
     * retry offer task
     *
     * @param o task
     * @return offer success or not
     * @throws RejectedExecutionException if executor is terminated.
     */
    public boolean retryOffer(Runnable o, long timeout, TimeUnit unit) throws InterruptedException {
        if (executor.isShutdown()) {
            throw new RejectedExecutionException("Executor is shutdown!");
        }
        return super.offer(o, timeout, unit);
    }
}
