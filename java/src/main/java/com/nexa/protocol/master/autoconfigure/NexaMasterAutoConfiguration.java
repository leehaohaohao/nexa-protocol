package com.nexa.protocol.master.autoconfigure;

import com.nexa.protocol.master.NexaMaster;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.NexaMasterListenerAdapter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "nexa.master", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(NexaMasterProperties.class)
public class NexaMasterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NexaMasterListener nexaMasterListener() {
        return new NexaMasterListenerAdapter();
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public NexaMaster nexaMaster(NexaMasterProperties properties, NexaMasterListener listener) {
        return NexaMaster.builder(listener)
                .host(properties.getHost())
                .port(properties.getPort())
                .maxFrameSize(properties.getMaxFrameSize())
                .heartbeatTimeout(properties.getHeartbeatTimeout())
                .heartbeatCheckInterval(properties.getHeartbeatCheckInterval())
                .build();
    }
}
