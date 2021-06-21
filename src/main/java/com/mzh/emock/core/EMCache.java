package com.mzh.emock.core;

import com.mzh.emock.type.EMRelatedObject;
import com.mzh.emock.type.bean.EMBeanInfo;
import com.mzh.emock.type.bean.definition.EMBeanDefinitionSource;
import com.mzh.emock.type.bean.definition.EMBeanDefinition;
import com.mzh.emock.type.bean.method.EMMethodInfo;
import com.mzh.emock.type.bean.method.EMMethodInvoker;
import com.mzh.emock.type.bean.method.EMMethodInvoker.*;
import com.mzh.emock.type.proxy.EMProxyHolder;
import com.mzh.emock.util.EMObjectMap;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class EMCache {

    public static final Object lock=new Object();

    public static AtomicLong idSequence=new AtomicLong(0);

    public static final List<EMBeanDefinitionSource<?>> EM_DEFINITION_SOURCES = new ArrayList<>();

    public static final Map<Object, EMRelatedObject> EM_OBJECT_MAP = new EMObjectMap<>();

    static class ESimpleInvoker implements SimpleInvoker<Object, Object[]> {
        private final Object bean;
        private final Method method;

        ESimpleInvoker(Object bean, Method method) {
            this.bean = bean;
            this.method = method;
        }

        @Override
        public Object invoke(Object[] args) throws InvocationTargetException, IllegalAccessException {
            return method.invoke(bean, args);
        }
    }

    private static Object doMock(Object o, Method method, Object[] args, Object oldBean,Class<?> injectClz) throws Exception {
        List<EMBeanInfo<?>> mockBeanInfoList=null;
        if (EM_OBJECT_MAP.get(oldBean) != null &&  EM_OBJECT_MAP.get(oldBean).getEmInfo()!=null
                && EM_OBJECT_MAP.get(oldBean).getEmInfo().get(injectClz)!=null) {
            mockBeanInfoList=EM_OBJECT_MAP.get(oldBean).getEmInfo().get(injectClz);
        }
        if(mockBeanInfoList==null || mockBeanInfoList.size()==0){
            return null;
        }
        for(EMBeanInfo<?> mockBeanInfo:mockBeanInfoList){
            if(mockBeanInfo.isMocked()){
                Map<String, EMMethodInfo> invokeMethods = mockBeanInfo.getInvokeMethods();
                EMMethodInfo methodInfo = invokeMethods.get(method.getName());
                if(methodInfo.isMock()) {
                    if (methodInfo.getDynamicInvokerName() != null) {
                        EMMethodInvoker<Object, Object[]> dynamicInvoker = methodInfo.getDynamicInvokers().get(methodInfo.getDynamicInvokerName());
                        return dynamicInvoker.invoke(new ESimpleInvoker(oldBean, method), new ESimpleInvoker(mockBeanInfo.getMockedBean(), method), args);
                    }
                    return method.invoke(mockBeanInfo.getMockedBean(), args);
                }
            }
        }
        return null;
    }

    public static class EInterfaceProxyInvocationHandler extends EInvocationHandler implements InvocationHandler {
        public EInterfaceProxyInvocationHandler(Object oldBean,Class<?> injectClz) {
            super(oldBean,injectClz);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object mock = doMock(proxy, method, args, oldBean,injectClz);
            return mock == null ? method.invoke(oldBean, args) : mock;
        }
    }

    public static class EObjectEnhanceInterceptor extends EInvocationHandler implements MethodInterceptor {
        public EObjectEnhanceInterceptor(Object oldBean,Class<?> injectClz) {
            super(oldBean,injectClz);
        }

        @Override
        public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
            Object mock = doMock(o, method, objects, oldBean,injectClz);
            return mock == null ? method.invoke(oldBean, objects) : mock;
        }
    }

    public static class EProxyHandlerEnhanceInterceptor extends EInvocationHandler implements MethodInterceptor {
        private final InvocationHandler oldHandler;

        public EProxyHandlerEnhanceInterceptor(InvocationHandler oldHandler, Object oldBean,Class<?> injectClz) {
            super(oldBean, injectClz);
            this.oldHandler = oldHandler;
        }

        @Override
        public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
            Method realMethod = (Method) objects[1];
            Object mock = doMock(o, realMethod, (Object[]) objects[2], oldBean,injectClz);
            return mock == null ? oldHandler.invoke(oldBean, realMethod, (Object[]) objects[2]) : mock;
        }
    }
    public static abstract class EInvocationHandler{
        protected final Object oldBean;
        protected final Class<?> injectClz;
        public EInvocationHandler(Object oldBean,Class<?> injectClz){
            this.oldBean=oldBean;
            this.injectClz=injectClz;
        }
    }

}
