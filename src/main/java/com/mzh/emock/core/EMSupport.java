package com.mzh.emock.core;

import com.mzh.emock.EMConfigurationProperties;
import com.mzh.emock.log.Logger;
import com.mzh.emock.type.EMBean;
import com.mzh.emock.type.EMRelatedObject;
import com.mzh.emock.type.bean.EMBeanInfo;
import com.mzh.emock.type.bean.definition.EMBeanDefinitionSource;
import com.mzh.emock.type.bean.definition.EMBeanDefinition;
import com.mzh.emock.type.proxy.EMProxyHolder;
import com.mzh.emock.util.EMClassUtil;
import com.mzh.emock.util.EMObjectUtil;
import com.mzh.emock.util.EMProxyUtil;
import com.mzh.emock.util.EMResourceUtil;
import com.mzh.emock.util.entity.EMFieldInfo;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.PatternMatchUtils;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class EMSupport {
    private static final Logger logger = Logger.get(EMSupport.class);

    public static void loadEMDefinitionSource(ApplicationContext context, ResourceLoader resLoader) throws Exception {
        if (context == null || resLoader == null) {
            return;
        }
        EMCache.EM_DEFINITION_SOURCES.clear();
        String[] matchers = loadEMNameMatcher(context);
        List<Method> methods = loadEMDefinitionSources(resLoader);
        ClassLoader clzLoader=EMSupport.class.getClassLoader();
        for (Method m : methods) {
            String typeName=((ParameterizedType) m.getGenericReturnType()).getActualTypeArguments()[0].getTypeName();
            if (PatternMatchUtils.simpleMatch(matchers,typeName)) {
                EMCache.EM_DEFINITION_SOURCES.add(new EMBeanDefinitionSource<>(m,clzLoader));
            }
        }
        logger.info("emock : load definitionSource complete : "+EMCache.EM_DEFINITION_SOURCES.size());
    }

    public static void createEMDefinition(ApplicationContext context) throws Exception {
        for (EMBeanDefinitionSource<?> ds : EMCache.EM_DEFINITION_SOURCES) {
            ds.createBeanDefinition(context);
        }
    }

    public static void createEMBeanIfNecessary(AbstractApplicationContext context) {
        ConfigurableListableBeanFactory factory=context.getBeanFactory();
        Arrays.stream(context.getBeanDefinitionNames()).forEach(
                name-> createEMBeanIfNecessary(name, context.getBean(name),factory.getBeanDefinition(name))
        );
    }

    /**
     * 实际mock过程,检查所有的bean是否需要进行mock
     *
     * @param beanName
     * @param oldBean
     */
    public static boolean createEMBeanIfNecessary(String beanName, Object oldBean, BeanDefinition oldDefinition) {
        AtomicBoolean isCreate= new AtomicBoolean(false);
        EMCache.EM_DEFINITION_SOURCES.stream().filter(ds->ds.getBeanDefinition().isMatch(beanName,oldBean)
                && !hasCreateBeanForThisDefinition(oldBean,ds)).forEach(ds->{
                    EMCache.EM_OBJECT_MAP.computeIfAbsent(oldBean,r->new EMRelatedObject(beanName,oldBean));
                    EMBeanInfo<?> newBean=createMockBean(oldBean,ds);
                    EMCache.EM_OBJECT_MAP.get(oldBean).setOldDefinition(oldDefinition);
                    Map<Class<?>,List<EMBeanInfo<?>>> infoMap=EMCache.EM_OBJECT_MAP.get(oldBean).getEmInfo();
                    infoMap.computeIfAbsent(ds.getTargetClz(),l->new ArrayList<>());
                    infoMap.get(ds.getTargetClz()).add(newBean);
                    isCreate.set(true);
                    infoMap.get(ds.getTargetClz()).sort(Comparator.comparingInt(e->e.getDefinitionSource().getOrder()));
                });
        return isCreate.get();
    }

    private static <T> EMBeanInfo<T> createMockBean(Object oldBean, EMBeanDefinitionSource<T> ds) {
        return new EMBeanInfo<>(ds.getBeanDefinition().getWrapper().wrap(ds.getTargetClz().cast(oldBean)),ds);
    }
    private static boolean hasCreateBeanForThisDefinition(Object oldBean,EMBeanDefinitionSource<?> ds){
        return EMCache.EM_OBJECT_MAP.get(oldBean)!=null &&
                EMCache.EM_OBJECT_MAP.get(oldBean).getEmInfo()!=null &&
                EMCache.EM_OBJECT_MAP.get(oldBean).getEmInfo().get(ds.getTargetClz())!=null &&
                EMCache.EM_OBJECT_MAP.get(oldBean).getEmInfo().get(ds.getTargetClz())
                        .stream().anyMatch(bi->bi.getDefinitionSource()==ds);
    }

    /**
     * 将mock的bean注入到具体的实例中
     */
    public static void proxyAndInject(ApplicationContext context) throws Exception {
        String[] names = context.getBeanDefinitionNames();
        Object[] beans= Arrays.stream(names).map(context::getBean).toArray();
        for (Object target : EMCache.EM_OBJECT_MAP.keySet()) {
            for (int j=0;j<names.length;j++) {
                createProxyAndSetField(beans[j], target);
            }
        }
    }

    private static void createProxyAndSetField(Object src, Object target) throws Exception {
        Map<Object, List<EMFieldInfo>> matchedObject = EMObjectUtil.match(src, target);
        for (Object holder : matchedObject.keySet()) {
            List<EMFieldInfo> fields = matchedObject.get(holder);
            for(int i=fields.size()-1;i>=0;i--){
                createProxyAndSetField(fields.get(i),holder,target);
            }
        }
    }
    private static void createProxyAndSetField(EMFieldInfo info, Object holder, Object target) throws Exception {
        Class<?> clz;
        if(info.isArrayIndex()){
            if(((Object[])holder)[info.getIndex()]!=target){logger.error("array object index changed "+",obj:"+holder);return;}
            clz=findBestMatchClz(target,info.getNativeField().getType().getComponentType());
        }else{
            clz=findBestMatchClz(target,info.getNativeField().getType());
        }
        EMProxyHolder proxyHolder = EMProxyUtil.createProxy(clz, target);
        proxyHolder.addInjectField(info);
        doInject(info,holder,proxyHolder.getProxy());
    }

    public static boolean doInject(EMFieldInfo fieldInfo, Object holder, Object proxy)throws Exception{
        if(fieldInfo.isArrayIndex()){
            ((Object[]) holder)[fieldInfo.getIndex()] = proxy;
        }else{
            fieldInfo.getNativeField().setAccessible(true);
            fieldInfo.getNativeField().set(holder,proxy);
        }
        return true;
    }



    private static Class<?> findBestMatchClz(Object oldBean,Class<?> fieldClz){
        Set<Class<?>> curr=EMCache.EM_OBJECT_MAP.get(oldBean).getEmInfo().keySet();
        Class<?> bestMatch=null;
        for(Class<?> c:curr){
            if(fieldClz.isAssignableFrom(c)){
                if(fieldClz==c){
                    return fieldClz;
                }
                if(bestMatch==null){
                    bestMatch=c;
                }
                if(bestMatch.isAssignableFrom(c)){
                    bestMatch=c;
                }
            }
        }
        return bestMatch;
    }


    private static String[] loadEMNameMatcher(ApplicationContext context) {
        Environment environment = context.getEnvironment();
        if (!isEMEnvironment(environment)) {
            return new String[]{};
        }
        List<String> filters=EMConfigurationProperties.FILTER;
        return filters.size() == 0 ? new String[]{"*"} : filters.toArray(new String[0]);
    }

    private static boolean isEMEnvironment(Environment environment) {
        String[] envProfiles = environment.getActiveProfiles();
        List<String> targetProfiles = EMConfigurationProperties.ENABLED_PROFILES;
        if (envProfiles.length == 0 || targetProfiles.size() == 0) {
            return false;
        }
        for (String envProfile : envProfiles) {
            if (targetProfiles.contains(envProfile)) {
                return true;
            }
        }
        return false;
    }

    private static List<Method> loadEMDefinitionSources(ResourceLoader resourceLoader) throws Exception {
        ClassLoader classLoader = EMSupport.class.getClassLoader();
        List<Method> methods = new ArrayList<>();
        ResourcePatternResolver resolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
        MetadataReaderFactory readerFactory = new CachingMetadataReaderFactory(resourceLoader);
        List<String> paths = EMConfigurationProperties.SCAN_PACKAGE;
        for (String path : paths) {
            Resource[] resources = resolver.getResources(EMResourceUtil.formatResourcePath(path));
            for (Resource resource : resources) {
                MetadataReader reader = readerFactory.getMetadataReader(resource);
                Class<?> clz = classLoader.loadClass(reader.getClassMetadata().getClassName());
                methods.addAll(EMClassUtil.getAllMethods(clz,EMSupport::isEMDefinition));
            }
        }
        return methods;
    }

    private static boolean isEMDefinition(Method method) {
        return (method.getModifiers() & Modifier.PUBLIC) > 0
                && (method.getModifiers() & Modifier.STATIC) > 0
                && method.isAnnotationPresent(EMBean.class)
                && method.getParameterCount() == 1
                && ApplicationContext.class.isAssignableFrom(method.getParameterTypes()[0])
                && method.getReturnType() == EMBeanDefinition.class
                && ParameterizedType.class.isAssignableFrom(method.getGenericReturnType().getClass())
                && ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments() != null
                && ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments().length == 1;
    }

}