package com.atguigu.daijia.driver.config;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.impl.WxMaServiceImpl;
import cn.binarywang.wx.miniapp.config.impl.WxMaDefaultConfigImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WxConfigOperator {

    @Autowired
    private WxConfigProperties wxConfigProperties;

    @Bean
    public WxMaService wxMaService(){

        //主要用于存储和管理微信小程序的配置信息，包括但不限于 appid 和 secret
        WxMaDefaultConfigImpl config = new WxMaDefaultConfigImpl();//
        config.setAppid(wxConfigProperties.getAppId());// 这里的appid是小程序的appid(小程序ID)
        config.setSecret(wxConfigProperties.getSecret());// 这里的secret是小程序的secret(小程序密钥)

        //在需要与微信小程序 API 交互时，首先需要创建 WxMaDefaultConfigImpl 的实例，
        // 并通过其方法设置好配置信息。然后将其注入到具体的服务实现类（如 WxMaServiceImpl）中，
        // 从而使得这个服务能够正确地调用微信的接口

        WxMaService wxMaService = new WxMaServiceImpl();// 这里的WxMaServiceImpl是WxMaService的实现类
        wxMaService.setWxMaConfig(config);// 将WxMaDefaultConfigImpl对象注入到WxMaServiceImpl对象中
        return wxMaService;

    }

}

