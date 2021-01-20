/*
 * Copyright 2013-2017 the original author or authors.
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
 *
 */

package org.springframework.cloud.gateway.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.event.FilterArgsEvent;
import org.springframework.cloud.gateway.event.PredicateArgsEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.support.ConfigurationUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.validation.Validator;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;

/**
 * 从RouteDefinitionLocator获取RouteDefinition 转换成Route
 */
public class RouteDefinitionRouteLocator implements RouteLocator, BeanFactoryAware, ApplicationEventPublisherAware {
	protected final Log logger = LogFactory.getLog(getClass());

	private final RouteDefinitionLocator routeDefinitionLocator;
	/**
	 * RoutePredicateFactory映射
	 */
	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();
	/**
	 * GatewayFilterFactory映射
	 */
	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();
	private final GatewayProperties gatewayProperties;
	//spring el表达式解析器
	private final SpelExpressionParser parser = new SpelExpressionParser();
	private BeanFactory beanFactory;
	private ApplicationEventPublisher publisher;

	public RouteDefinitionRouteLocator(RouteDefinitionLocator routeDefinitionLocator,
									   List<RoutePredicateFactory> predicates,
									   List<GatewayFilterFactory> gatewayFilterFactories,
									   GatewayProperties gatewayProperties) {
		this.routeDefinitionLocator = routeDefinitionLocator;
		initFactories(predicates);
		gatewayFilterFactories.forEach(factory -> this.gatewayFilterFactories.put(factory.name(), factory));
		this.gatewayProperties = gatewayProperties;
	}

	@Autowired
	private Validator validator;

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	private void initFactories(List<RoutePredicateFactory> predicates) {
		predicates.forEach(factory -> {
			String key = factory.name();
			if (this.predicates.containsKey(key)) {
				this.logger.warn("A RoutePredicateFactory named "+ key
						+ " already exists, class: " + this.predicates.get(key)
						+ ". It will be overwritten.");
			}
			this.predicates.put(key, factory);
			if (logger.isInfoEnabled()) {
				logger.info("Loaded RoutePredicateFactory [" + key + "]");
			}
		});
	}

	@Override
	public Flux<Route> getRoutes() {
		return this.routeDefinitionLocator.getRouteDefinitions()
				//调用 convertToRoute 方法将 RouteDefinition 转换成 Route。
				.map(this::convertToRoute)
				//TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("RouteDefinition matched: " + route.getId());
					}
					return route;
				});


		/* TODO: trace logging
			if (logger.isTraceEnabled()) {
				logger.trace("RouteDefinition did not match: " + routeDefinition.getId());
			}*/
	}

	private Route convertToRoute(RouteDefinition routeDefinition) {
		//将 PredicateDefinition 转换成 AsyncPredicate。
		AsyncPredicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);
		//将 FilterDefinition 转换成 GatewayFilter
		List<GatewayFilter> gatewayFilters = getFilters(routeDefinition);
		//根据前面两步骤定义的变量生成 Route 对象。
		return Route.async(routeDefinition)
				.asyncPredicate(predicate)
				.replaceFilters(gatewayFilters)
				.build();
	}

	@SuppressWarnings("unchecked")
	List<GatewayFilter> loadGatewayFilters(String id, List<FilterDefinition> filterDefinitions) {
		ArrayList<GatewayFilter> ordered = new ArrayList<>(filterDefinitions.size());

		/**
		 * FilterDefinition -> GatewayFilter
		 */
		for (int i = 0; i < filterDefinitions.size(); i++) {

			FilterDefinition definition = filterDefinitions.get(i);
			/**
			 * 根据filter名称获取对应的GatewayFilterFactory
			 */
			GatewayFilterFactory factory = this.gatewayFilterFactories.get(definition.getName());
			if (factory == null) {
				throw new IllegalArgumentException("Unable to find GatewayFilterFactory with name " + definition.getName());
			}

			/**
			 *  获取 FilterDefinition 中的 Map 类型参数，key 是固定字符串_genkey_ + 数字拼接而成
			 */
			Map<String, String> args = definition.getArgs();
			if (logger.isDebugEnabled()) {
				logger.debug("RouteDefinition " + id + " applying filter " + args + " to " + definition.getName());
			}

			/**
			 * 对上一步获得的参数作进一步转换，key为 config 类（工厂类中通过范型指定）的属性名称。
			 */
			Map<String, Object> properties = factory.shortcutType().normalize(args, factory, this.parser, this.beanFactory);

			/**
			 * 创建一个config对象
			 */
			Object configuration = factory.newConfig();

			/**
			 * 绑定properties到 config 对象上。
			 */
			ConfigurationUtils.bind(configuration, properties,
					factory.shortcutFieldPrefix(), definition.getName(), validator);

			GatewayFilter gatewayFilter = factory.apply(configuration);
			if (this.publisher != null) {
				this.publisher.publishEvent(new FilterArgsEvent(this, id, properties));
			}
			// GatewayFilter => OrderedGatewayFilter
			if (gatewayFilter instanceof Ordered) {
				ordered.add(gatewayFilter);
			}
			else {
				ordered.add(new OrderedGatewayFilter(gatewayFilter, i + 1));
			}
		}

		return ordered;
	}

	private List<GatewayFilter> getFilters(RouteDefinition routeDefinition) {
		List<GatewayFilter> filters = new ArrayList<>();

		/**
		 * 添加默认过滤器
		 */
		//TODO: support option to apply defaults after route specific filters?
		if (!this.gatewayProperties.getDefaultFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters("defaultFilters",
					this.gatewayProperties.getDefaultFilters()));
		}

		/**
		 * 添加配置的过滤器
		 */
		if (!routeDefinition.getFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(routeDefinition.getId(), routeDefinition.getFilters()));
		}

		/**
		 * 排序
		 */
		AnnotationAwareOrderComparator.sort(filters);
		return filters;
	}

	private AsyncPredicate<ServerWebExchange> combinePredicates(RouteDefinition routeDefinition) {
		List<PredicateDefinition> predicates = routeDefinition.getPredicates();
		/**
		 * 1、将列表中每一个 PredicateDefinition 都转换成 AsyncPredicate
		 *  2、根据PredicateDefinition 生成Config对象
		 *
		 */
		AsyncPredicate<ServerWebExchange> predicate = lookup(routeDefinition, predicates.get(0));

		//拼接Predicate
		for (PredicateDefinition andPredicate : predicates.subList(1, predicates.size())) {
			/**
			 * 1、将列表中每一个 PredicateDefinition 都转换成 AsyncPredicate
			 *  2、根据PredicateDefinition 生成Config对象
			 */

			AsyncPredicate<ServerWebExchange> found = lookup(routeDefinition, andPredicate);
			predicate = predicate.and(found);
		}

		return predicate;
	}

	@SuppressWarnings("unchecked")
	private AsyncPredicate<ServerWebExchange> lookup(RouteDefinition route, PredicateDefinition predicate) {
		/**
		 * ①根据 predicate 名称获取对应的 predicate factory。
		 */
		RoutePredicateFactory<Object> factory = this.predicates.get(predicate.getName());
		if (factory == null) {
            throw new IllegalArgumentException("Unable to find RoutePredicateFactory with name " + predicate.getName());
		}
		/**
		 * ②获取 PredicateDefinition 中的 Map 类型参数，key 是固定字符串_genkey_ + 数字拼接而成。
		 */
		Map<String, String> args = predicate.getArgs();
		if (logger.isDebugEnabled()) {
			logger.debug("RouteDefinition " + route.getId() + " applying "
					+ args + " to " + predicate.getName());
		}
		/**
		 * ③ 对第 ② 步获得的参数作进一步转换，key为 config 类（工厂类中通过范型指定）的属性名称。
		 */
        Map<String, Object> properties = factory.shortcutType().normalize(args, factory, this.parser, this.beanFactory);
		/**
		 * ④调用 factory 的 newConfig 方法创建一个 config 类对象。
		 */
		Object config = factory.newConfig();
		/**
		 * ⑤ 将第 ③ 步中产生的参数绑定到 config 对象上。
		 */
        ConfigurationUtils.bind(config, properties,
                factory.shortcutFieldPrefix(), predicate.getName(), validator);
        if (this.publisher != null) {
            this.publisher.publishEvent(new PredicateArgsEvent(this, route.getId(), properties));
        }
		/**
		 * ⑥将 cofing 作参数代入，调用 factory 的 applyAsync 方法创建 AsyncPredicate 对象。
		 */
		return factory.applyAsync(config);
	}
}
