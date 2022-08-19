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
package org.apache.dubbo.demo.provider;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.MetadataReportConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.demo.DemoService;

import java.util.concurrent.CountDownLatch;

public class Application {
    public static void main(String[] args) throws Exception {
        if (isClassic(args)) {
            startWithExport();
        } else {
            startWithBootstrap();
        }
    }

    private static boolean isClassic(String[] args) {
        return args.length > 0 && "classic".equalsIgnoreCase(args[0]);
    }

    private static void startWithBootstrap() {
        // serviceConfig可以理解成dubbo服务的一些配置信息
        // 其中泛型是接口的实现类
        ServiceConfig<DemoServiceImpl> service = new ServiceConfig<>();
        // 设置服务暴露的接口
        service.setInterface(DemoService.class);
        service.setRef(new DemoServiceImpl());

        DubboBootstrap bootstrap = DubboBootstrap.getInstance();
        bootstrap.application(new ApplicationConfig("dubbo-demo-api-provider"))
            .registry(new RegistryConfig("zookeeper://127.0.0.1:2181"))
            .protocol(new ProtocolConfig(CommonConstants.DUBBO, -1))
            .service(service)
            .start()
            .await();
    }

    private static void startWithExport() throws InterruptedException {
        // serviceConfig可以理解成dubbo服务的一些配置信息
        // 其中泛型是接口的实现类
        ServiceConfig<DemoServiceImpl> service = new ServiceConfig<>();
        // 设置服务暴露的接口
        service.setInterface(DemoService.class);
        // 进一步明确设置暴露接口的实现代码
        service.setRef(new DemoServiceImpl());
        // application name一般就是服务名称
        service.setApplication(new ApplicationConfig("dubbo-demo-api-provider"));
        // rpc框架必须要跟注册中心配合使用，服务启动之后必须向注册中心进行注册
        // 注册中心是知道每一个服务有几个实力，每个实例在那台服务器上
        // 设置zk作为注册中心
        service.setRegistry(new RegistryConfig("zookeeper://127.0.0.1:2181"));
        // 元数据上报配置，dubbo服务实例启动之后，肯定会有自己的元数据，必须上报到zk
        service.setMetadataReportConfig(new MetadataReportConfig("zookeeper://127.0.0.1:2181"));
        // 服务对外暴露
        service.export();

        System.out.println("dubbo service started");
        new CountDownLatch(1).await();
    }
}
