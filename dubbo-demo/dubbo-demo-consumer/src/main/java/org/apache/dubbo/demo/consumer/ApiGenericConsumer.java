package org.apache.dubbo.demo.consumer;

import com.google.common.collect.Maps;
import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.beanutil.JavaBeanDescriptor;
import org.apache.dubbo.common.beanutil.JavaBeanSerializeUtil;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.io.UnsafeByteArrayInputStream;
import org.apache.dubbo.common.io.UnsafeByteArrayOutputStream;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.demo.HelloService;
import org.apache.dubbo.demo.params.Result;
import org.apache.dubbo.demo.params.User;
import org.apache.dubbo.rpc.service.GenericService;

import java.io.IOException;
import java.util.Map;

public class ApiGenericConsumer {
    public static void main(String[] args) throws IOException, ClassNotFoundException {
        // 1. 设置泛型参数为 GenericService
//        ReferenceConfig<GenericService> referenceConfig = new ReferenceConfig<>();
//        referenceConfig.setApplication(new ApplicationConfig("generic-consumer"));
//        referenceConfig.setRegistry(new RegistryConfig("zookeeper://101.43.158.127:2181"));
//        referenceConfig.setTimeout(5000);
//        referenceConfig.setVersion("1.0.0");
//        referenceConfig.setGroup("dubbo");
//        genericTrue(referenceConfig);
//        genericBean(referenceConfig);
//        genericNativejava(referenceConfig);

//        genericOrignClass();
    }

    public static void genericOrignClass() {
        ReferenceConfig<HelloService> referenceConfig = new ReferenceConfig<>();
        referenceConfig.setApplication(new ApplicationConfig("generic-consumer"));
        referenceConfig.setRegistry(new RegistryConfig("zookeeper://101.43.158.127:2181"));
        referenceConfig.setTimeout(5000);
//        referenceConfig.setVersion("1.0.0");
        referenceConfig.setGroup("dubbo");

        // 设置为泛化引用，类型为 true。 返回调用的接口为 api.GenericService
        referenceConfig.setInterface("org.apache.dubbo.demo.HelloService");
//        referenceConfig.setGeneric(Constants.GENERIC_SERIALIZATION_DEFAULT);

        HelloService helloService = referenceConfig.get();
        Result result = helloService.register(new User(1, "哈哈哈"));
        System.out.println("结果: " + result.toString());

    }

    /**
     * generic = true 的方式
     * 输出:
     * sayHello = GenericServiceImpl ：attribute = null name = generic
     * genericResult = {data={"id":"no.1","name":"张三"}, class=pojo.Result}
     *
     * @param referenceConfig
     */
    private static void genericTrue(ReferenceConfig<GenericService> referenceConfig) {
        // 设置为泛化引用，类型为 true。 返回调用的接口为 api.GenericService
        referenceConfig.setInterface("org.apache.dubbo.demo.HelloService");
        referenceConfig.setGeneric(Constants.GENERIC_SERIALIZATION_DEFAULT);

        // 使用 GenericService 代替所有接口引用
        GenericService genericService = referenceConfig.get();
        // 简单的泛型调用 ： 基本属性类型以及Date、List、Map 等不需要转换，直接调用，如果返回值为 POJO，则会自动转化为 Map
        Object sayHello = genericService.$invoke("hello", new String[]{"java.lang.String"}, new Object[]{"哈哈哈"});
        System.out.println("hello = " + sayHello);

        // 入参返回值都为 POJO 的调用
//        Map<String, Object> map = Maps.newHashMap();
//        map.put("class", "pojo.Pojo");
//        map.put("id", "no.1");
//        map.put("name", "张三");
//        Object genericResult = genericService.$invoke("seyHelloByGeneric", new String[]{"pojo.Pojo"}, new Object[]{map});
//        System.out.println("genericResult = " + genericResult);
    }

    /**
     * generic = bean 的方式
     * 输出：
     * sayHello = GenericServiceImpl ：attribute = null name = genericBean
     * pojo 类型好像无法调用
     *
     * @param referenceConfig
     */
    private static void genericBean(ReferenceConfig<GenericService> referenceConfig) {
        // 设置为泛化引用，类型为 true。 返回调用的接口为 api.GenericService
        referenceConfig.setInterface("api.GenericService");
        referenceConfig.setGeneric(Constants.GENERIC_SERIALIZATION_BEAN);
        // 使用 GenericService 代替所有接口引用
        GenericService genericService = referenceConfig.get();
        // 简单的泛型调用 ： 基本属性类型以及Date、List、Map 等不需要转换，直接调用，如果返回值为 POJO，则会自动转化为 Map
        JavaBeanDescriptor genericBean = JavaBeanSerializeUtil.serialize("genericBean");
        Object sayHello = genericService.$invoke("sayHello", new String[]{"java.lang.String"}, new Object[]{genericBean});
        // 因为服务提供方会对返回结果进行序列化， 所以对结果进行反序列化。
        System.out.println("sayHello = " + JavaBeanSerializeUtil.deserialize((JavaBeanDescriptor) sayHello));
    }

    /**
     * generic = nativejava 方式
     * 输出
     * sayHello = GenericServiceImpl ：attribute = null name = genericNativejava
     *
     * @param referenceConfig
     */
    private static void genericNativejava(ReferenceConfig<GenericService> referenceConfig) throws IOException, ClassNotFoundException {
        // 设置为泛化引用，类型为 true。 返回调用的接口为 api.GenericService
        referenceConfig.setInterface("api.GenericService");
        referenceConfig.setGeneric(Constants.GENERIC_SERIALIZATION_NATIVE_JAVA);
        // 使用 GenericService 代替所有接口引用
        GenericService genericService = referenceConfig.get();
        UnsafeByteArrayOutputStream out = new UnsafeByteArrayOutputStream();
        // 泛型调用，需要把参数使用Java序列化为二进制数据
        ExtensionLoader.getExtensionLoader(Serialization.class)
                .getExtension(Constants.GENERIC_SERIALIZATION_NATIVE_JAVA)
                .serialize(null, out).writeObject("genericNativejava");
        // 简单的泛型调用 ： 基本属性类型以及Date、List、Map 等不需要转换，直接调用，如果返回值为 POJO，则会自动转化为 Map
        JavaBeanDescriptor genericBean = JavaBeanSerializeUtil.serialize("genericBean");
        Object sayHello = genericService.$invoke("sayHello", new String[]{"java.lang.String"}, new Object[]{out.toByteArray()});
        UnsafeByteArrayInputStream in = new UnsafeByteArrayInputStream((byte[]) sayHello);
        Object result = ExtensionLoader.getExtensionLoader(Serialization.class)
                .getExtension(Constants.GENERIC_SERIALIZATION_NATIVE_JAVA)
                .deserialize(null, in).readObject();
        System.out.println("sayHello = " + result);
    }
}
