package com.panie.common.web;

import java.beans.PropertyEditorSupport;
import java.io.IOException;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.ConstraintViolationException;
import javax.validation.ValidationException;
import javax.validation.Validator;

import org.apache.commons.lang3.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.panie.common.config.Global;
import com.panie.common.utils.BeanValidators;
import com.panie.common.utils.CookieUtils;
import com.panie.common.utils.DateUtils;
import com.panie.common.utils.JsonMapper;
import com.panie.common.utils.StringUtils;

/**
 * 控制器支持类
 */
public abstract class BaseController<E>
{
    
    /**
     * 日志对象
     */
    protected Logger logger = LoggerFactory.getLogger(getClass());
    
    /**
     * 验证Bean实例对象
     */
    @Autowired
    protected Validator validator;
    
    /**
     * 服务端参数有效性验证
     * 
     * @param object 验证的实体对象
     * @param groups 验证组
     * @return 验证成功：返回true；严重失败：将错误信息添加到 message 中
     */
    protected boolean beanValidator(Model model, Object object, Class<?>... groups)
    {
        try
        {
            BeanValidators.validateWithException(validator, object, groups);
        }
        catch (ConstraintViolationException ex)
        {
            List<String> list = BeanValidators.extractPropertyAndMessageAsList(ex, ": ");
            list.add(0, "数据验证失败：");
            addMessage(model, list.toArray(new String[] {}));
            return false;
        }
        return true;
    }
    
    /**
     * 服务端参数有效性验证
     * 
     * @param object 验证的实体对象
     * @param groups 验证组
     * @return 验证成功：返回true；严重失败：将错误信息添加到 flash message 中
     */
    protected boolean beanValidator(RedirectAttributes redirectAttributes, Object object, Class<?>... groups)
    {
        try
        {
            BeanValidators.validateWithException(validator, object, groups);
        }
        catch (ConstraintViolationException ex)
        {
            List<String> list = BeanValidators.extractPropertyAndMessageAsList(ex, ": ");
            list.add(0, "数据验证失败：");
            addMessage(redirectAttributes, list.toArray(new String[] {}));
            return false;
        }
        return true;
    }
    
    /**
     * 服务端参数有效性验证
     * 
     * @param object 验证的实体对象
     * @param groups 验证组，不传入此参数时，同@Valid注解验证
     * @return 验证成功：继续执行；验证失败：抛出异常跳转400页面。
     */
    protected void beanValidator(Object object, Class<?>... groups)
    {
        BeanValidators.validateWithException(validator, object, groups);
    }
    
    /**
     * 添加Model消息
     * 
     * @param message
     */
    protected void addMessage(Model model, String... messages)
    {
        StringBuilder sb = new StringBuilder();
        for (String message : messages)
        {
            sb.append(message).append(messages.length > 1 ? "<br/>" : "");
        }
        model.addAttribute("message", sb.toString());
    }
    
    /**
     * 添加Flash消息
     * 
     * @param message
     */
    protected void addMessage(RedirectAttributes redirectAttributes, String... messages)
    {
        StringBuilder sb = new StringBuilder();
        for (String message : messages)
        {
            sb.append(message).append(messages.length > 1 ? "<br/>" : "");
        }
        redirectAttributes.addFlashAttribute("message", sb.toString());
    }
    
    /**
     * 客户端返回JSON字符串
     * 
     * @param response
     * @param object
     * @return
     */
    protected String renderString(HttpServletResponse response, Object object)
    {
        return renderString(response, JsonMapper.getInstance().toJson(object), "application/json");
    }
    
    /**
     * 客户端返回字符串
     * 
     * @param response
     * @param string
     * @return
     */
    protected String renderString(HttpServletResponse response, String string, String type)
    {
        try
        {
            response.reset();
            response.setContentType(type);
            response.setCharacterEncoding("utf-8");
            response.getWriter().print(string);
            return null;
        }
        catch (IOException e)
        {
            return null;
        }
    }
    
    /**
     * 参数绑定异常
     */
    @ExceptionHandler({BindException.class, ConstraintViolationException.class, ValidationException.class})
    public String bindException()
    {
        return "error/400";
    }
    
    /**
     * 初始化数据绑定 1. 将所有传递进来的String进行HTML编码，防止XSS攻击 2. 将字段中Date类型转换为String类型
     */
    @InitBinder
    protected void initBinder(WebDataBinder binder)
    {
        // String类型转换，将所有传递进来的String进行HTML编码，防止XSS攻击
        binder.registerCustomEditor(String.class, new PropertyEditorSupport()
        {
            @Override
            public void setAsText(String text)
            {
                setValue(text == null ? null : StringEscapeUtils.escapeHtml4(text.trim()));
            }
            
            @Override
            public String getAsText()
            {
                Object value = getValue();
                return value != null ? value.toString() : "";
            }
        });
        // Date 类型转换
        binder.registerCustomEditor(Date.class, new PropertyEditorSupport()
        {
            @Override
            public void setAsText(String text)
            {
                setValue(DateUtils.parseDate(text));
            }
        });
    }
    
    /**
     * 构造方法
     * 
     * @param request 传递 repage 参数，来记住页码
     * @param response 用于设置 Cookie，记住页码
     * @param defaultPageSize 默认分页大小，如果传递 -1 则为不分页，返回所有数据
     */
    public Page<E> initPage(HttpServletRequest request, HttpServletResponse response)
    {
        int pageSize = Integer.valueOf(Global.getConfig("page.pageSize")); // 页面大小，设置为“-1”表示不进行分页（分页无效）
        int pageNo = 1;
        // 设置页码参数（传递repage参数，来记住页码）
        String no = request.getParameter("pageNo");
        if (StringUtils.isNumeric(no))
        {
            CookieUtils.setCookie(response, "pageNo", no);
            pageNo = Integer.parseInt(no);
        }
        else if (request.getParameter("repage") != null)
        {
            no = CookieUtils.getCookie(request, "pageNo");
            if (StringUtils.isNumeric(no))
            {
                pageNo = Integer.parseInt(no);
            }
        }
        // 设置页面大小参数（传递repage参数，来记住页码大小）
        String size = request.getParameter("pageSize");
        if (StringUtils.isNumeric(size))
        {
            CookieUtils.setCookie(response, "pageSize", size);
            pageSize = Integer.parseInt(size);
        }
        else if (request.getParameter("repage") != null)
        {
            no = CookieUtils.getCookie(request, "pageSize");
            if (StringUtils.isNumeric(size))
            {
                pageSize = Integer.parseInt(size);
            }
        }
        System.out.println("pageNo=" + pageNo + ",pageSize=" + pageSize);
        return PageHelper.startPage(pageNo, pageSize);
    }
}
