/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CacheableSupplier;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.SystemPropertyConfigUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.Decodeable;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.remoting.transport.ExceedPayloadLimitException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.FrameworkServiceRepository;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.protocol.PermittedSerializationKeeper;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.apache.dubbo.common.BaseServiceMetadata.keyWithoutGroup;
import static org.apache.dubbo.common.URL.buildKey;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PAYLOAD;
import static org.apache.dubbo.common.constants.CommonConstants.SystemProperty.SERIALIZATION_SECURITY_CHECK_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.PROTOCOL_FAILED_DECODE;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.TRANSPORT_EXCEED_PAYLOAD_LIMIT;
import static org.apache.dubbo.rpc.Constants.SERIALIZATION_ID_KEY;

public class DecodeableRpcInvocation extends RpcInvocation implements Codec, Decodeable {

    protected static final ErrorTypeAwareLogger log =
            LoggerFactory.getErrorTypeAwareLogger(DecodeableRpcInvocation.class);

    protected final transient Channel channel;

    protected final byte serializationType;

    protected final transient InputStream inputStream;

    protected final transient Request request;

    protected volatile boolean hasDecoded;

    protected final FrameworkModel frameworkModel;

    protected final transient Supplier<CallbackServiceCodec> callbackServiceCodecFactory;

    private static final boolean CHECK_SERIALIZATION =
            Boolean.parseBoolean(SystemPropertyConfigUtils.getSystemProperty(SERIALIZATION_SECURITY_CHECK_KEY, "true"));

    public DecodeableRpcInvocation(
            FrameworkModel frameworkModel, Channel channel, Request request, InputStream is, byte id) {
        this.frameworkModel = frameworkModel;
        Assert.notNull(channel, "channel == null");
        Assert.notNull(request, "request == null");
        Assert.notNull(is, "inputStream == null");
        this.channel = channel;
        this.request = request;
        this.inputStream = is;
        this.serializationType = id;
        this.callbackServiceCodecFactory =
                CacheableSupplier.newSupplier(() -> new CallbackServiceCodec(frameworkModel));
    }

    @Override
    public void decode() throws Exception {
        if (!hasDecoded && channel != null && inputStream != null) {
            try {
                decode(channel, inputStream);
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn(PROTOCOL_FAILED_DECODE, "", "", "Decode rpc invocation failed: " + e.getMessage(), e);
                }
                request.setBroken(true);
                request.setData(e);
            } finally {
                hasDecoded = true;
            }
        }
    }

    @Override
    public void encode(Channel channel, OutputStream output, Object message) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object decode(Channel channel, InputStream input) throws IOException {
        int contentLength = input.available();
        getAttributes().put(Constants.CONTENT_LENGTH_KEY, contentLength);
        Object sslSession = channel.getAttribute(Constants.SSL_SESSION_KEY);
        if (null != sslSession) {
            put(Constants.SSL_SESSION_KEY, sslSession);
        }

        ObjectInput in = CodecSupport.getSerialization(serializationType).deserialize(channel.getUrl(), input);
        this.put(SERIALIZATION_ID_KEY, serializationType);

        String dubboVersion = in.readUTF();
        request.setVersion(dubboVersion);
        setAttachment(DUBBO_VERSION_KEY, dubboVersion);

        String path = in.readUTF();
        setAttachment(PATH_KEY, path);
        String version = in.readUTF();
        setAttachment(VERSION_KEY, version);

        // Do provider-level payload checks.
        String keyWithoutGroup = keyWithoutGroup(path, version);
        checkPayload(keyWithoutGroup);

        setMethodName(in.readUTF());

        String desc = in.readUTF();
        setParameterTypesDesc(desc);

        ClassLoader originClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            if (CHECK_SERIALIZATION) {
                PermittedSerializationKeeper keeper =
                        frameworkModel.getBeanFactory().getBean(PermittedSerializationKeeper.class);
                if (!keeper.checkSerializationPermitted(keyWithoutGroup, serializationType)) {
                    throw new IOException("Unexpected serialization id:" + serializationType
                            + " received from network, please check if the peer send the right id.");
                }
            }
            Object[] args = DubboCodec.EMPTY_OBJECT_ARRAY;
            Class<?>[] pts = DubboCodec.EMPTY_CLASS_ARRAY;
            if (desc.length() > 0) {
                pts = drawPts(path, version, desc, pts);
                if (pts == DubboCodec.EMPTY_CLASS_ARRAY) {
                    if (RpcUtils.isGenericCall(desc, getMethodName())) {
                        // Should recreate here for each invocation because the parameterTypes may be changed by user.
                        pts = new Class<?>[] {String.class, String[].class, Object[].class};
                    } else if (RpcUtils.isEcho(desc, getMethodName())) {
                        pts = new Class<?>[] {Object.class};
                    } else {
                        throw new IllegalArgumentException("Service not found:" + path + ", " + getMethodName());
                    }
                }
                args = drawArgs(in, pts);
            }
            setParameterTypes(pts);

            Map<String, Object> map = in.readAttachments();
            if (CollectionUtils.isNotEmptyMap(map)) {
                addObjectAttachments(map);
            }

            decodeArgument(channel, pts, args);
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read invocation data failed.", e));
        } finally {
            Thread.currentThread().setContextClassLoader(originClassLoader);
            if (in instanceof Cleanable) {
                ((Cleanable) in).cleanup();
            }
        }
        return this;
    }

    protected void decodeArgument(Channel channel, Class<?>[] pts, Object[] args) throws IOException {
        CallbackServiceCodec callbackServiceCodec = callbackServiceCodecFactory.get();
        for (int i = 0; i < args.length; i++) {
            args[i] = callbackServiceCodec.decodeInvocationArgument(channel, this, pts, i, args[i]);
        }

        setArguments(args);
        String targetServiceName =
                buildKey(getAttachment(PATH_KEY), getAttachment(GROUP_KEY), getAttachment(VERSION_KEY));
        setTargetServiceUniqueName(targetServiceName);
    }

    protected Class<?>[] drawPts(String path, String version, String desc, Class<?>[] pts) {
        FrameworkServiceRepository repository = frameworkModel.getServiceRepository();
        List<ProviderModel> providerModels =
                repository.lookupExportedServicesWithoutGroup(keyWithoutGroup(path, version));
        ServiceDescriptor serviceDescriptor = null;
        if (CollectionUtils.isNotEmpty(providerModels)) {
            for (ProviderModel providerModel : providerModels) {
                serviceDescriptor = providerModel.getServiceModel();
                if (serviceDescriptor != null) {
                    break;
                }
            }
        }
        if (serviceDescriptor == null) {
            // Unable to find ProviderModel from Exported Services
            for (ApplicationModel applicationModel : frameworkModel.getApplicationModels()) {
                for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
                    serviceDescriptor = moduleModel.getServiceRepository().lookupService(path);
                    if (serviceDescriptor != null) {
                        break;
                    }
                }
            }
        }

        if (serviceDescriptor != null) {
            MethodDescriptor methodDescriptor = serviceDescriptor.getMethod(getMethodName(), desc);
            if (methodDescriptor != null) {
                pts = methodDescriptor.getParameterClasses();
                this.setReturnTypes(methodDescriptor.getReturnTypes());

                // switch TCCL
                if (CollectionUtils.isNotEmpty(providerModels)) {
                    if (providerModels.size() == 1) {
                        Thread.currentThread()
                                .setContextClassLoader(providerModels.get(0).getClassLoader());
                    } else {
                        // try all providerModels' classLoader can load pts, use the first one
                        for (ProviderModel providerModel : providerModels) {
                            ClassLoader classLoader = providerModel.getClassLoader();
                            boolean match = true;
                            for (Class<?> pt : pts) {
                                try {
                                    if (!pt.equals(classLoader.loadClass(pt.getName()))) {
                                        match = false;
                                    }
                                } catch (ClassNotFoundException e) {
                                    match = false;
                                }
                            }
                            if (match) {
                                Thread.currentThread().setContextClassLoader(classLoader);
                                break;
                            }
                        }
                    }
                }
            }
        }
        return pts;
    }

    protected Object[] drawArgs(ObjectInput in, Class<?>[] pts) throws IOException, ClassNotFoundException {
        Object[] args;
        args = new Object[pts.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = in.readObject(pts[i]);
        }
        return args;
    }

    private void checkPayload(String serviceKey) throws IOException {
        ProviderModel providerModel =
                frameworkModel.getServiceRepository().lookupExportedServiceWithoutGroup(serviceKey);
        if (providerModel != null) {
            String payloadStr =
                    (String) providerModel.getServiceMetadata().getAttachments().get(PAYLOAD);
            if (payloadStr != null) {
                int payload = Integer.parseInt(payloadStr);
                if (payload <= 0) {
                    return;
                }
                if (request.getPayload() > payload) {
                    ExceedPayloadLimitException e = new ExceedPayloadLimitException("Data length too large: "
                            + request.getPayload() + ", max payload: " + payload + ", channel: " + channel);
                    log.error(TRANSPORT_EXCEED_PAYLOAD_LIMIT, "", "", e.getMessage(), e);
                    throw e;
                }
            }
        }
    }

    protected void fillInvoker(DubboProtocol dubboProtocol) throws RemotingException {
        this.setInvoker(dubboProtocol.getInvoker(channel, this));
    }
}
