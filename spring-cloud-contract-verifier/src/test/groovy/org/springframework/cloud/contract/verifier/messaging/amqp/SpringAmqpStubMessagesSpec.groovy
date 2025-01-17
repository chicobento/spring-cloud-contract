/*
 * Copyright 2013-2020 the original author or authors.
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

package org.springframework.cloud.contract.verifier.messaging.amqp

import com.rabbitmq.client.Channel
import org.mockito.exceptions.verification.WantedButNotInvoked
import spock.lang.IgnoreIf
import spock.lang.Specification

import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter
import org.springframework.boot.autoconfigure.amqp.RabbitProperties
import org.springframework.cloud.contract.verifier.converter.YamlContract

import static org.mockito.Mockito.mock
import static org.springframework.amqp.core.MessageProperties.CONTENT_TYPE_JSON
import static org.springframework.amqp.support.converter.DefaultClassMapper.DEFAULT_CLASSID_FIELD_NAME

/**
 * @author Mathias Düsterhöft
 */
@IgnoreIf({ jvm.isJava17Compatible() })
class SpringAmqpStubMessagesSpec extends Specification {

	RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class)
	SimpleMessageListenerContainer listenerContainer = new SimpleMessageListenerContainer()
	MessageListenerAdapter messageListenerAdapter = Mock(MessageListenerAdapter.class)
	RabbitProperties rabbitProperties = new RabbitProperties()
	Message message = Mock(Message.class)

	String queueName = "test.queue"
	String exchange = "test-exchange"
	String payload = '''{"name":"some"}'''
	String routingKey = "resource.created"

	def "should send amqp message with type id"() {
		given:
			listenerContainer.setMessageListener(messageListenerAdapter)
			listenerContainer.setQueueNames(queueName)
			Binding binding = BindingBuilder.bind(new Queue(queueName)).to(new DirectExchange(exchange)).with(routingKey)
			MessageListenerAccessor messageListenerAccessor = new MessageListenerAccessor(null, [listenerContainer], [binding])
			SpringAmqpStubMessages messageVerifier = new SpringAmqpStubMessages(rabbitTemplate, messageListenerAccessor, rabbitProperties)

		when:
			messageVerifier.send(payload,
					[(DEFAULT_CLASSID_FIELD_NAME): "org.example.Some",
					 "amqp_receivedRoutingKey"   : routingKey,
					 "contentType"               : CONTENT_TYPE_JSON],
					exchange)
		then:
			1 * messageListenerAdapter.onMessage({ Message msg ->
				msg.getMessageProperties().getReceivedRoutingKey() == "resource.created" &&
						msg.getMessageProperties().getContentType() == CONTENT_TYPE_JSON &&
						msg.getMessageProperties().getHeaders().get(DEFAULT_CLASSID_FIELD_NAME) == "org.example.Some"
			})
	}

	// for JDK 16 the merge option won't work and the metadata needs to be FULLY applied
	def "should send amqp message with metadata id"() {
		given:
			listenerContainer.setMessageListener(messageListenerAdapter)
			listenerContainer.setQueueNames(queueName)
			Binding binding = BindingBuilder.bind(new Queue(queueName)).to(new DirectExchange(exchange)).with(routingKey)
			MessageListenerAccessor messageListenerAccessor = new MessageListenerAccessor(null, [listenerContainer], [binding])
			SpringAmqpStubMessages messageVerifier = new SpringAmqpStubMessages(rabbitTemplate, messageListenerAccessor, rabbitProperties)

		when:
			messageVerifier.send(payload,
					[(DEFAULT_CLASSID_FIELD_NAME): "org.example.Some",
					 "amqp_receivedRoutingKey"   : routingKey,
					 "contentType"               : CONTENT_TYPE_JSON],
					exchange, new YamlContract(metadata:
					[
						"amqp": [
							"input": ["messageProperties": [
								correlationId: "correlationIdValue",
								consumerQueue: "queue",
								contentType: CONTENT_TYPE_JSON,
								receivedRoutingKey: routingKey]]
						],
						"verifierMessage" : [messageType: "INPUT"]
					]))
		then:
			1 * messageListenerAdapter.onMessage({ Message msg ->
				msg.getMessageProperties().getReceivedRoutingKey() == "resource.created" &&
						msg.getMessageProperties().getContentType() == CONTENT_TYPE_JSON &&
						msg.getMessageProperties().getHeaders().get(DEFAULT_CLASSID_FIELD_NAME) == "org.example.Some" &&
						msg.getMessageProperties().getCorrelationId() == "correlationIdValue" &&
						msg.getMessageProperties().getConsumerQueue() == "queue"
			})
	}

	def "should send amqp message for non transactional channel"() {
		given:
			rabbitProperties.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE)
			listenerContainer.setMessageListener(messageListenerAdapter)
			listenerContainer.setQueueNames(queueName)
			Binding binding = BindingBuilder.bind(new Queue(queueName)).to(new DirectExchange(exchange)).with(routingKey)
			MessageListenerAccessor messageListenerAccessor = new MessageListenerAccessor(null, [listenerContainer], [binding])
			boolean createChannelCalled = false
			boolean transactionalChannel = false
			SpringAmqpStubMessages messageVerifier = new SpringAmqpStubMessages(rabbitTemplate, messageListenerAccessor, rabbitProperties) {
				@Override
				boolean isChannelAwareListener(SimpleMessageListenerContainer listenerContainer, Object messageListener) {
					return true
				}

				@Override
				Channel createChannel(SimpleMessageListenerContainer listenerContainer, boolean transactional) {
					createChannelCalled = true
					transactionalChannel = transactional
					return null
				}
			}

		when:
			messageVerifier.send(payload,
					[(DEFAULT_CLASSID_FIELD_NAME): "org.example.Some",
					 "amqp_receivedRoutingKey"   : routingKey,
					 "contentType"               : CONTENT_TYPE_JSON],
					exchange)
		then:
			createChannelCalled
			!transactionalChannel
	}

	def "should send amqp message for transactional channel"() {
		given:
			rabbitProperties.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.NONE)
			listenerContainer.setMessageListener(messageListenerAdapter)
			listenerContainer.setQueueNames(queueName)
			Binding binding = BindingBuilder.bind(new Queue(queueName)).to(new DirectExchange(exchange)).with(routingKey)
			MessageListenerAccessor messageListenerAccessor = new MessageListenerAccessor(null, [listenerContainer], [binding])
			boolean createChannelCalled = false
			boolean transactionalChannel = false
			SpringAmqpStubMessages messageVerifier = new SpringAmqpStubMessages(rabbitTemplate, messageListenerAccessor, rabbitProperties) {
				@Override
				boolean isChannelAwareListener(SimpleMessageListenerContainer listenerContainer, Object messageListener) {
					return true
				}

				@Override
				Channel createChannel(SimpleMessageListenerContainer listenerContainer, boolean transactional) {
					createChannelCalled = true
					transactionalChannel = transactional
					return null
				}
			}

		when:
			messageVerifier.send(payload,
					[(DEFAULT_CLASSID_FIELD_NAME): "org.example.Some",
					 "amqp_receivedRoutingKey"   : routingKey,
					 "contentType"               : CONTENT_TYPE_JSON],
					exchange)
		then:
			createChannelCalled
			transactionalChannel
	}

	def "should fail to receive a message if rabbit template wasn't called"() {
		given:
			listenerContainer.setMessageListener(messageListenerAdapter)
			listenerContainer.setQueueNames(queueName)
			Binding binding = BindingBuilder.bind(new Queue(queueName)).to(new DirectExchange(exchange)).with(routingKey)
			MessageListenerAccessor messageListenerAccessor = new MessageListenerAccessor(null, [listenerContainer], [binding])
			SpringAmqpStubMessages messageVerifier = new SpringAmqpStubMessages(rabbitTemplate, messageListenerAccessor, rabbitProperties)
		when:
			messageVerifier.receive("foo")
		then:
			thrown(WantedButNotInvoked)
	}

	def "should return null if received called and message was sent without any body"() {
		given:
			listenerContainer.setMessageListener(messageListenerAdapter)
			listenerContainer.setQueueNames(queueName)
			Binding binding = BindingBuilder.bind(new Queue(queueName)).to(new DirectExchange(exchange)).with(routingKey)
			MessageListenerAccessor messageListenerAccessor = new MessageListenerAccessor(null, [listenerContainer], [binding])
			SpringAmqpStubMessages messageVerifier = new SpringAmqpStubMessages(rabbitTemplate, messageListenerAccessor, rabbitProperties)

		and:
			rabbitTemplate.send("foo", "bar", null, null)
		expect:
			messageVerifier.receive("foo") == null
	}

	def "should return match the message if received called and message was sent with a message with null payload"() {
		given:
			listenerContainer.setMessageListener(messageListenerAdapter)
			listenerContainer.setQueueNames(queueName)
			Binding binding = BindingBuilder.bind(new Queue(queueName)).to(new DirectExchange(exchange)).with(routingKey)
			MessageListenerAccessor messageListenerAccessor = new MessageListenerAccessor(null, [listenerContainer], [binding])
			SpringAmqpStubMessages messageVerifier = new SpringAmqpStubMessages(rabbitTemplate, messageListenerAccessor, rabbitProperties)
			message.getMessageProperties() >> new MessageProperties()
		and:
			rabbitTemplate.send("foo", "bar", message, null)
		expect:
			messageVerifier.receive("foo") is message
	}
}
