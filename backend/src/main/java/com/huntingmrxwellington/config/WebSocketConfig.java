package com.huntingmrxwellington.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
        // Exact-match subscriptions only. Otherwise the simple broker treats a subscription to
        // "/topic/games/{id}/players/**" as a pattern and delivers every player's private state,
        // Mr X's position included. (This also turns off patterns in @MessageMapping, which we don't use.)
        registry.setPathMatcher(new AntPathMatcher() {
            @Override
            public boolean isPattern(String path) {
                return false;
            }
        });
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    /** Clients only ever subscribe. A client SEND to a /topic destination would go straight to the
     *  broker and reach subscribers as if the server had published it, so it's dropped here. */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                return StompCommand.SEND.equals(StompHeaderAccessor.wrap(message).getCommand()) ? null : message;
            }
        });
    }
}
