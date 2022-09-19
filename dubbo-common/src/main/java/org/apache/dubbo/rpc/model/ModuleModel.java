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
package org.apache.dubbo.rpc.model;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ModuleEnvironment;
import org.apache.dubbo.common.context.ModuleExt;
import org.apache.dubbo.common.deploy.ApplicationDeployer;
import org.apache.dubbo.common.deploy.DeployState;
import org.apache.dubbo.common.deploy.ModuleDeployer;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.config.context.ModuleConfigManager;

import java.util.HashMap;
import java.util.Set;

/**
 * Model of a service module
 *
 * 属于一个service module的module，是属于一个服务模块的model组件模型
 */
public class ModuleModel extends ScopeModel {
    private static final Logger logger = LoggerFactory.getLogger(ModuleModel.class);

    public static final String NAME = "ModuleModel";

    // 引用applicationModel
    private final ApplicationModel applicationModel;
    // service module环境相关的数据，封装的都是各种各样的配置信息
    private ModuleEnvironment moduleEnvironment;
    // 属于关键性的一个组件，服务仓储。存储的都是服务的数据
    private ModuleServiceRepository serviceRepository;
    // 存储一些服务相关的配置信息
    private ModuleConfigManager moduleConfigManager;
    // module deployer组件
    // 对一些其他的组件模块，管理其生命周期，初始化、启动、停止、预销毁、销毁后处理
    private ModuleDeployer deployer;

    public ModuleModel(ApplicationModel applicationModel) {
        this(applicationModel, false);
    }

    // 在构造module model的时候，会传递进来一个application model
    // 所以module model一般来说，是把application model当做自己的父组件的
    public ModuleModel(ApplicationModel applicationModel, boolean isInternal) {
        super(applicationModel, ExtensionScope.MODULE, isInternal);
        Assert.notNull(applicationModel, "ApplicationModel can not be null");
        // 赋值
        this.applicationModel = applicationModel;
        // 把当前model加入到applicationModel里去
        applicationModel.addModule(this, isInternal);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(getDesc() + " is created");
        }

        initialize();
        Assert.notNull(getServiceRepository(), "ModuleServiceRepository can not be null");
        Assert.notNull(getConfigManager(), "ModuleConfigManager can not be null");
        Assert.assertTrue(getConfigManager().isInitialized(), "ModuleConfigManager can not be initialized");

        // notify application check state
        ApplicationDeployer applicationDeployer = applicationModel.getDeployer();
        if (applicationDeployer != null) {
            applicationDeployer.notifyModuleChanged(this, DeployState.PENDING);
        }
    }

    @Override
    protected void initialize() {
        super.initialize();
        this.serviceRepository = new ModuleServiceRepository(this);

        initModuleExt();
        // 通过SPI机制先获取到ScopeModelInitializer接口的extension loader
        // dubbo为什么说基于SPI机制，把扩展性做的特别好，他几乎把他所有的核心组件都做成可以基于SPI机制进行扩展和自定义
        ExtensionLoader<ScopeModelInitializer> initializerExtensionLoader = this.getExtensionLoader(ScopeModelInitializer.class);
        // 再通过SPI机制，去获取接口对应的extension实现类的实例
        Set<ScopeModelInitializer> initializers = initializerExtensionLoader.getSupportedExtensionInstances();
        // 做一个遍历，直接回调initializeModuleModel方法，如果说你要扩展这里
        // 你去自定义ScopeModelInitializer接口实现类，再dubbo初始化的过程中，立刻就会去回调你自己的SPI扩展
        // 这是一个dubbo留下给使用者的扩展点
        for (ScopeModelInitializer initializer : initializers) {
            initializer.initializeModuleModel(this);
        }
    }

    private void initModuleExt() {
        Set<ModuleExt> exts = this.getExtensionLoader(ModuleExt.class).getSupportedExtensionInstances();
        for (ModuleExt ext : exts) {
            ext.initialize();
        }
    }

    @Override
    protected void onDestroy() {
        // 1. remove from applicationModel
        applicationModel.removeModule(this);

        // 2. set stopping
        if (deployer != null) {
            deployer.preDestroy();
        }

        // 3. release services
        if (deployer != null) {
            deployer.postDestroy();
        }

        // destroy other resources
        notifyDestroy();

        if (serviceRepository != null) {
            serviceRepository.destroy();
            serviceRepository = null;
        }

        if (moduleEnvironment != null) {
            moduleEnvironment.destroy();
            moduleEnvironment = null;
        }

        if (moduleConfigManager != null) {
            moduleConfigManager.destroy();
            moduleConfigManager = null;
        }

        // destroy application if none pub module
        applicationModel.tryDestroy();
    }

    public ApplicationModel getApplicationModel() {
        return applicationModel;
    }

    public ModuleServiceRepository getServiceRepository() {
        return serviceRepository;
    }

    @Override
    public void addClassLoader(ClassLoader classLoader) {
        super.addClassLoader(classLoader);
        if (moduleEnvironment != null) {
            moduleEnvironment.refreshClassLoaders();
        }
    }

    @Override
    public ModuleEnvironment getModelEnvironment() {
        if (moduleEnvironment == null) {
            moduleEnvironment = (ModuleEnvironment) this.getExtensionLoader(ModuleExt.class)
                .getExtension(ModuleEnvironment.NAME);
        }
        return moduleEnvironment;
    }

    public ModuleConfigManager getConfigManager() {
        if (moduleConfigManager == null) {
            moduleConfigManager = (ModuleConfigManager) this.getExtensionLoader(ModuleExt.class)
                .getExtension(ModuleConfigManager.NAME);
        }
        return moduleConfigManager;
    }

    public ModuleDeployer getDeployer() {
        return deployer;
    }

    public void setDeployer(ModuleDeployer deployer) {
        this.deployer = deployer;
    }

    /**
     * for ut only
     */
    @Deprecated
    public void setModuleEnvironment(ModuleEnvironment moduleEnvironment) {
        this.moduleEnvironment = moduleEnvironment;
    }

    public ConsumerModel registerInternalConsumer(Class<?> internalService, URL url) {
        ServiceMetadata serviceMetadata = new ServiceMetadata();
        serviceMetadata.setVersion(url.getVersion());
        serviceMetadata.setGroup(url.getGroup());
        serviceMetadata.setDefaultGroup(url.getGroup());
        serviceMetadata.setServiceInterfaceName(internalService.getName());
        serviceMetadata.setServiceType(internalService);
        String serviceKey = URL.buildKey(internalService.getName(), url.getGroup(), url.getVersion());
        serviceMetadata.setServiceKey(serviceKey);

        ConsumerModel consumerModel = new ConsumerModel(serviceMetadata.getServiceKey(), "jdk", serviceRepository.lookupService(serviceMetadata.getServiceInterfaceName()),
            this, serviceMetadata, new HashMap<>(0), ClassUtils.getClassLoader(internalService));

        logger.info("Dynamically registering consumer model " + serviceKey + " into model " + this.getDesc());
        serviceRepository.registerConsumer(consumerModel);
        return consumerModel;
    }
}
