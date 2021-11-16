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

import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;

/**
 * SpiExtensionFactory
 *
 * SPI ExtensionFactory 拓展实现类
 */
public class SpiExtensionFactory implements ExtensionFactory {

    /**
     * 获得拓展对象
     *
     * @param type object type. 拓展接口
     * @param name object name. 拓展名
     * @param <T> 泛型
     * @return 拓展对象
     */
    @Override
    public <T> T getExtension(Class<T> type, String name) {
        // 要求type必须是一个接口，并且有@SPI注解。这是dubbo中SPI接口的标准配置
        if (type.isInterface() && type.isAnnotationPresent(SPI.class)) {     // 校验是 @SPI
            ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);

            // 返回的是扩展点名称的TreeSet集合
            if (!loader.getSupportedExtensions().isEmpty()) {
                // 使用ExtensionLoader#getAdaptiveExtension()获取默认的实现类
                return loader.getAdaptiveExtension();
            }
        }
        return null;
    }

}
