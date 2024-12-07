package com.atguigu.daijia.driver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Data
@ConfigurationProperties(prefix = "wx.miniapp")
// prefix value这两个属性是互为别名的，即它们可以互换使用。
//这种设计使得用户可以根据自己的偏好选择使用 value 或 prefix
public class WxConfigProperties {

    private String appId;
    private String secret;


}
