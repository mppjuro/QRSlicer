package org.example;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer, WebSocketMessageBrokerConfigurer {

    private final ImageProcessor imageProcessor;

    @Autowired
    public WebSocketConfig(ImageProcessor imageProcessor) {
        this.imageProcessor = imageProcessor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new BinaryWebSocketHandlerMP(imageProcessor), "/ws")
                .setAllowedOrigins("*");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(20 * 1024 * 1024); // 20MB na wiadomość
        registration.setSendBufferSizeLimit(20 * 1024 * 1024); // 20MB na wiadomość
        registration.setTimeToFirstMessage(5000); // 5s na pierwszą wiadomość
    }
}
