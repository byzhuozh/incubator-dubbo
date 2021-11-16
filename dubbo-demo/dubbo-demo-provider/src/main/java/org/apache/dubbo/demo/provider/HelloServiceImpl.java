package org.apache.dubbo.demo.provider;

import org.apache.dubbo.demo.HelloService;

/**
 * @author zhuozh
 * @version : HelloServiceImpl.java, v 0.1 2021/11/11 21:48 zhuozh Exp $
 */
public class HelloServiceImpl implements HelloService {

    @Override
    public void hello(String name) {
        System.out.println("HelloServiceImpl -->>> hello >>> " + name);
    }
}
