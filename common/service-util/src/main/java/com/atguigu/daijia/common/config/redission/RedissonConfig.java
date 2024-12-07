package com.atguigu.daijia.common.config.redission;

import lombok.Data;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * redisson配置信息
 */
@Data
@Configuration
@ConfigurationProperties("spring.data.redis")
public class RedissonConfig {

    private String host;

    private String password;

    private String port;

    private int timeout = 3000;
    private static String ADDRESS_PREFIX = "redis://";//redis地址前缀

    /**
     * 自动装配
     *
     */
    @Bean
    RedissonClient redissonSingle() {
        // 创建redisson客户端
        Config config = new Config();


        if(!StringUtils.hasText(host)){
            throw new RuntimeException("host is  empty");
        }


        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress(ADDRESS_PREFIX + this.host + ":" + port)//设置redis地址和端口
                .setTimeout(this.timeout);//设置连接超时时间
        if(StringUtils.hasText(this.password)) {
            // 设置redis密码
            serverConfig.setPassword(this.password);
        }
        return Redisson.create(config);
    }
}