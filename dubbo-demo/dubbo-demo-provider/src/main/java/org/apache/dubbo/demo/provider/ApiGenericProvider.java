package org.apache.dubbo.demo.provider;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.demo.HelloService;
import org.apache.dubbo.rpc.service.GenericService;

import java.io.IOException;

public class ApiGenericProvider {
    public static void main(String[] args) throws IOException {

//        ServiceConfig<GenericService> serviceServiceConfig = new ServiceConfig<>();
        ServiceConfig<HelloService> serviceServiceConfig = new ServiceConfig<>();
        // 设置服务名称
        serviceServiceConfig.setApplication(new ApplicationConfig("generic-provider"));
        // 设置注册中心地址
        RegistryConfig registryConfig = new RegistryConfig("zookeeper://101.43.158.127:2181");
        serviceServiceConfig.setRegistry(registryConfig);
        // 设置暴露接口
        serviceServiceConfig.setInterface(HelloService.class);
        serviceServiceConfig.setRef(new HelloServiceImpl());
//        serviceServiceConfig.setRef(new GenericHelloServiceImpl());

        // 设置版本号和分组 服务接口 + 服务分组 + 服务版本号确定唯一服务
//        serviceServiceConfig.setVersion("1.0.0");
        serviceServiceConfig.setGroup("dubbo");
        serviceServiceConfig.setGeneric("true");
//        serviceServiceConfig.setGeneric(Constants.GENERIC_SERIALIZATION_BEAN);

        // 设置线程池策略
//        HashMap<String, String> objectObjectHashMap = Maps.newHashMap();
//        objectObjectHashMap.put("threadpool", "mythreadpool");
//        serviceServiceConfig.setParameters(objectObjectHashMap);
        // 暴露服务
        serviceServiceConfig.export();
        // 挂起线程
        System.out.println("service is start");
        System.in.read();
    }
}
