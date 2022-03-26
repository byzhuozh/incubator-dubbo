package org.apache.dubbo.demo;

import org.apache.dubbo.demo.params.Result;
import org.apache.dubbo.demo.params.User;

/**
 * @author zhuozh
 * @version : HelloService.java, v 0.1 2021/11/11 21:47 zhuozh Exp $
 */
public interface HelloService {

    void hello(String name);

    Result register(User user);
}
