package org.apache.dubbo.demo.provider;

import org.apache.dubbo.demo.params.Result;
import org.apache.dubbo.rpc.service.GenericException;
import org.apache.dubbo.rpc.service.GenericService;

/**
 * @author kongwen
 * @version GenericHelloServiceImpl.java, v 0.1 2022/1/26 13:07 kongwen Exp $
 */
public class GenericHelloServiceImpl implements GenericService {

    @Override
    public Object $invoke(String method, String[] parameterTypes, Object[] args) throws GenericException {
        System.out.println("方法名：" + method + ", 参数 = " + args.toString());

        if (!method.equals("hello")) {
            return new Result(true, "成功相应");
        }

        return null;
    }
}
