/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.config;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.target.HotSwappableTargetSource;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.Lifecycle;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.function.ThrowingConsumer;

/**
 * A {@link FactoryBean} that creates proxy instances for bean instances of a certain type that swap the target instance
 * on {@link Lifecycle} restarts.
 *
 * @author Oliver Drotbohm
 * @param <T> the type of the bean to create eventually
 */
public class LifecycleProxyFactoryBean<T> implements Lifecycle, FactoryBean<T>, DisposableBean, BeanClassLoaderAware {

	private static final Collection<Class<?>> IMPLEMENTED_TYPES = List.of(Lifecycle.class, DisposableBean.class);

	private final Class<T> type;
	private final Supplier<? extends T> creator;
	private final ThrowingConsumer<Object> destroyer;

	private ClassLoader classLoader;
	private Status status;

	enum Status {
		INITIALIZED, STARTED, STOPPED;
	}

	private HotSwappableTargetSource target;

	/**
	 * Creates a new {@link LifecycleProxyFactoryBean} for the given target type and {@link Supplier} to create new
	 * instances.
	 *
	 * @param type must not be {@literal null}.
	 * @param creator must not be {@literal null}.
	 */
	public LifecycleProxyFactoryBean(Class<T> type, Supplier<T> creator) {
		this(type, creator, __ -> {});
	}

	/**
	 * Creates a new {@link LifecycleProxyFactoryBean} for the given target type, {@link Supplier} to create new instances
	 * and {@link ThrowingConsumer} to eventually destroy them.
	 *
	 * @param type must not be {@literal null}.
	 * @param creator must not be {@literal null}.
	 * @param destroyer must not be {@literal null}.
	 */
	@SuppressWarnings("unchecked")
	public <S extends T> LifecycleProxyFactoryBean(Class<T> type, Supplier<S> creator, ThrowingConsumer<S> destroyer) {

		Assert.notNull(type, "Type must not be null!");
		Assert.notNull(creator, "Creator must not be null!");
		Assert.notNull(destroyer, "Destroyer must not be null!");

		this.creator = creator;
		this.type = type;
		this.destroyer = (ThrowingConsumer<Object>) destroyer;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.BeanClassLoaderAware#setBeanClassLoader(java.lang.ClassLoader)
	 */
	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	/**
	 * Returns the raw target instance. Potentially a different one on every invocation.
	 *
	 * @return the target
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	T getTarget() {

		initTargetSource();

		return (T) target.getTarget();
	}

	private HotSwappableTargetSource initTargetSource() {

		if (this.target != null) {
			return this.target;
		}

		this.target = new HotSwappableTargetSource(creator.get());
		this.status = Status.INITIALIZED;

		return this.target;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	@NonNull
	@Override
	@SuppressWarnings("unchecked")
	public T getObject() {

		initTargetSource();

		ProxyFactory factory = new ProxyFactory(type, Lifecycle.class, DisposableBean.class);
		factory.setTargetSource(this.target);
		factory.addAdvice(new MethodInterceptor() {

			@Override
			@Nullable
			public Object invoke(MethodInvocation invocation) throws Throwable {

				var method = invocation.getMethod();

				return IMPLEMENTED_TYPES.contains(method.getDeclaringClass())
						? method.invoke(LifecycleProxyFactoryBean.this)
						: invocation.proceed();
			}
		});

		return (T) factory.getProxy(classLoader);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.context.Lifecycle#stop()
	 */
	@Override
	@SuppressWarnings("unchecked")
	public void stop() {

		try {

			destroyer.accept(target.getTarget());

		} finally {
			this.status = Status.STOPPED;
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.context.Lifecycle#start()
	 */
	@Override
	public void start() {

		if (status.equals(Status.STOPPED)) {
			this.target.swap(creator.get());
		}

		this.status = Status.STARTED;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.context.Lifecycle#isRunning()
	 */
	@Override
	public boolean isRunning() {
		return status.equals(Status.STARTED) || status.equals(Status.INITIALIZED);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.FactoryBean#getObjectType()
	 */
	@NonNull
	@Override
	public Class<?> getObjectType() {
		return type;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.DisposableBean#destroy()
	 */
	@Override
	public void destroy() throws Exception {

		if (target.getTarget() != null) {
			stop();
		}
	}
}
