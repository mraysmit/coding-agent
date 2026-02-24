package dev.mars.apexaiagent.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/home/index.html");
        registry.addRedirectViewController("/home", "/home/index.html");
        registry.addRedirectViewController("/home/help", "/home/help/index.html");
    }
}
