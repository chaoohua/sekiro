package com.virjar.sekiro.api;

import android.text.TextUtils;

import com.virjar.sekiro.api.databind.ActionRequestHandlerGenerator;
import com.virjar.sekiro.api.databind.AutoBind;
import com.virjar.sekiro.api.databind.DirectMapGenerator;
import com.virjar.sekiro.api.databind.EmptyARCreateHelper;
import com.virjar.sekiro.api.databind.FieldBindGenerator;
import com.virjar.sekiro.api.databind.ICRCreateHelper;
import com.virjar.sekiro.api.databind.InnerClassCreateHelper;
import com.virjar.sekiro.netty.protocol.SekiroNatMessage;
import com.virjar.sekiro.utils.Defaults;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.netty.channel.Channel;


public class SekiroRequestHandlerManager {
    private static final Logger log = LoggerFactory.getLogger(SekiroRequestHandlerManager.class);
    private static final String action = "action";
    private static final String actionList = "__actionList";


    private Map<String, ActionRequestHandlerGenerator> requestHandlerMap = new HashMap<>();

    private static final ConcurrentMap<Class, Field[]> fieldCache = new ConcurrentHashMap<>();


    public void handleSekiroNatMessage(SekiroNatMessage sekiroNatMessage, Channel channel) {
        SekiroRequest sekiroRequest = new SekiroRequest(sekiroNatMessage.getData(), sekiroNatMessage.getSerialNumber());
        SekiroResponse sekiroResponse = new SekiroResponse(sekiroRequest, channel);

        try {
            sekiroRequest.getString("ensure mode parsed");
        } catch (Exception e) {
            sekiroResponse.failed(CommonRes.statusBadRequest, e);
        }

        String action = sekiroRequest.getString(SekiroRequestHandlerManager.action);

        if (TextUtils.isEmpty(action)) {
            sekiroResponse.failed("the param:{" + SekiroRequestHandlerManager.action + "} not present");
            return;
        }


        ActionRequestHandlerGenerator actionRequestHandlerGenerator = requestHandlerMap.get(action);
        if (actionRequestHandlerGenerator == null) {
            if (action.equals(actionList)) {
                TreeSet<String> sortedActionSet = new TreeSet<>(requestHandlerMap.keySet());
                sekiroResponse.success(sortedActionSet);
                return;
            } else {
                sekiroResponse.failed("unknown action: " + action);
                return;
            }
        }

        try {
            actionRequestHandlerGenerator.gen(sekiroRequest).handleRequest(sekiroRequest, sekiroResponse);
        } catch (Throwable throwable) {
            log.error("failed to generate action request handler", throwable);
            sekiroResponse.failed(CommonRes.statusError, throwable);
        }
    }


    public void registerHandler(String action, SekiroRequestHandler sekiroRequestHandler) {
        if (TextUtils.isEmpty(action)) {
            throw new IllegalArgumentException("action empty!!");
        }
        if (requestHandlerMap.containsKey(action)) {
            throw new IllegalStateException("the request handler: " + sekiroRequestHandler + " for action:" + action + "  registered already!!");
        }
        requestHandlerMap.put(action, toGenerator(sekiroRequestHandler));
    }

    @SuppressWarnings("unchecked")
    private ActionRequestHandlerGenerator toGenerator(SekiroRequestHandler actionRequestHandler) {
        Constructor<? extends SekiroRequestHandler>[] constructors = (Constructor<? extends SekiroRequestHandler>[]) actionRequestHandler.getClass().getDeclaredConstructors();
        boolean canAutoCreateInstance = false;
        ActionRequestHandlerGenerator instanceCreateHelper = null;
        for (Constructor<? extends SekiroRequestHandler> constructor : constructors) {
            if (constructor.getParameterTypes().length == 0) {
                canAutoCreateInstance = true;
                instanceCreateHelper = new EmptyARCreateHelper(actionRequestHandler.getClass());
                break;
            }
            if (constructor.getParameterTypes().length == 1) {
                if (SekiroRequest.class.isAssignableFrom(constructor.getParameterTypes()[0])) {
                    canAutoCreateInstance = true;
                    instanceCreateHelper = new ICRCreateHelper(constructor);
                    break;
                } else if (actionRequestHandler.getClass().getName().startsWith(constructor.getParameterTypes()[0].getName())) {

                    // 可能是匿名内部类，这个时候也需要支持注入
                    //com.virjar.sekiro.demo.MainActivity$1$1
                    //com.virjar.sekiro.demo.MainActivity$1
                    String simpleInnerClassName = actionRequestHandler.getClass().getName().substring(constructor.getParameterTypes()[0].getName().length());
                    //$1
                    if (simpleInnerClassName.startsWith("$")) {
                        //确定是匿名内部类
                        //find out class object instance
                        Object outClassObjectInstance = null;
                        boolean hasAutoBindAnnotation = false;
                        for (Field field : actionRequestHandler.getClass().getDeclaredFields()) {
                            if (!field.isSynthetic()) {
                                continue;
                            }

                            AutoBind fieldAnnotation = field.getAnnotation(AutoBind.class);
                            if (fieldAnnotation != null) {
                                hasAutoBindAnnotation = true;
                            }

                            if (!field.getType().equals(constructor.getParameterTypes()[0])) {
                                continue;
                            }

                            field.setAccessible(true);
                            try {
                                outClassObjectInstance = field.get(actionRequestHandler);
                                break;
                            } catch (IllegalAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        if (outClassObjectInstance != null && hasAutoBindAnnotation) {
//                            canAutoCreateInstance = true;
//                            Class<? extends SekiroRequestHandler> aClass = actionRequestHandler.getClass();
//                            instanceCreateHelper = new InnerClassCreateHelper(constructor, outClassObjectInstance);
//                            break;
                            //不支持匿名内部类的自动绑定
                            throw new IllegalStateException("can not bind attribute for InnerClass object");
                        }
                    }
                }


            }
        }
        if (!canAutoCreateInstance) {
            return new DirectMapGenerator(actionRequestHandler);
        }

        Field[] fields = classFileds(actionRequestHandler.getClass());
        List<Field> autoBindFields = new ArrayList<>();
        Map<Field, Object> copyFiledMap = new HashMap<>();
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())
                    || field.isSynthetic()) {
                continue;
            }
            try {
                Object o = field.get(actionRequestHandler);
                if (o != null) {
                    if (field.getType().isPrimitive() && Defaults.defaultValue(o.getClass()) == o) {
                        // int a =0;
                        // double =0;
                        continue;
                    }
                    copyFiledMap.put(field, o);
                    //continue;
                }
            } catch (Exception e) {
                //ignore
            }
            autoBindFields.add(field);
        }
        if (autoBindFields.size() == 0) {
            return new DirectMapGenerator(actionRequestHandler);
        }
        return new FieldBindGenerator(autoBindFields, instanceCreateHelper, copyFiledMap);
    }


    private static Field[] classFileds(Class clazz) {
        if (clazz == Object.class) {
            return new Field[0];
        }
        Field[] fields = fieldCache.get(clazz);
        if (fields != null) {
            return fields;
        }
        synchronized (clazz) {
            fields = fieldCache.get(clazz);
            if (fields != null) {
                return fields;
            }
            ArrayList<Field> ret = new ArrayList<>();
            ret.addAll(Arrays.asList(clazz.getDeclaredFields()));
            ret.addAll(Arrays.asList(classFileds(clazz.getSuperclass())));
            Iterator<Field> iterator = ret.iterator();
            while (iterator.hasNext()) {
                Field next = iterator.next();
                if (Modifier.isStatic(next.getModifiers())) {
                    iterator.remove();
                    continue;
                }
                if (next.isSynthetic()) {
                    iterator.remove();
                    continue;
                }
                if (!next.isAccessible()) {
                    next.setAccessible(true);
                }
            }
            fields = ret.toArray(new Field[0]);

            fieldCache.put(clazz, fields);
        }
        return fields;
    }


}
