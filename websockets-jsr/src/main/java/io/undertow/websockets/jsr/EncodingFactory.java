/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.websockets.jsr;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.websocket.Decoder;
import javax.websocket.DeploymentException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

import io.undertow.servlet.api.ClassIntrospecter;
import io.undertow.servlet.api.InstanceFactory;
import io.undertow.servlet.api.InstanceHandle;

/**
 * Factory class that produces encoding instances for an endpoint. This also provides static
 * methods about the capabilities of encoders.
 * <p>
 * These classes also perform implicit encodings for java primitives
 *
 * @author Stuart Douglas
 */
public class EncodingFactory {

    /**
     * An encoding factory that can deal with primitive types.
     */
    public static final EncodingFactory DEFAULT = new EncodingFactory(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());

    private final Map<Class<?>, List<InstanceFactory<? extends Encoder>>> binaryEncoders;
    private final Map<Class<?>, List<InstanceFactory<? extends Decoder>>> binaryDecoders;
    private final Map<Class<?>, List<InstanceFactory<? extends Encoder>>> textEncoders;
    private final Map<Class<?>, List<InstanceFactory<? extends Decoder>>> textDecoders;

    public EncodingFactory(final Map<Class<?>, List<InstanceFactory<? extends Encoder>>> binaryEncoders, final Map<Class<?>, List<InstanceFactory<? extends Decoder>>> binaryDecoders, final Map<Class<?>, List<InstanceFactory<? extends Encoder>>> textEncoders, final Map<Class<?>, List<InstanceFactory<? extends Decoder>>> textDecoders) {
        this.binaryEncoders = binaryEncoders;
        this.binaryDecoders = binaryDecoders;
        this.textEncoders = textEncoders;
        this.textDecoders = textDecoders;
    }

    public boolean canEncodeText(final Class<?> type) {
        if (isPrimitiveOrBoxed(type)) {
            return true;
        }
        return textEncoders.containsKey(type);
    }


    public boolean canDecodeText(final Class<?> type) {
        if (isPrimitiveOrBoxed(type)) {
            return true;
        }
        return textDecoders.containsKey(type);
    }


    public boolean canEncodeBinary(final Class<?> type) {
        return binaryEncoders.containsKey(type);
    }


    public boolean canDecodeBinary(final Class<?> type) {
        return binaryDecoders.containsKey(type);
    }

    public Encoding createEncoding(final EndpointConfig endpointConfig) {
        Map<Class<?>, List<InstanceHandle<? extends Encoder>>> binaryEncoders = registerCoders(endpointConfig, this.binaryEncoders);
        Map<Class<?>, List<InstanceHandle<? extends Decoder>>> binaryDecoders = registerCoders(endpointConfig, this.binaryDecoders);
        Map<Class<?>, List<InstanceHandle<? extends Encoder>>> textEncoders = registerCoders(endpointConfig, this.textEncoders);
        Map<Class<?>, List<InstanceHandle<? extends Decoder>>> textDecoders = registerCoders(endpointConfig, this.textDecoders);
        return new Encoding(binaryEncoders, binaryDecoders, textEncoders, textDecoders);
    }

    // Sacrifice some type checks to avoid generics-hell
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> T registerCoders(EndpointConfig endpointConfig, Map coders) {
        if (coders.isEmpty()) {
            return (T) Collections.emptyMap();
        }

        Map result = new HashMap();
        for (Map.Entry<Class<?>, List<InstanceFactory<?>>> entry : ((Map<Class<?>, List<InstanceFactory<?>>>) coders).entrySet()) {
            final List<InstanceHandle<?>> val = new ArrayList<>(entry.getValue().size());
            result.put(entry.getKey(), val);
            for (InstanceFactory<?> factory : entry.getValue()) {
                InstanceHandle<?> instance = createInstance(factory);
                initializeCoder(endpointConfig, instance);
                val.add(instance);
            }
        }
        return (T) result;
    }

    private void initializeCoder(EndpointConfig endpointConfig, InstanceHandle<?> instance) {
        if (instance.getInstance() instanceof Encoder) {
            ((Encoder) instance.getInstance()).init(endpointConfig);
        } else if (!(instance.getInstance() instanceof Decoder)) {
            ((Decoder) instance.getInstance()).init(endpointConfig);
        } else {
            throw new IllegalStateException("Illegal type: " + ((Decoder) instance.getInstance()).getClass());
        }
    }

    private static <T> InstanceHandle<T> createInstance(InstanceFactory<T> factory) {
        try {
            return factory.createInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        }
    }

    public static EncodingFactory createFactory(final ClassIntrospecter classIntrospecter, final Class<? extends Decoder>[] decoders, final Class<? extends Encoder>[] encoders) throws DeploymentException {
        return createFactory(classIntrospecter, Arrays.asList(decoders), Arrays.asList(encoders));
    }

    public static EncodingFactory createFactory(final ClassIntrospecter classIntrospecter, final List<Class<? extends Decoder>> decoders, final List<Class<? extends Encoder>> encoders) throws DeploymentException {
        final Map<Class<?>, List<InstanceFactory<? extends Encoder>>> binaryEncoders = new HashMap<>();
        final Map<Class<?>, List<InstanceFactory<? extends Decoder>>> binaryDecoders = new HashMap<>();
        final Map<Class<?>, List<InstanceFactory<? extends Encoder>>> textEncoders = new HashMap<>();
        final Map<Class<?>, List<InstanceFactory<? extends Decoder>>> textDecoders = new HashMap<>();

        for (Class<? extends Decoder> decoder : decoders) {
            if (isUnknownDecoderSubclass(decoder)) {
                throw JsrWebSocketMessages.MESSAGES.didNotImplementKnownDecoderSubclass(decoder);
            }

            tryRegisterDecoder(classIntrospecter, binaryDecoders, decoder, Decoder.Binary.class, ByteBuffer.class);
            tryRegisterDecoder(classIntrospecter, binaryDecoders, decoder, Decoder.BinaryStream.class, InputStream.class);
            tryRegisterDecoder(classIntrospecter, textDecoders, decoder, Decoder.Text.class, String.class);
            tryRegisterDecoder(classIntrospecter, textDecoders, decoder, Decoder.TextStream.class, Reader.class);
        }

        for (Class<? extends Encoder> encoder : encoders) {
            if (isUnknownEncoderSubclass(encoder)) {
                throw JsrWebSocketMessages.MESSAGES.didNotImplementKnownEncoderSubclass(encoder);
            }

            if (Encoder.Binary.class.isAssignableFrom(encoder)) {
                final Class<?> type = findEncodeMethod(encoder, ByteBuffer.class);
                List<InstanceFactory<? extends Encoder>> list = binaryEncoders.computeIfAbsent(type, k -> new ArrayList<>());
                list.add(createInstanceFactory(classIntrospecter, encoder));
            }
            if (Encoder.BinaryStream.class.isAssignableFrom(encoder)) {
                final Class<?> type = findEncodeMethod(encoder, void.class, OutputStream.class);
                List<InstanceFactory<? extends Encoder>> list = binaryEncoders.computeIfAbsent(type, k -> new ArrayList<>());
                list.add(createInstanceFactory(classIntrospecter, encoder));
            }
            if (Encoder.Text.class.isAssignableFrom(encoder)) {
                final Class<?> type = findEncodeMethod(encoder, String.class);
                List<InstanceFactory<? extends Encoder>> list = textEncoders.computeIfAbsent(type, k -> new ArrayList<>());
                list.add(createInstanceFactory(classIntrospecter, encoder));
            }
            if (Encoder.TextStream.class.isAssignableFrom(encoder)) {
                final Class<?> type = findEncodeMethod(encoder, void.class, Writer.class);
                List<InstanceFactory<? extends Encoder>> list = textEncoders.computeIfAbsent(type, k -> new ArrayList<>());
                list.add(createInstanceFactory(classIntrospecter, encoder));
            }
        }
        return new EncodingFactory(binaryEncoders, binaryDecoders, textEncoders, textDecoders);
    }

    private static boolean isUnknownEncoderSubclass(Class<? extends Encoder> encoder) {
        return !Encoder.Binary.class.isAssignableFrom(encoder)
                && !Encoder.BinaryStream.class.isAssignableFrom(encoder)
                && !Encoder.Text.class.isAssignableFrom(encoder)
                && !Encoder.TextStream.class.isAssignableFrom(encoder);
    }

    private static boolean isUnknownDecoderSubclass(Class<? extends Decoder> decoder) {
        return !Decoder.Binary.class.isAssignableFrom(decoder)
                && !Decoder.BinaryStream.class.isAssignableFrom(decoder)
                && !Decoder.Text.class.isAssignableFrom(decoder)
                && !Decoder.TextStream.class.isAssignableFrom(decoder);
    }

    private static void tryRegisterDecoder(ClassIntrospecter classIntrospecter, Map<Class<?>, List<InstanceFactory<? extends Decoder>>> binaryDecoders, Class<? extends Decoder> decoder, Class<?> decoderType, Class<?> decodedType) throws DeploymentException {
        if (!decoderType.isAssignableFrom(decoder)) {
            return;
        }
        Method method = findDecodeMethod(decoder, decodedType);
        final Class<?> type = resolveReturnType(method, decoder);
        List<InstanceFactory<? extends Decoder>> list = binaryDecoders.computeIfAbsent(type, k -> new ArrayList<>());
        list.add(createInstanceFactory(classIntrospecter, decoder));
    }

    private static Method findDecodeMethod(Class<? extends Decoder> decoder, Class<?> type) throws DeploymentException {
        try {
            return decoder.getMethod("decode", type);
        } catch (NoSuchMethodException e) {
            throw JsrWebSocketMessages.MESSAGES.couldNotDetermineTypeOfDecodeMethodForClass(decoder, e);
        }
    }

    private static Class<?> resolveReturnType(Method method, Class<? extends Decoder> decoder) {
        Type genericReturnType = method.getGenericReturnType();
        if (genericReturnType instanceof Class) {
            return (Class<?>) genericReturnType;
        } else if (genericReturnType instanceof TypeVariable) {
            TypeVariable type = ((TypeVariable) genericReturnType);
            List<Class> classes = new ArrayList<>();
            Class c = decoder;
            while (c != method.getDeclaringClass() && c != null) {
                classes.add(c);
                c = c.getSuperclass();
            }
            Collections.reverse(classes);

            String currentName = type.getName();
            int currentPos = -1;
            for (Class clz : classes) {
                for (int i = 0; i < clz.getSuperclass().getTypeParameters().length; ++i) {
                    TypeVariable<? extends Class<?>> param = clz.getSuperclass().getTypeParameters()[i];
                    if (param.getName().equals(currentName)) {
                        currentPos = i;
                        break;
                    }
                }
                Type gs = clz.getGenericSuperclass();
                if (gs instanceof ParameterizedType) {
                    ParameterizedType pt = (ParameterizedType) gs;
                    Type at = pt.getActualTypeArguments()[currentPos];
                    if (at instanceof Class) {
                        return (Class<?>) at;
                    } else if (at instanceof TypeVariable) {
                        TypeVariable tv = (TypeVariable) at;
                        currentName = tv.getName();
                    }
                }
            }
            //todo: should we throw an exception here? It should never actually be reached
            return method.getReturnType();
        } else {
            return method.getReturnType();
        }
    }

    private static <T> InstanceFactory<? extends T> createInstanceFactory(final ClassIntrospecter classIntrospecter, final Class<? extends T> decoder) throws DeploymentException {
        try {
            return classIntrospecter.createInstanceFactory(decoder);
        } catch (NoSuchMethodException e) {
            throw JsrWebSocketMessages.MESSAGES.classDoesNotHaveDefaultConstructor(decoder, e);
        }
    }

    private static Class<?> findEncodeMethod(final Class<? extends Encoder> encoder, final Class<?> returnType, Class<?>... otherParameters) throws DeploymentException {
        for (Method method : encoder.getMethods()) {
            if (method.getName().equals("encode") && !method.isBridge() &&
                    method.getParameterCount() == 1 + otherParameters.length &&
                    method.getReturnType() == returnType) {
                boolean ok = true;
                for (int i = 1; i < method.getParameterCount(); ++i) {
                    if (method.getParameterTypes()[i] != otherParameters[i - 1]) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return method.getParameterTypes()[0];
                }
            }
        }
        throw JsrWebSocketMessages.MESSAGES.couldNotDetermineTypeOfEncodeMethodForClass(encoder);
    }


    static boolean isPrimitiveOrBoxed(final Class<?> clazz) {
        return clazz.isPrimitive() ||
                clazz == Boolean.class ||
                clazz == Byte.class ||
                clazz == Character.class ||
                clazz == Short.class ||
                clazz == Integer.class ||
                clazz == Long.class ||
                clazz == Float.class ||
                clazz == Double.class;//we don't care about void
    }
}
