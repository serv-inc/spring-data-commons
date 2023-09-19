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

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.Lifecycle;

/**
 * Unit tests for {@link LifecycleProxyFactory}.
 *
 * @author Oliver Drotbohm
 */
class LifecycleProxyFactoryUnitTests {

	LifecycleProxyFactoryBean<Sample> adapter = new LifecycleProxyFactoryBean<>(Sample.class,
			() -> new SampleImplementation(), it -> it.destroy());

	@Test
	void createsTargetInstance() {

		Sample first = adapter.getObject();

		assertThat(first.hello()).isEqualTo("World!");
		assertThat(first).isInstanceOfSatisfying(Lifecycle.class, it -> {

			assertThat(it.isRunning()).isTrue();

			it.stop();
			assertThat(it.isRunning()).isFalse();

			it.start();
			assertThat(it.isRunning()).isTrue();
		});
	}

	@Test
	void usesInitialInstanceOnInitialStart() {

		Sample target = adapter.getTarget();

		assertThat(adapter.getObject()).isInstanceOfSatisfying(Lifecycle.class, it -> {

			it.start();

			assertThat(adapter.getTarget()).isSameAs(target);
		});
	}

	@Test
	void swapsTargetInstanceAfterStop() throws Exception {

		Sample target = adapter.getTarget();

		assertThat(adapter.getObject()).isInstanceOfSatisfying(Lifecycle.class, it -> {

			it.stop();
			it.start();

			assertThat(adapter.getTarget()).isNotSameAs(target);
		});
	}

	@Test
	void invokesDestroyerOnTargetInstance() {

		assertThat(adapter.getObject()).isInstanceOfSatisfying(DisposableBean.class, it -> {

			assertThatNoException().isThrownBy(() -> it.destroy());

			assertThat(adapter.getTarget().hello()).isEqualTo("Destroyed!");
		});
	}

	interface Sample {

		String hello();
	}

	static class SampleImplementation implements Sample {

		String answer = "World!";

		public String hello() {
			return answer;
		}

		void destroy() {
			this.answer = "Destroyed!";
		}
	}
}
