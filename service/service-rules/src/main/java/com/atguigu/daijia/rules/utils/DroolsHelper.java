package com.atguigu.daijia.rules.utils;

import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieModule;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.internal.io.ResourceFactory;

//定义一个Drools帮助类，接收规则文件，返回KieSession即可
//该代码的主要功能是加载规则并创建一个KieSession，这个Session用于执行业务规则引擎的规则。
public class DroolsHelper {

    private static final String RULES_CUSTOMER_RULES_DRL = "rules/FeeRule.drl";

    public static KieSession loadForRule(String drlStr) {
        // 加载规则文件
        KieServices kieServices = KieServices.Factory.get();

        // 编译规则文件
        KieFileSystem kieFileSystem = kieServices.newKieFileSystem();
        // 写入规则文件
        kieFileSystem.write(ResourceFactory.newClassPathResource(drlStr));

        // 编译
        KieBuilder kb = kieServices.newKieBuilder(kieFileSystem);
        // 验证规则文件
        kb.buildAll();

        // 得到KieModule
        KieModule kieModule = kb.getKieModule();
        // 得到KieContainer
        KieContainer kieContainer = kieServices.newKieContainer(kieModule.getReleaseId());
        // 得到KieSession
        return kieContainer.newKieSession();
    }
}