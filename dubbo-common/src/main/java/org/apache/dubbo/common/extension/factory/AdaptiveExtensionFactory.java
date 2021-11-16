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
package org.apache.dubbo.common.extension.factory;

import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.ExtensionLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AdaptiveExtensionFactory
 * 由于本实现类上有@Adaptive注解，因此它才是ExtensionFactory的默认实现
 * 其本身包含ExtensionFactory的所有实现类
 * 在获取接口实例时，就遍历其他的ExtensionFactory实例。调用他们的getExtension方法
 */
@Adaptive
public class AdaptiveExtensionFactory implements ExtensionFactory {

    /**
     * ExtensionFactory 拓展对象集合
     */
    private final List<ExtensionFactory> factories;

    public AdaptiveExtensionFactory() {
        // 使用 ExtensionLoader 加载拓展对象实现类。
        ExtensionLoader<ExtensionFactory> loader = ExtensionLoader.getExtensionLoader(ExtensionFactory.class);
        List<ExtensionFactory> list = new ArrayList<ExtensionFactory>();
        /*
         * ExtensionLoader.getSupportedExtensions()返回的TreeSet集合，
         * 里面会对ExtensionFactory进行排序，默认排序会使SpiExtensionFactory实例排在前面
         * 这样就会优先从Dubbo的SPI容器中获取扩展点，如果获取不到再从SpringExtensionFactory容器中获取 。
         */
        for (String name : loader.getSupportedExtensions()) {
            list.add(loader.getExtension(name));
        }
        //并使用不可变的list存到内存中
        factories = Collections.unmodifiableList(list);
    }

    @Override
    public <T> T getExtension(Class<T> type, String name) {
        /*
         * 依次遍历各个ExtensionFactory实现的getExtension方法，一旦获取到Extension即返回
         * 如果遍历完所有的ExtensionFactory实现均无法找到Extension,则返回null
         * 获取扩展点实例，实际是调用SpiExtensionFactory,SpringExtensionFactory等的getExtension
         */
        for (ExtensionFactory factory : factories) {
            T extension = factory.getExtension(type, name);
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

}
