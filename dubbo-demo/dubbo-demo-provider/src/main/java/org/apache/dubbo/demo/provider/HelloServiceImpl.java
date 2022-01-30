package org.apache.dubbo.demo.provider;

import org.apache.dubbo.demo.HelloService;
import org.apache.dubbo.demo.params.Result;
import org.apache.dubbo.demo.params.User;

/**
 * @author zhuozh
 * @version : HelloServiceImpl.java, v 0.1 2021/11/11 21:48 zhuozh Exp $
 */
public class HelloServiceImpl implements HelloService {

    @Override
    public void hello(String name) {
        System.out.println("HelloServiceImpl -->>> hello >>> " + name);
    }

    @Override
    public Result register(User user) {
        System.out.println("用户注册:" + user.toString());
        return new Result(true,"注册成功");
    }
}
