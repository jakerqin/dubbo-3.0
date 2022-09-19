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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.rpc.model.ScopeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ExtensionDirector is a scoped extension loader manager.
 *
 * <p></p>
 * <p>ExtensionDirector supports multiple levels, and the child can inherit the parent's extension instances. </p>
 * <p>The way to find and create an extension instance is similar to Java classloader.</p>
 */
public class ExtensionDirector implements ExtensionAccessor {

    // key就是接口类型的class对象，value就是extension loader
    private final ConcurrentMap<Class<?>, ExtensionLoader<?>> extensionLoadersMap = new ConcurrentHashMap<>(64);
    private final ConcurrentMap<Class<?>, ExtensionScope> extensionScopeMap = new ConcurrentHashMap<>(64);
    // extension director自己，是一个父级组件，会有一个树形的关系
    private final ExtensionDirector parent;
    // 获取到的扩展的实现对象，他的使用范围是有多宽
    private final ExtensionScope scope;
    // extension扩展实例的后处理器，extension实例之后需要进行后处理
    private final List<ExtensionPostProcessor> extensionPostProcessors = new ArrayList<>();
    // 每个model组件都会关联一个extension directory组件，反过来，也会关联一个model组件
    private final ScopeModel scopeModel;
    private final AtomicBoolean destroyed = new AtomicBoolean();

    // 构造参数需要传这些，parent就很关键了
    public ExtensionDirector(ExtensionDirector parent, ExtensionScope scope, ScopeModel scopeModel) {
        this.parent = parent;
        this.scope = scope;
        this.scopeModel = scopeModel;
    }

    public void addExtensionPostProcessor(ExtensionPostProcessor processor) {
        if (!this.extensionPostProcessors.contains(processor)) {
            this.extensionPostProcessors.add(processor);
        }
    }

    public List<ExtensionPostProcessor> getExtensionPostProcessors() {
        return extensionPostProcessors;
    }

    @Override
    public ExtensionDirector getExtensionDirector() {
        return this;
    }

    /**
     * extension loader的管理组件，拿到各种接口的extension loader，去拿到接口的扩展实现类
     * @param type
     * @param <T>
     * @return
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        checkDestroyed();
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type +
                ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }

        // 1. find in local cache
        // 按照你的接口的class类型去获取extension loader缓存
        ExtensionLoader<T> loader = (ExtensionLoader<T>) extensionLoadersMap.get(type);

        // 拿到作用的范围
        ExtensionScope scope = extensionScopeMap.get(type);
        if (scope == null) {
            // 从接口class里面拿到对接口打得SPI注解
            SPI annotation = type.getAnnotation(SPI.class);
            scope = annotation.scope();
            // 拿到这个SPI注解之后，就会获取这个注解里的scope范围，extension scope，并保存起来
            extensionScopeMap.put(type, scope);
        }

        // 获取出来的extension loader是空的，同时scope是self范围
        if (loader == null && scope == ExtensionScope.SELF) {
            // create an instance in self scope
            // 创建extension loader并保存
            loader = createExtensionLoader0(type);
        }

        // 2. find in parent
        // 如果说extension loader没有拿到，同时范围还不是self
        if (loader == null) {
            // 在创建的extension loader的过程中，会有父组件的依赖和搜寻
            if (this.parent != null) {
                // 通过父类
                loader = this.parent.getExtensionLoader(type);
            }
        }

        // 3. create it
        // 创建一个extension loader分为四步，第一步：先去缓存搜索；第二步：scope=self尝试自己创建；第三步：parent中搜索
        // 最后一步：直接尝试自己去创建
        if (loader == null) {
            loader = createExtensionLoader(type);
        }

        return loader;
    }

    private <T> ExtensionLoader<T> createExtensionLoader(Class<T> type) {
        ExtensionLoader<T> loader = null;
        if (isScopeMatched(type)) {
            // if scope is matched, just create it
            loader = createExtensionLoader0(type);
        }
        return loader;
    }

    @SuppressWarnings("unchecked")
    private <T> ExtensionLoader<T> createExtensionLoader0(Class<T> type) {
        checkDestroyed();
        ExtensionLoader<T> loader;
        // 直接new 了一个extensionLoader
        extensionLoadersMap.putIfAbsent(type, new ExtensionLoader<T>(type, this, scopeModel));
        // 保存起来
        loader = (ExtensionLoader<T>) extensionLoadersMap.get(type);
        return loader;
    }

    private boolean isScopeMatched(Class<?> type) {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        return defaultAnnotation.scope().equals(scope);
    }

    private static boolean withExtensionAnnotation(Class<?> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    public ExtensionDirector getParent() {
        return parent;
    }

    public void removeAllCachedLoader() {
    }

    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            for (ExtensionLoader<?> extensionLoader : extensionLoadersMap.values()) {
                extensionLoader.destroy();
            }
            extensionLoadersMap.clear();
            extensionScopeMap.clear();
            extensionPostProcessors.clear();
        }
    }

    private void checkDestroyed() {
        if (destroyed.get()) {
            throw new IllegalStateException("ExtensionDirector is destroyed");
        }
    }
}
