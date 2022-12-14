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
package org.apache.dubbo.common.beans.factory;

import org.apache.dubbo.common.beans.ScopeBeanException;
import org.apache.dubbo.common.beans.support.InstantiationStrategy;
import org.apache.dubbo.common.extension.ExtensionAccessor;
import org.apache.dubbo.common.extension.ExtensionAccessorAware;
import org.apache.dubbo.common.extension.ExtensionPostProcessor;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.resource.Disposable;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.model.ScopeModelAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A bean factory for internal sharing.
 *
 * 它是dubbo框架自己内部实现的一个bean工厂，bean工厂是管理的这些bean，用于在dubbo框架内部进行共享的
 */
public class ScopeBeanFactory {

    protected static final Logger LOGGER = LoggerFactory.getLogger(ScopeBeanFactory.class);

    // 父级组件。可以组成一棵树
    private final ScopeBeanFactory parent;
    // extension扩展实例的获取组件，有了这个东西，就有了对SPI机制使用的权限
    private ExtensionAccessor extensionAccessor;
    // extension实例后处理器
    private List<ExtensionPostProcessor> extensionPostProcessors;
    // 每一个class都有一个AtomicInteger作为一个计数器
    private Map<Class, AtomicInteger> beanNameIdCounterMap = new ConcurrentHashMap<>();
    // 注册过的bean实例信息
    private List<BeanInfo> registeredBeanInfos = new CopyOnWriteArrayList<>();
    // 初始化策略逻辑，用于bean实例的初始化
    private InstantiationStrategy instantiationStrategy;
    private AtomicBoolean destroyed = new AtomicBoolean();

    public ScopeBeanFactory(ScopeBeanFactory parent, ExtensionAccessor extensionAccessor) {
        this.parent = parent;
        this.extensionAccessor = extensionAccessor;
        extensionPostProcessors = extensionAccessor.getExtensionDirector().getExtensionPostProcessors();
        initInstantiationStrategy();
    }

    private void initInstantiationStrategy() {
        for (ExtensionPostProcessor extensionPostProcessor : extensionPostProcessors) {
            if (extensionPostProcessor instanceof ScopeModelAccessor) {
                instantiationStrategy = new InstantiationStrategy((ScopeModelAccessor) extensionPostProcessor);
                break;
            }
        }
        if (instantiationStrategy == null) {
            instantiationStrategy = new InstantiationStrategy();
        }
    }

    public <T> T registerBean(Class<T> bean) throws ScopeBeanException {
        // 参数name一开始为空。或者说可以为空
        return this.getOrRegisterBean(null, bean);
    }


    public <T> T registerBean(String name, Class<T> clazz) throws ScopeBeanException {
        return getOrRegisterBean(name, clazz);
    }

    private <T> T createAndRegisterBean(String name, Class<T> clazz) {
        checkDestroyed();
        T instance = getBean(name, clazz);
        if (instance != null) {
            throw new ScopeBeanException("already exists bean with same name and type, name=" + name + ", type=" + clazz.getName());
        }
        try {
            // 直接就用你的实现类的class，初始化一个实例对象
            instance = instantiationStrategy.instantiate(clazz);
        } catch (Throwable e) {
            throw new ScopeBeanException("create bean instance failed, type=" + clazz.getName(), e);
        }
        // 注册bean
        registerBean(name, instance);
        return instance;
    }

    public void registerBean(Object bean) {
        this.registerBean(null, bean);
    }

    public void registerBean(String name, Object bean) {
        checkDestroyed();
        // avoid duplicated register same bean
        if (containsBean(name, bean)) {
            return;
        }

        Class<?> beanClass = bean.getClass();
        if (name == null) {
            // 生成name
            name = beanClass.getName() + "#" + getNextId(beanClass);
        }
        // 初始化bean，设置一些属性。设置ExtensionAccessor、后处理器
        initializeBean(name, bean);

        registeredBeanInfos.add(new BeanInfo(name, bean));
    }

    public <T> T getOrRegisterBean(Class<T> type) {
        return getOrRegisterBean(null, type);
    }

    public <T> T getOrRegisterBean(String name, Class<T> type) {
        T bean = getBean(name, type);
        // bean为空，double check获取bean
        if (bean == null) {
            // lock by type
            synchronized (type) {
                bean = getBean(name, type);
                if (bean == null) {
                    // 实在获取不到，创建
                    bean = createAndRegisterBean(name, type);
                }
            }
        }
        return bean;
    }

    public <T> T getOrRegisterBean(Class<T> type, Function<? super Class<T>, ? extends T> mappingFunction) {
        return getOrRegisterBean(null, type, mappingFunction);
    }

    public <T> T getOrRegisterBean(String name, Class<T> type, Function<? super Class<T>, ? extends T> mappingFunction) {
        T bean = getBean(name, type);
        if (bean == null) {
            // lock by type
            synchronized (type) {
                bean = getBean(name, type);
                if (bean == null) {
                    bean = mappingFunction.apply(type);
                    registerBean(name, bean);
                }
            }
        }
        return bean;
    }

    public <T> T initializeBean(T bean) {
        this.initializeBean(null, bean);
        return bean;
    }

    private void initializeBean(String name, Object bean) {
        checkDestroyed();
        try {
            if (bean instanceof ExtensionAccessorAware) {
                ((ExtensionAccessorAware) bean).setExtensionAccessor(extensionAccessor);
            }
            for (ExtensionPostProcessor processor : extensionPostProcessors) {
                processor.postProcessAfterInitialization(bean, name);
            }
        } catch (Exception e) {
            throw new ScopeBeanException("register bean failed! name=" + name + ", type=" + bean.getClass().getName(), e);
        }
    }

    private boolean containsBean(String name, Object bean) {
        for (BeanInfo beanInfo : registeredBeanInfos) {
            if (beanInfo.instance == bean &&
                (name == null || StringUtils.isEquals(name, beanInfo.name))) {
                return true;
            }
        }
        return false;
    }

    private int getNextId(Class<?> beanClass) {
        return beanNameIdCounterMap.computeIfAbsent(beanClass, key -> new AtomicInteger()).incrementAndGet();
    }

    public <T> T getBean(Class<T> type) {
        return this.getBean(null, type);
    }

    public <T> T getBean(String name, Class<T> type) {
        // 先从内部获取bean
        T bean = getBeanInternal(name, type);
        if (bean == null && parent != null) {
            // 内部没获取到，尝试从父容器中获取
            return parent.getBean(name, type);
        }
        return bean;
    }

    private <T> T getBeanInternal(String name, Class<T> type) {
        checkDestroyed();
        // All classes are derived from java.lang.Object, cannot filter bean by it
        if (type == Object.class) {
            return null;
        }
        List<BeanInfo> candidates = null;
        BeanInfo firstCandidate = null;
        // 遍历registeredBeanInfos，获取指定的bean
        for (BeanInfo beanInfo : registeredBeanInfos) {
            // if required bean type is same class/superclass/interface of the registered bean
            if (type.isAssignableFrom(beanInfo.instance.getClass())) {
                if (StringUtils.isEquals(beanInfo.name, name)) {
                    return (T) beanInfo.instance;
                } else {
                    // optimize for only one matched bean
                    if (firstCandidate == null) {
                        firstCandidate = beanInfo;
                    } else {
                        if (candidates == null) {
                            candidates = new ArrayList<>();
                            candidates.add(firstCandidate);
                        }
                        candidates.add(beanInfo);
                    }
                }
            }
        }

        // if bean name not matched and only single candidate
        if (candidates != null) {
            if (candidates.size() == 1) {
                return (T) candidates.get(0).instance;
            } else if (candidates.size() > 1) {
                List<String> candidateBeanNames = candidates.stream().map(beanInfo -> beanInfo.name).collect(Collectors.toList());
                throw new ScopeBeanException("expected single matching bean but found " + candidates.size() + " candidates for type [" + type.getName() + "]: " + candidateBeanNames);
            }
        } else if (firstCandidate != null) {
            return (T) firstCandidate.instance;
        }
        return null;
    }

    public void destroy() {
        if (destroyed.compareAndSet(false, true)){
            for (BeanInfo beanInfo : registeredBeanInfos) {
                if (beanInfo.instance instanceof Disposable) {
                    try {
                        Disposable beanInstance = (Disposable) beanInfo.instance;
                        beanInstance.destroy();
                    } catch (Throwable e) {
                        LOGGER.error("An error occurred when destroy bean [name=" + beanInfo.name + ", bean=" + beanInfo.instance + "]: " + e, e);
                    }
                }
            }
            registeredBeanInfos.clear();
        }
    }

    private void checkDestroyed() {
        if (destroyed.get()) {
            throw new IllegalStateException("ScopeBeanFactory is destroyed");
        }
    }

    static class BeanInfo {
        private String name;
        private Object instance;

        public BeanInfo(String name, Object instance) {
            this.name = name;
            this.instance = instance;
        }
    }
}
