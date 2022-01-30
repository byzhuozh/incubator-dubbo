package org.apache.dubbo.demo.params;

import java.io.Serializable;

/**
 * @author kongwen
 * @version Result.java, v 0.1 2022/1/25 16:37 kongwen Exp $
 */
public class Result implements Serializable {

    private boolean success;

    private String data;

    public Result(boolean success, String data) {
        this.success = success;
        this.data = data;
    }

    @Override
    public String toString() {
        return "Result{" +
                "success=" + success +
                ", data='" + data + '\'' +
                '}';
    }
}
