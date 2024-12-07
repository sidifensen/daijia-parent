package com.atguigu.daijia.driver.service.impl;

import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.driver.config.MinioProperties;
import com.atguigu.daijia.driver.service.FileService;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class FileServiceImpl implements FileService {

    @Autowired
    private MinioProperties minioProperties;

    @Override
    public String upload(MultipartFile file) {
        try {

            String accessKey = minioProperties.getAccessKey();
            String secretKey = minioProperties.getSecreKey();
            String endpointUrl = minioProperties.getEndpointUrl();
            String bucketName = minioProperties.getBucketName();

            // 创建一个Minio的客户端对象
            MinioClient minioClient = MinioClient.builder()
                    .endpoint(endpointUrl).credentials(accessKey,secretKey)
                    .build();

            // 判断桶是否存在
            boolean bucketExists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!bucketExists) {       // 如果不存在，那么此时就创建一个新的桶
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            } else {  // 如果存在打印信息
                System.out.println("Bucket 'daijia' already exists.");
            }

            // 设置存储对象名称 lastIndexOf 是 String 类的方法，用于查找指定字符在字符串中最后出现的位置。
            String extFileName = file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf("."));
            String fileName = new SimpleDateFormat("yyyyMMdd")
                    .format(new Date()) + "/" + UUID.randomUUID().toString().replace("-" , "") + "." + extFileName;

            // 上传文件到Minio
            PutObjectArgs bucket = PutObjectArgs.builder()
                    .bucket(bucketName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .object(fileName)
                    .build();

            minioClient.putObject(bucket);

            // 返回文件的URL地址
            return endpointUrl + "/" + bucketName + "/" + fileName ;

        } catch (Exception e) {
            throw new GuiguException(ResultCodeEnum.FILE_ERROR);
        }
    }


}
