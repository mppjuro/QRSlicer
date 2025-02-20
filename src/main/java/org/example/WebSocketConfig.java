package org.example;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

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
}
