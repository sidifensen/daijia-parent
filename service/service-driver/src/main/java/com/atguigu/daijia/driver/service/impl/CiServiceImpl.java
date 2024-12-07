package com.atguigu.daijia.driver.service.impl;

import cn.hutool.core.codec.Base64;
import com.atguigu.daijia.driver.config.TencentCloudProperties;
import com.atguigu.daijia.driver.service.CiService;
import com.atguigu.daijia.model.vo.order.TextAuditingVo;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ciModel.auditing.*;
import com.qcloud.cos.region.Region;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class CiServiceImpl implements CiService {
    @Autowired
    private TencentCloudProperties tencentCloudProperties;

    private COSClient getPrivateCOSClient() {
        COSCredentials cred = new BasicCOSCredentials(
                tencentCloudProperties.getSecretId(), tencentCloudProperties.getSecretKey());
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setRegion(new Region(tencentCloudProperties.getRegion()));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        COSClient cosClient = new COSClient(cred, clientConfig);
        return cosClient;
    }

    //封装图片审核接口
    //我们只需要提供service接口，在对于的业务中调用该接口即可
    @Override
    public Boolean imageAuditing(String path) {
        COSClient cosClient = this.getPrivateCOSClient();

        //审核图片内容
        //1.创建任务请求对象
        ImageAuditingRequest request = new ImageAuditingRequest();

        //2.添加请求参数 参数详情请见 API 接口文档
        //2.1设置请求 bucket
        request.setBucketName(tencentCloudProperties.getBucketPrivate());

        //2.2设置审核策略 不传则为默认策略（预设）
        //request.setBizType("");

        //2.3设置图片在腾讯云COS的bucket中的位置
        request.setObjectKey(path);

        //3.调用接口,获取任务响应对象
        ImageAuditingResponse response = cosClient.imageAuditing(request);
        cosClient.shutdown();

        //HitFlag 用于返回该审核场景的审核结果，返回值：0：正常。1：确认为当前场景的违规内容。2：疑似为当前场景的违规内容。
        if (!response.getPornInfo().getHitFlag().equals("0")//涉黄
                || !response.getAdsInfo().getHitFlag().equals("0")//广告
                || !response.getTerroristInfo().getHitFlag().equals("0")//暴恐
                || !response.getPoliticsInfo().getHitFlag().equals("0")//涉政
        ) {
            return false;
        }
        return true;
    }


    //文本审核接口
    @Override
    public TextAuditingVo textAuditing(String content) {

        if(!StringUtils.hasText(content)) {
            TextAuditingVo textAuditingVo = new TextAuditingVo();
            textAuditingVo.setResult("0");
            return textAuditingVo;
        }

        COSClient cosClient = this.getPrivateCOSClient();

        TextAuditingRequest request = new TextAuditingRequest();

        request.setBucketName(tencentCloudProperties.getBucketPrivate());

        //将文本内容转换为base64字符串(这里使用hutools工具类进行转换)
        String base64 = Base64.encode(content);

        request.getInput().setContent(base64);//Input 需要审核的内容
        request.getConf().setDetectType("all");//conf 审核规则配置 all表示全部规则

        TextAuditingResponse response = cosClient.createAuditingTextJobs(request);

        //JobsDetail 用于返回文本审核的详细信息。
        AuditingJobsDetail detail = response.getJobsDetail();

        //要返回的对象 包含: 审核结果和风险关键词
        TextAuditingVo textAuditingVo = new TextAuditingVo();

        //State 文本审核的状态，值为 Success（审核成功）或者 Failed（审核失败）
        String state = detail.getState();

        if ("Success".equals(state)) {

            //Result 表示本次判定的审核结果 : 0（审核正常），1 （判定为违规敏感文件），2（疑似敏感，建议人工复核）。
            String result = detail.getResult();


            //Section 文本审核的具体结果信息。 getSectionList返回一个SectionInfo列表
            List<SectionInfo> sectionInfoList = detail.getSectionList();

            //违规关键词
            StringBuffer keywords = new StringBuffer();
            for (SectionInfo info : sectionInfoList) {

                String pornInfoKeyword = info.getPornInfo().getKeywords();
                String illegalInfoKeyword = info.getIllegalInfo().getKeywords();
                String abuseInfoKeyword = info.getAbuseInfo().getKeywords();

                //将关键词拼接到一起,放入到keywords(违规关键词)中,用逗号隔开
                if (pornInfoKeyword.length() > 0) {
                    keywords.append(pornInfoKeyword).append(",");
                }
                if (illegalInfoKeyword.length() > 0) {
                    keywords.append(illegalInfoKeyword).append(",");
                }
                if (abuseInfoKeyword.length() > 0) {
                    keywords.append(abuseInfoKeyword).append(",");
                }
            }
            textAuditingVo.setResult(result);
            textAuditingVo.setKeywords(keywords.toString());
        }
        return textAuditingVo;
    }
}
