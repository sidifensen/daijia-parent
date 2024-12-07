package com.atguigu.daijia.driver.service.impl;

import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.driver.config.TencentCloudProperties;
import com.atguigu.daijia.driver.service.CiService;
import com.atguigu.daijia.driver.service.CosService;
import com.atguigu.daijia.model.vo.driver.CosUploadVo;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.*;
import com.qcloud.cos.region.Region;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class CosServiceImpl implements CosService {


    @Autowired
    private TencentCloudProperties tencentCloudProperties;

    @Autowired
    private CiService ciService;

    @Override
    public CosUploadVo upload(MultipartFile file, String path) {

        //1.获取COSClient对象
        COSClient cosClient = getCosClient();

        //文件上传
        //4.元数据信息
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(file.getSize());
        meta.setContentEncoding("UTF-8");
        meta.setContentType(file.getContentType());



        //5.向存储桶中保存文件 设置文件名
        String fileType = file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf(".")); //将从文件名中的点位置开始提取字符串，结果是获取后缀名
        String uploadPath = "/driver/" + path + "/" + UUID.randomUUID().toString().replaceAll("-", "") + fileType;

        // 比如:01.jpg
        // /driver/auth/0o98754.jpg
        // 6.上传文件
        PutObjectRequest putObjectRequest = null;
        try {
            //创建 putObjectRequest 请求对象
            putObjectRequest = new PutObjectRequest(
                    tencentCloudProperties.getBucketPrivate(),//bucket名称
                    uploadPath,//文件名
                    file.getInputStream(),//上传文件流
                    meta);//元数据信息
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        putObjectRequest.setStorageClass(StorageClass.Standard);//设置存储类型为标准类型
        PutObjectResult putObjectResult = cosClient.putObject(putObjectRequest);//上传文件

        log.info("上传成功, uploadPath:{}, fileUrl:{}", uploadPath, putObjectResult.getETag());


        //TODO 调用 CI审核图片
        Boolean isAuditing = ciService.imageAuditing(uploadPath);
        if(!isAuditing) {//如果审核失败
            //删除违规图片
            cosClient.deleteObject(tencentCloudProperties.getBucketPrivate(), uploadPath);
            throw new GuiguException(ResultCodeEnum.IMAGE_AUDITION_FAIL);
        }

        //7.关闭客户端
        cosClient.shutdown();

        //8.返回vo对象
        CosUploadVo cosUploadVo = new CosUploadVo();
        cosUploadVo.setUrl(uploadPath);

        //9.TODO 调用下面的方法获取图片临时访问url，回显使用
        String imageUrl = this.getImageUrl(uploadPath);
        cosUploadVo.setShowUrl(imageUrl);

        return cosUploadVo;
    }

    public COSClient getCosClient() {
        // 1 传入获取到的临时密钥 (tmpSecretId, tmpSecretKey) , 获取凭证对象
        String tmpSecretId = tencentCloudProperties.getSecretId();
        String tmpSecretKey = tencentCloudProperties.getSecretKey();
        COSCredentials cred = new BasicCOSCredentials(tmpSecretId, tmpSecretKey);

        // 2 设置 bucket 的地域
        // clientConfig 中包含了设置 region, https(默认 http), 超时, 代理等 set 方法, 使用可参见源码或者常见问题 Java SDK 部分
        Region region = new Region(tencentCloudProperties.getRegion()); //COS_REGION 参数：配置成存储桶 bucket 的实际地域，例如 ap-beijing，
        ClientConfig clientConfig = new ClientConfig(region);
        // 这里建议设置使用 https 协议
        // 从 5.6.54 版本开始，默认使用了 https
        clientConfig.setHttpProtocol(HttpProtocol.https);

        // 3 生成cos客户端 (传入凭证,和客户端配置)
        COSClient cosClient = new COSClient(cred, clientConfig);

        return cosClient;
    }

    //临时访问url 回显使用
    @Override
    public String getImageUrl(String path) {

        if(!StringUtils.hasText(path)) return "";
        //1.获取COSClient对象
        COSClient cosClient = getCosClient();

        //2.TODO 创建GeneratePresignedUrlRequest,生成预签名 URL 的请求对象
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(tencentCloudProperties.getBucketPrivate(),
                        path, HttpMethodName.GET);

        //3.设置签名过期时间 DateTime 调用了joda-time包
        Date date = new DateTime().plusMinutes(15).toDate();

        request.setExpiration(date);

        //4.调用方法获取地址
        URL url = cosClient.generatePresignedUrl(request);

        //5.关闭客户端
        cosClient.shutdown();

        //6.返回url
        return url.toString();
    }
}
