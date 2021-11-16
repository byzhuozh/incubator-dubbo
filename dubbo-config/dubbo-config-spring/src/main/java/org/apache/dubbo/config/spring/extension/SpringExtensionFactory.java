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
package org.apache.dubbo.config.spring.extension;

import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import java.util.Set;

/**
 * SpringExtensionFactory
 *
 * Spring ExtensionFactory 拓展实现类
 */
public class SpringExtensionFactory implements ExtensionFactory {
    private static final Logger logger = LoggerFactory.getLogger(SpringExtensionFactory.class);

    /**
     * Spring Context 集合
     */
    private static final Set<ApplicationContext> contexts = new ConcurrentHashSet<ApplicationContext>();

    public static void addApplicationContext(ApplicationContext context) {
        contexts.add(context);
    }

    public static void removeApplicationContext(ApplicationContext context) {
        contexts.remove(context);
    }

    // currently for test purpose
    public static void clearContexts() {
        contexts.clear();
    }

    /**
     * 从spring容器中获取指定class类型和名称的对象
     * @param type object type. 扩展点类型
     * @param name object name. 扩展点名称
     * @param <T> 扩展点class
     * @return 扩展点
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getExtension(Class<T> type, String name) {

        /*
         * SPI should be get from SpiExtensionFactory
         * 如果扩展类时一个接口，并且接口上由@SPI注解，就返回 null。
         * 意思是：SPI接口的扩展点实现应该从SpiExtensionFactory中获取
         */
        // SPI should be get from SpiExtensionFactory
        if (type.isInterface() && type.isAnnotationPresent(SPI.class)) {
            return null;
        }

        // 遍历Spring的上下文对象ApplicationContext
        for (ApplicationContext context : contexts) {
            if (context.containsBean(name)) {
                //通过接口实现的名称从上下文中获取接口的实例对象，如果有多个实现，默认获取第一个
                Object bean = context.getBean(name);
                // 判断类型
                if (type.isInstance(bean)) {
                    return (T) bean;
                }
            }
        }

        logger.warn("No spring extension(bean) named:" + name + ", try to find an extension(bean) of type " + type.getName());

        for (ApplicationContext context : contexts) {
            try {
                //通过接口类型从上下文中获取接口的实例对象，如果有多个实现，默认获取第一个
                return context.getBean(type);
            } catch (NoUniqueBeanDefinitionException multiBeanExe) {
                throw multiBeanExe;
            } catch (NoSuchBeanDefinitionException noBeanExe) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Error when get spring extension(bean) for type:" + type.getName(), noBeanExe);
                }
            }
        }

        logger.warn("No spring extension(bean) named:" + name + ", type:" + type.getName() + " found, stop get bean.");

        return null;
    }

}
