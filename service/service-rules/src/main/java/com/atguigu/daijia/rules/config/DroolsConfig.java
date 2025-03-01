package com.atguigu.daijia.rules.config;

import lombok.extern.slf4j.Slf4j;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieModule;
import org.kie.api.runtime.KieContainer;
import org.kie.internal.io.ResourceFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class DroolsConfig{

    private static final String RULES_CUSTOMER_RULES_DRL = "rules/FeeRule.drl";

    //创建和配置Drools的KieContainer实例
    @Bean
    public KieContainer kieContainer(){

        //通过KieServices工厂类获取一个KieServices对象。KieServices是Drools API的入口点，提供了访问Drools的各种功能的方法。
        KieServices kieServices = KieServices.Factory.get();

        //创建一个新的KieFileSystem实例。当你需要编译和执行Drools规则时，KieFileSystem提供了一个文件系统的接口。
        KieFileSystem kieFileSystem = kieServices.newKieFileSystem();

        //通过ResourceFactory从类路径中读取规则文件FeeRule.drl，并将其写入KieFileSystem。
        kieFileSystem.write(ResourceFactory.newClassPathResource(RULES_CUSTOMER_RULES_DRL));

        //使用之前创建的KieFileSystem实例创建KieBuilder。KieBuilder用于构建KieModule，它能够将规则文件编译并验证其正确性。
        KieBuilder kieBuilder = kieServices.newKieBuilder(kieFileSystem);

        //调用buildAll方法开始编译所有在KieFileSystem中定义的规则文件。如果有错误，KieBuilder将会记录这些错误，后续可以通过相应的方法获取。
        kieBuilder.buildAll();

        //从KieBuilder获取已构建的KieModule实例。KieModule代表的是一组规则集的集合，并且它包含了可以由KieContainer使用的信息。
        KieModule kieModule = kieBuilder.getKieModule();

        //基于构建好的KieModule创建一个KieContainer。KieContainer是Drools的运行时环境，通过它可以访问和执行规则。
        KieContainer kieContainer = kieServices.newKieContainer(kieModule.getReleaseId());

        //将创建的KieContainer返回，这样其他组件可以通过依赖注入获取到这个实例。
        return kieContainer;

    }
}