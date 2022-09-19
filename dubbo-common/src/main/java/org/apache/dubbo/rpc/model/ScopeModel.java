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

import org.apache.dubbo.common.beans.factory.ScopeBeanFactory;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.extension.ExtensionAccessor;
import org.apache.dubbo.common.extension.ExtensionDirector;
import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.StringUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 它是model组件体系里 最顶层的抽象父类
 * 他实现了一个很关键的一个接口，ExtensionAccessor接口
 * 这个ExtensionAccessor，是SPI扩展实现的获取组件
 *
 * 在dubbo组件体系里，如果你要是实现了extensionAccessor接口（里面有ExtensionDirector），就代表你具备了使用
 * dubbo的SPI机制，随时可以获取指定接口的扩展实现的能力
 */
public abstract class ScopeModel implements ExtensionAccessor {
    protected static final Logger LOGGER = LoggerFactory.getLogger(ScopeModel.class);

    /**
     * The internal id is used to represent the hierarchy of the model tree, such as:
     * <ol>
     *     <li>1</li>
     *     FrameworkModel (index=1)
     *     <li>1.2</li>
     *     FrameworkModel (index=1) -> ApplicationModel (index=2)
     *     <li>1.2.0</li>
     *     FrameworkModel (index=1) -> ApplicationModel (index=2) -> ModuleModel (index=0, internal module)
     *     <li>1.2.1</li>
     *     FrameworkModel (index=1) -> ApplicationModel (index=2) -> ModuleModel (index=1, first user module)
     * </ol>
     * 根据不同的名称就代表了不同的model组件树，或者说model组件体系
     */
    private String internalId;

    /**
     * Public Model Name, can be set from user
     */
    private String modelName;

    private String desc;

    // 一批要使用的类加载器
    private Set<ClassLoader> classLoaders;

    // 这个很关键，类型也是scopeModel，model组件体系必然会基于parent属性，构建一个model组件树
    private final ScopeModel parent;
    // 一看就是跟spi机制的使用，是有关系的。它是枚举类型。代表了你在这里使用SPI机制的范围的范围是什么
    // 你有很多的model组件，不同的extension Scope范围，就决定了你创建出来的extension实例，到底是在一个model可以用
    // 还是可以跟其他的model组件可以进行共享使用，或者是别的model组件创建的extension实例就是新的
    private final ExtensionScope scope;

    // extensionDirector本质来说就是一个extension loader的manager，管理组件
    // 针对某个接口去加载到他对应的SPI扩展实例，就必须先通过extensionDirector获取到那个接口对应的extensionLoader
    // 再通过extensionLoader来获取那个接口的extension实例对象
    private ExtensionDirector extensionDirector;

    // 跟Spring中的概念很像。scope factory工厂的概念在里面
    private ScopeBeanFactory beanFactory;
    // model组件，有自己的生命周期，创建、使用、销毁，如果说model组件有自己的行为的话，此时可以在销毁之前
    // 先回调你的销毁事件的监听器，这样的话就有一个model组件的生命周期的事件监听机制在里面
    private List<ScopeModelDestroyListener> destroyListeners;

    // model组件有关联的属性数据
    private Map<String, Object> attributes;
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final boolean internalScope;

    // 构造的时候支持传入一个parent scopeModel
    public ScopeModel(ScopeModel parent, ExtensionScope scope, boolean isInternal) {
        this.parent = parent;
        this.scope = scope;
        this.internalScope = isInternal;
    }

    /**
     * NOTE:
     * <ol>
     *  <li>The initialize method only be called in subclass.</li>
     * <li>
     * In subclass, the extensionDirector and beanFactory are available in initialize but not available in constructor.
     * </li>
     * </ol>
     */
    protected void initialize() {
        // 直接new一个extensionDirector，这个是SPI核心机制的manager组件
        this.extensionDirector = new ExtensionDirector(parent != null ? parent.getExtensionDirector() : null, scope, this);
        // 添加extension post processor
        this.extensionDirector.addExtensionPostProcessor(new ScopeModelAwareExtensionProcessor(this));
        // 构建出一个dubbo内部的bean容器
        this.beanFactory = new ScopeBeanFactory(parent != null ? parent.getBeanFactory() : null, extensionDirector);
        this.destroyListeners = new LinkedList<>();
        this.attributes = new ConcurrentHashMap<>();
        this.classLoaders = new ConcurrentHashSet<>();

        // Add Framework's ClassLoader by default
        ClassLoader dubboClassLoader = ScopeModel.class.getClassLoader();
        if (dubboClassLoader != null) {
            this.addClassLoader(dubboClassLoader);
        }
    }

    /**
     * model组件要被销毁了
     */
    public void destroy() {
        // CAS操作 多线程并发访问这个方法，是线程安全的
        if (destroyed.compareAndSet(false, true)) {
            try {
                // 调用onDestroy 那么destroyListeners里的监听器就会进行回调
                onDestroy();
                HashSet<ClassLoader> copyOfClassLoaders = new HashSet<>(classLoaders);
                for (ClassLoader classLoader : copyOfClassLoaders) {
                    // 做一些清理型的操作
                    removeClassLoader(classLoader);
                }
                if (beanFactory != null) {
                    beanFactory.destroy();
                }
                if (extensionDirector != null) {
                    extensionDirector.destroy();
                }
            } catch (Throwable t) {
                LOGGER.error("Error happened when destroying ScopeModel.", t);
            }
        }
    }

    public boolean isDestroyed() {
        return destroyed.get();
    }

    protected void notifyDestroy() {
        for (ScopeModelDestroyListener destroyListener : destroyListeners) {
            destroyListener.onDestroy(this);
        }
    }

    protected void notifyProtocolDestroy() {
        for (ScopeModelDestroyListener destroyListener : destroyListeners) {
            if (destroyListener.isProtocol()) {
                destroyListener.onDestroy(this);
            }
        }
    }

    protected abstract void onDestroy();

    public final void addDestroyListener(ScopeModelDestroyListener listener) {
        destroyListeners.add(listener);
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public <T> T getAttribute(String key, Class<T> type) {
        return (T) attributes.get(key);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @Override
    public ExtensionDirector getExtensionDirector() {
        return extensionDirector;
    }

    public ScopeBeanFactory getBeanFactory() {
        return beanFactory;
    }

    public ScopeModel getParent() {
        return parent;
    }

    public ExtensionScope getScope() {
        return scope;
    }

    public void addClassLoader(ClassLoader classLoader) {
        this.classLoaders.add(classLoader);
        if (parent != null) {
            parent.addClassLoader(classLoader);
        }
        extensionDirector.removeAllCachedLoader();
    }

    public void removeClassLoader(ClassLoader classLoader) {
        if (checkIfClassLoaderCanRemoved(classLoader)) {
            this.classLoaders.remove(classLoader);
            if (parent != null) {
                parent.removeClassLoader(classLoader);
            }
            extensionDirector.removeAllCachedLoader();
        }
    }

    protected boolean checkIfClassLoaderCanRemoved(ClassLoader classLoader) {
        return classLoader != null && !classLoader.equals(ScopeModel.class.getClassLoader());
    }

    public Set<ClassLoader> getClassLoaders() {
        return Collections.unmodifiableSet(classLoaders);
    }

    public abstract Environment getModelEnvironment();

    public String getInternalId() {
        return this.internalId;
    }

    void setInternalId(String internalId) {
        this.internalId = internalId;
    }

    protected String buildInternalId(String parentInternalId, long childIndex) {
        // FrameworkModel    1
        // ApplicationModel  1.1
        // ModuleModel       1.1.1
        if (StringUtils.hasText(parentInternalId)) {
            return parentInternalId + "." + childIndex;
        } else {
            return "" + childIndex;
        }
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
        this.desc = buildDesc();
    }

    public boolean isInternal() {
        return internalScope;
    }

    /**
     * @return to describe string of this scope model
     */
    public String getDesc() {
        if (this.desc == null) {
            this.desc = buildDesc();
        }
        return this.desc;
    }

    private String buildDesc() {
        // Dubbo Framework[1]
        // Dubbo Application[1.1](appName)
        // Dubbo Module[1.1.1](appName/moduleName)
        String type = this.getClass().getSimpleName().replace("Model", "");
        String desc = "Dubbo " + type + "[" + this.getInternalId() + "]";

        // append model name path
        String modelNamePath = this.getModelNamePath();
        if (StringUtils.hasText(modelNamePath)) {
            desc += "(" + modelNamePath + ")";
        }
        return desc;
    }

    private String getModelNamePath() {
        if (this instanceof ApplicationModel) {
            return safeGetAppName((ApplicationModel) this);
        } else if (this instanceof ModuleModel) {
            String modelName = this.getModelName();
            if (StringUtils.hasText(modelName)) {
                // appName/moduleName
                return safeGetAppName(((ModuleModel) this).getApplicationModel()) + "/" + modelName;
            }
        }
        return null;
    }

    private static String safeGetAppName(ApplicationModel applicationModel) {
        String modelName = applicationModel.getModelName();
        if (StringUtils.isBlank(modelName)) {
            modelName = "unknown"; // unknown application
        }
        return modelName;
    }

    @Override
    public String toString() {
        return getDesc();
    }
}
