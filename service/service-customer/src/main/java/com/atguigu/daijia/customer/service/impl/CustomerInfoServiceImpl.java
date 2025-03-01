package com.atguigu.daijia.customer.service.impl;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import cn.binarywang.wx.miniapp.bean.WxMaPhoneNumberInfo;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.customer.mapper.CustomerInfoMapper;
import com.atguigu.daijia.customer.mapper.CustomerLoginLogMapper;
import com.atguigu.daijia.customer.service.CustomerInfoService;
import com.atguigu.daijia.model.entity.customer.CustomerInfo;
import com.atguigu.daijia.model.entity.customer.CustomerLoginLog;
import com.atguigu.daijia.model.form.customer.UpdateWxPhoneForm;
import com.atguigu.daijia.model.vo.customer.CustomerLoginVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class CustomerInfoServiceImpl extends ServiceImpl<CustomerInfoMapper, CustomerInfo> implements CustomerInfoService {

    @Autowired
    private WxMaService wxMaService;

    @Autowired
    private CustomerInfoMapper customerInfoMapper;

    @Autowired
    private CustomerLoginLogMapper customerLoginLogMapper;

    @Override
    public Long login(String code) {

        //1.获取code值,使用微信工具包对象,获取微信唯一标识openid
        String openid = null;
        try {
            WxMaJscode2SessionResult sessionInfo = wxMaService.getUserService().getSessionInfo(code);
             openid = sessionInfo.getOpenid();
        } catch (WxErrorException e) {
            throw new RuntimeException(e);
        }

        //2.根据openid查询数据库,判断是否是第一次登录
        //如果openid不存在,返回null,如果存在返回一条记录
        LambdaQueryWrapper<CustomerInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CustomerInfo::getWxOpenId,openid);
        CustomerInfo customerInfo = customerInfoMapper.selectOne(queryWrapper);

        //3.如果是第一次登录,则创建新的用户,并返回用户id
        if (null == customerInfo){
            customerInfo = new CustomerInfo();
            customerInfo.setWxOpenId(openid);
            customerInfo.setNickname(String.valueOf(System.currentTimeMillis()));
            customerInfo.setAvatarUrl("https://oss.aliyuncs.com/aliyun_id_photo_bucket/default_handsome.jpg");
            customerInfo.setWxOpenId(openid);

            customerInfoMapper.insert(customerInfo);
        }

        //4.记录登录日志
        CustomerLoginLog customerLoginLog = new CustomerLoginLog();
        customerLoginLog.setCustomerId(customerInfo.getId());
        customerLoginLog.setMsg("小程序登录");

        customerLoginLogMapper.insert(customerLoginLog);

        //5.返回用户id
        return customerInfo.getId();
    }

    //回显用户信息
    @Override
    public CustomerLoginVo getCustomerInfo(Long customerId) {
        //1.根据customerId查询用户信息
        CustomerInfo customerInfo = customerInfoMapper.selectById(customerId);

        //2.把数据封装到CustomerLoginVo对象中并返回
        CustomerLoginVo customerLoginVo = new CustomerLoginVo();
        BeanUtils.copyProperties(customerInfo, customerLoginVo);

        //3.判断是否绑定手机号
        String phone = customerInfo.getPhone();
        boolean isBindPhone = StringUtils.hasText(phone);

        customerLoginVo.setIsBindPhone(isBindPhone);

        return customerLoginVo;
    }

    //更新客户微信手机号码
    @Override
    public Boolean updateWxPhoneNumber(UpdateWxPhoneForm updateWxPhoneForm) {
        //根据code值获取微信绑定手机号码
        try {
            WxMaPhoneNumberInfo phoneNoInfo =
                    wxMaService.getUserService().getPhoneNoInfo(updateWxPhoneForm.getCode());
            String phoneNumber = phoneNoInfo.getPhoneNumber();

            //获取用户id
            Long customerId = updateWxPhoneForm.getCustomerId();
            //根据customerId查询用户信息
            CustomerInfo customerInfo = customerInfoMapper.selectById(customerId);
            customerInfo.setPhone(phoneNumber);
            //更新用户信息
            customerInfoMapper.updateById(customerInfo);

            return true;
        } catch (WxErrorException e) {
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }
    }

    //根据customerId获取客服的微信openid
    @Override
    public String getCustomerOpenId(Long customerId) {
        LambdaQueryWrapper<CustomerInfo> queryWrapper = new LambdaQueryWrapper();
        queryWrapper.eq(CustomerInfo::getId,customerId);
        queryWrapper.select(CustomerInfo::getWxOpenId);//声明只查询微信openid,提升查询效率


        CustomerInfo customerInfo = customerInfoMapper.selectOne(queryWrapper);
        String wxOpenId = customerInfo.getWxOpenId();

        return wxOpenId;
    }



}














