package com.atguigu.daijia.customer.service;

import com.atguigu.daijia.model.form.customer.UpdateWxPhoneForm;
import com.atguigu.daijia.model.vo.customer.CustomerLoginVo;

public interface CustomerService {


    String login(String code);

    //获取用户信息
    CustomerLoginVo getCustomerLoginInfo(String token);

    //从ThreadLocal中获取用户信息
    CustomerLoginVo getCustomerInfo(Long customerId);

    //更新用户手机号
    Object updateWxPhoneNumber(UpdateWxPhoneForm updateWxPhoneForm);
}
