/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.graalvm.options.OptionDescriptor;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.polyglot.Context;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Scope;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameUtil;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.java.JavaInterop;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExecutableNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.NodeFactory;
import com.oracle.truffle.js.nodes.ScriptNode;
import com.oracle.truffle.js.nodes.access.ScopeFrameNode;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.BinaryExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.BuiltinRootTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowBlockTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowBranchTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowRootTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ReadElementExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.WriteElementExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.WritePropertyExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.EvalCallTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.FunctionCallExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.LiteralExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ObjectAllocationExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ReadPropertyExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ReadVariableExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.UnaryExpressionTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.WriteVariableExpressionTag;
import com.oracle.truffle.js.nodes.interop.ExportValueNode;
import com.oracle.truffle.js.parser.env.DebugEnvironment;
import com.oracle.truffle.js.parser.env.Environment;
import com.oracle.truffle.js.parser.foreign.InteropBoundFunctionMRForeign;
import com.oracle.truffle.js.parser.foreign.JSForeignAccessFactoryForeign;
import com.oracle.truffle.js.runtime.AbstractJavaScriptLanguage;
import com.oracle.truffle.js.runtime.Evaluator;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSContextOptions;
import com.oracle.truffle.js.runtime.JSFrameUtil;
import com.oracle.truffle.js.runtime.JSInteropRuntime;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.ParserOptions;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSDate;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSSymbol;
import com.oracle.truffle.js.runtime.builtins.JSUserObject;
import com.oracle.truffle.js.runtime.objects.JSLazyString;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.Null;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.truffleinterop.InteropBoundFunction;
import com.oracle.truffle.js.runtime.truffleinterop.JSInteropNodeUtil;

@ProvidedTags({StandardTags.CallTag.class,
                StandardTags.StatementTag.class,
                DebuggerTags.AlwaysHalt.class,
                StandardTags.RootTag.class,
                StandardTags.ExpressionTag.class,
                // Expressions
                ObjectAllocationExpressionTag.class,
                BinaryExpressionTag.class,
                UnaryExpressionTag.class,
                WriteVariableExpressionTag.class,
                ReadElementExpressionTag.class,
                WriteElementExpressionTag.class,
                ReadPropertyExpressionTag.class,
                WritePropertyExpressionTag.class,
                ReadVariableExpressionTag.class,
                LiteralExpressionTag.class,
                FunctionCallExpressionTag.class,
                // Statements and builtins
                BuiltinRootTag.class,
                EvalCallTag.class,
                ControlFlowRootTag.class,
                ControlFlowBlockTag.class,
                ControlFlowBranchTag.class
})

@TruffleLanguage.Registration(id = JavaScriptLanguage.ID, name = JavaScriptLanguage.NAME, version = JavaScriptLanguage.VERSION_NUMBER, mimeType = {
                JavaScriptLanguage.APPLICATION_MIME_TYPE, JavaScriptLanguage.TEXT_MIME_TYPE})
public class JavaScriptLanguage extends AbstractJavaScriptLanguage {
    private static final HiddenKey META_OBJECT_KEY = new HiddenKey("meta object");

    private final Map<ParserOptions, Queue<JSContext>> contextPools = new ConcurrentHashMap<>();
    private volatile Boolean useContextPool;

    public static final OptionDescriptors OPTION_DESCRIPTORS;
    static {
        ArrayList<OptionDescriptor> options = new ArrayList<>();
        GraalJSParserOptions.describeOptions(options);
        JSContextOptions.describeOptions(options);
        OPTION_DESCRIPTORS = OptionDescriptors.create(options);
    }

    @Override
    public boolean isObjectOfLanguage(Object o) {
        return JSObject.isJSObject(o) || o instanceof Symbol || o instanceof JSLazyString || o instanceof InteropBoundFunction;
    }

    @TruffleBoundary
    @Override
    public CallTarget parse(ParsingRequest parsingRequest) {
        Source source = parsingRequest.getSource();
        List<String> argumentNames = parsingRequest.getArgumentNames();
        if (argumentNames == null || argumentNames.isEmpty()) {
            final JSContext context = getContextReference().get().getContext();

            if (context.isOptionParseOnly()) {
                parseInContext(source, context);
                return createEmptyScript(context).getCallTarget();
            }

            Object cached = context.getCodeCache().get(source);
            if (cached != null) {
                return (CallTarget) cached;
            }

            final ScriptNode program = parseInContext(source, context);

            RootNode rootNode = new RootNode(this) {
                @Child private DirectCallNode directCallNode = DirectCallNode.create(program.getCallTarget());
                @Child private ExportValueNode exportValueNode = ExportValueNode.create();

                @Override
                public Object execute(VirtualFrame frame) {
                    JSRealm realm = getContextReference().get();
                    JSContext currentContext = realm.getContext();
                    assert currentContext == context : "unexpected JSContext";
                    try {
                        context.interopBoundaryEnter();
                        Object result = directCallNode.call(program.argumentsToRun(realm));
                        return exportValueNode.executeWithTarget(result, Undefined.instance);
                    } finally {
                        context.interopBoundaryExit();
                    }
                }

                @Override
                public boolean isInternal() {
                    return true;
                }
            };
            CallTarget callTarget = Truffle.getRuntime().createCallTarget(rootNode);
            context.getCodeCache().putIfAbsent(source, callTarget);
            return callTarget;
        } else {
            RootNode rootNode = parseWithArgumentNames(source, argumentNames);
            return Truffle.getRuntime().createCallTarget(rootNode);
        }
    }

    @TruffleBoundary
    private static ScriptNode createEmptyScript(JSContext context) {
        return ScriptNode.fromFunctionData(context, JSFunction.createEmptyFunctionData(context));
    }

    @Override
    protected ExecutableNode parse(InlineParsingRequest request) throws Exception {
        final Source source = request.getSource();
        final MaterializedFrame requestFrame = request.getFrame();
        final JSContext context = getContextReference().get().getContext();
        final ExecutableNode executableNode = new ExecutableNode(this) {
            @Child private JavaScriptNode expression = insert(parseInline(source, context, requestFrame));
            @Child private ExportValueNode exportValueNode = ExportValueNode.create();

            @Override
            public Object execute(VirtualFrame frame) {
                JSContext currentContext = getContextReference().get().getContext();
                assert currentContext == context : "unexpected JSContext";
                Object result = expression.execute(frame);
                return exportValueNode.executeWithTarget(result, Undefined.instance);
            }
        };
        return executableNode;
    }

    private RootNode parseWithArgumentNames(Source source, List<String> argumentNames) {
        return new RootNode(this) {
            @Override
            public Object execute(VirtualFrame frame) {
                return executeImpl(getContextReference().get(), frame.getArguments());
            }

            @TruffleBoundary
            private Object executeImpl(JSRealm realm, Object[] arguments) {
                // (GR-2039) only works for simple expressions at the moment. needs parser support.
                StringBuilder code = new StringBuilder();
                code.append("(function");
                code.append(" (");
                assert !argumentNames.isEmpty();
                code.append(argumentNames.get(0));
                for (int i = 1; i < argumentNames.size(); i++) {
                    code.append(", ");
                    code.append(argumentNames.get(i));
                }
                code.append(") {\n");
                code.append("return ");
                code.append(source.getCharacters());
                code.append("\n})");
                Source wrappedSource = Source.newBuilder(code.toString()).name(Evaluator.FUNCTION_SOURCE_NAME).language(ID).build();
                Object function = parseInContext(wrappedSource, realm.getContext()).run(realm);
                return JSRuntime.jsObjectToJavaObject(JSFunction.call(JSArguments.create(Undefined.instance, function, arguments)));
            }
        };
    }

    @TruffleBoundary
    @Override
    protected String toString(JSRealm realm, Object value) {
        if (value == null) {
            return "null";
        } else if (JSObject.isJSObject(value)) {
            DynamicObject object = (DynamicObject) value;
            if (object.containsKey(META_OBJECT_KEY)) {
                Object type = JSObject.get(object, "className");
                if (type == Undefined.instance) {
                    type = JSObject.get(object, "type");
                }
                return type.toString();
            }
        } else if (value instanceof Symbol) {
            return value.toString();
        } else if (value instanceof JSLazyString) {
            return value.toString();
        } else if (value instanceof TruffleObject) {
            TruffleObject truffleObject = (TruffleObject) value;
            try {
                if (JavaInterop.isJavaObject(truffleObject)) {
                    Class<?> clazz = JavaInterop.asJavaObject(Class.class, JavaInterop.toJavaClass(truffleObject));
                    if (clazz == Class.class) {
                        clazz = JavaInterop.asJavaObject(Class.class, truffleObject);
                        return "JavaClass[" + clazz.getTypeName() + "]";
                    } else {
                        return "JavaObject[" + clazz.getTypeName() + "]";
                    }
                } else if (ForeignAccess.sendIsNull(Message.IS_NULL.createNode(), truffleObject)) {
                    return "null";
                } else if (ForeignAccess.sendIsPointer(Message.IS_POINTER.createNode(), truffleObject)) {
                    long pointer = ForeignAccess.sendAsPointer(Message.AS_POINTER.createNode(), truffleObject);
                    return "Pointer[0x" + Long.toHexString(pointer) + "]";
                } else if (ForeignAccess.sendHasSize(Message.HAS_SIZE.createNode(), truffleObject)) {
                    List<?> list = JavaInterop.asJavaObject(List.class, truffleObject);
                    return "Array" + list.toString();
                } else if (ForeignAccess.sendIsExecutable(Message.IS_EXECUTABLE.createNode(), truffleObject)) {
                    return "Executable";
                } else {
                    Map<?, ?> map = JavaInterop.asJavaObject(Map.class, truffleObject);
                    return "Object" + map.toString();
                }
            } catch (Exception e) {
                return "Object";
            }
        }
        return JSRuntime.safeToString(value);
    }

    @TruffleBoundary
    protected static ScriptNode parseInContext(Source code, JSContext context) {
        long startTime = JSTruffleOptions.ProfileTime ? System.nanoTime() : 0L;
        try {
            return ((JSParser) context.getEvaluator()).parseScriptNode(context, code);
        } finally {
            if (JSTruffleOptions.ProfileTime) {
                context.getTimeProfiler().printElapsed(startTime, "parsing " + code.getName());
            }
        }
    }

    @TruffleBoundary
    protected static JavaScriptNode parseInline(Source code, JSContext context, MaterializedFrame lexicalContextFrame) {
        long startTime = JSTruffleOptions.ProfileTime ? System.nanoTime() : 0L;
        try {
            Environment env = assembleDebugEnvironment(context, lexicalContextFrame);
            return ((JSParser) context.getEvaluator()).parseInlineExpression(context, code, env, true);
        } finally {
            if (JSTruffleOptions.ProfileTime) {
                context.getTimeProfiler().printElapsed(startTime, "parsing " + code.getName());
            }
        }
    }

    private static Environment assembleDebugEnvironment(JSContext context, MaterializedFrame lexicalContextFrame) {
        Environment env = null;
        ArrayList<FrameDescriptor> frameDescriptors = new ArrayList<>();
        Frame frame = lexicalContextFrame;
        while (frame != null && frame != JSFrameUtil.NULL_MATERIALIZED_FRAME) {
            assert isJSArgumentsArray(frame.getArguments());
            FrameSlot parentSlot;
            while ((parentSlot = frame.getFrameDescriptor().findFrameSlot(ScopeFrameNode.PARENT_SCOPE_IDENTIFIER)) != null) {
                frameDescriptors.add(frame.getFrameDescriptor());
                frame = (Frame) FrameUtil.getObjectSafe(frame, parentSlot);
            }
            frameDescriptors.add(frame.getFrameDescriptor());
            frame = JSArguments.getEnclosingFrame(frame.getArguments());
        }

        for (int i = frameDescriptors.size() - 1; i >= 0; i--) {
            env = new DebugEnvironment(env, NodeFactory.getInstance(context), context, frameDescriptors.get(i));
        }
        return env;
    }

    private static boolean isJSArgumentsArray(Object[] arguments) {
        return arguments != null && arguments.length >= JSArguments.RUNTIME_ARGUMENT_COUNT && JSFunction.isJSFunction(JSArguments.getFunctionObject(arguments));
    }

    @Override
    protected JSRealm createContext(Env env) {
        if (useContextPool == null) {
            useContextPool = JSContextOptions.CODE_SHARING.getValue(env.getOptions()).equals("pool");
        }

        JSContext languageContext = null;
        TruffleContext parent = env.getContext().getParent();
        if (parent == null) {
            if (useContextPool() && !contextPools.isEmpty()) {
                languageContext = pollContextPool(GraalJSParserOptions.fromOptions(env.getOptions()));
            }
            if (languageContext == null) {
                languageContext = newJSContext(env);
            }
        } else {
            Object prev = parent.enter();
            try {
                languageContext = getCurrentContext(JavaScriptLanguage.class).getContext();
            } finally {
                parent.leave(prev);
            }
        }
        JSRealm realm = languageContext.createRealm(env);
        return realm;
    }

    private JSContext newJSContext(Env env) {
        JSContext context = JSEngine.createJSContext(this, env);

        /*
         * Ensure that we use the output stream provided by env, but avoid creating a new
         * PrintWriter when the existing PrintWriter already uses the same stream.
         */
        if (env.out() != context.getWriterStream()) {
            context.setWriter(null, env.out());
        }
        if (env.err() != context.getErrorWriterStream()) {
            context.setErrorWriter(null, env.err());
        }

        if (JSContextOptions.TIME_ZONE.hasBeenSet(env.getOptions())) {
            context.setLocalTimeZoneId(TimeZone.getTimeZone(JSContextOptions.TIME_ZONE.getValue(env.getOptions())).toZoneId());
        }

        context.setInteropRuntime(new JSInteropRuntime(JSForeignAccessFactoryForeign.ACCESS, InteropBoundFunctionMRForeign.ACCESS));
        return context;
    }

    @Override
    protected void initializeContext(JSRealm realm) {
        realm.setArguments(realm.getEnv().getApplicationArguments());

        if (((GraalJSParserOptions) realm.getContext().getParserOptions()).isScripting()) {
            realm.addScriptingOptionsObject();
        }
    }

    @Override
    protected boolean patchContext(JSRealm realm, Env newEnv) {
        JSContext context = realm.getContext();
        if (!JSContextOptions.optionsAllowPreInitializedContext(realm, newEnv)) {
            return false;
        }

        assert context.getLanguage() == this;
        realm.patchTruffleLanguageEnv(newEnv);

        if (newEnv.out() != context.getWriterStream()) {
            context.setWriter(null, newEnv.out());
        }
        if (newEnv.err() != context.getErrorWriterStream()) {
            context.setErrorWriter(null, newEnv.err());
        }

        if (JSContextOptions.TIME_ZONE.hasBeenSet(newEnv.getOptions())) {
            context.setLocalTimeZoneId(TimeZone.getTimeZone(JSContextOptions.TIME_ZONE.getValue(newEnv.getOptions())).toZoneId());
        }

        context.setInteropRuntime(new JSInteropRuntime(JSForeignAccessFactoryForeign.ACCESS, InteropBoundFunctionMRForeign.ACCESS));
        realm.setArguments(newEnv.getApplicationArguments());

        if (((GraalJSParserOptions) context.getParserOptions()).isScripting()) {
            realm.addScriptingOptionsObject();
        }
        return true;
    }

    @Override
    protected void disposeContext(JSRealm realm) {
        if (useContextPool() && !realm.isChildRealm()) {
            JSContext context = realm.getContext();
            Queue<JSContext> contextPool = getContextPool(context.getParserOptions());
            assert !contextPool.contains(context);
            contextPool.offer(context);
        }
    }

    private Queue<JSContext> getContextPool(ParserOptions configKey) {
        return contextPools.computeIfAbsent(configKey, k -> new ConcurrentLinkedQueue<>());
    }

    private JSContext pollContextPool(ParserOptions configKey) {
        Queue<JSContext> contextPool = contextPools.get(configKey);
        return contextPool == null ? null : contextPool.poll();
    }

    private boolean useContextPool() {
        return useContextPool;
    }

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return OPTION_DESCRIPTORS;
    }

    @TruffleBoundary
    @Override
    protected Object findMetaObject(JSRealm realm, Object value) {
        String type;
        String subtype = null;
        String className = null;
        String description;
        JSContext context = realm.getContext();

        if (JSObject.isJSObject(value)) {
            DynamicObject obj = (DynamicObject) value;
            type = "object";
            description = JSObject.safeToString(obj);
            className = obj == Undefined.instance ? "undefined" : JSRuntime.getConstructorName(obj);

            if (JSFunction.isJSFunction(obj)) {
                DynamicObject func = obj;
                if (JSFunction.isBoundFunction(func)) {
                    func = JSFunction.getBoundTargetFunction(func);
                }
                description = JSObject.safeToString(func);
            } else if (JSArray.isJSArray(obj)) {
                description = JSArray.CLASS_NAME + "[" + JSArray.arrayGetLength(obj) + "]";
            } else if (JSDate.isJSDate(obj)) {
                subtype = "date";
                description = JSDate.formatUTC(JSDate.getJSDateUTCFormat(), JSDate.getTimeMillisField(obj));
            } else if (JSSymbol.isJSSymbol(obj)) {
                Symbol sym = JSSymbol.getSymbolData(obj);
                type = "symbol";
                description = "Symbol(" + sym.getName() + ")";
            } else if (value == Undefined.instance) {
                type = "undefined";
                description = "undefined";
            } else if (value == Null.instance) {
                description = "null";
            } else if (JSUserObject.isJSUserObject(obj)) {
                description = className;
            }
        } else if (value instanceof TruffleObject && !(value instanceof Symbol) && !(value instanceof JSLazyString)) {
            assert !JSObject.isJSObject(value);
            TruffleObject truffleObject = (TruffleObject) value;
            if (JSInteropNodeUtil.isBoxed(truffleObject)) {
                return findMetaObject(realm, JSInteropNodeUtil.unbox(truffleObject));
            } else if (JavaInterop.isJavaObject(Symbol.class, truffleObject)) {
                return findMetaObject(realm, JavaInterop.asJavaObject(truffleObject));
            } else if (value instanceof InteropBoundFunction) {
                return findMetaObject(realm, ((InteropBoundFunction) value).getFunction());
            }
            type = "object";
            className = "Foreign";
            description = "foreign TruffleObject";
        } else if (value == null) {
            type = "null";
            description = "null";
        } else {
            // primitive
            type = JSRuntime.typeof(value);
            if (value instanceof Symbol) {
                description = "Symbol(" + ((Symbol) value).getName() + ")";
            } else {
                description = JSRuntime.toString(value);
            }
        }

        // avoid allocation profiling
        DynamicObject metaObject = realm.getInitialUserObjectShape().newInstance();
        JSObjectUtil.putDataProperty(context, metaObject, "type", type);
        if (subtype != null) {
            JSObjectUtil.putDataProperty(context, metaObject, "subtype", subtype);
        }
        if (className != null) {
            JSObjectUtil.putDataProperty(context, metaObject, "className", className);
        }
        if (description != null) {
            JSObjectUtil.putDataProperty(context, metaObject, "description", description);
        }
        metaObject.define(META_OBJECT_KEY, true);
        return metaObject;
    }

    @Override
    protected SourceSection findSourceLocation(JSRealm realm, Object value) {
        if (JSFunction.isJSFunction(value)) {
            DynamicObject func = (DynamicObject) value;
            CallTarget ct = JSFunction.getCallTarget(func);
            if (JSFunction.isBoundFunction(func)) {
                func = JSFunction.getBoundTargetFunction(func);
                ct = JSFunction.getCallTarget(func);
            }

            if (ct instanceof RootCallTarget) {
                return ((RootCallTarget) ct).getRootNode().getSourceSection();
            }
        }
        return null;
    }

    @Override
    protected boolean isVisible(JSRealm realm, Object value) {
        return (value != Undefined.instance);
    }

    @Override
    protected Iterable<Scope> findLocalScopes(JSRealm realm, Node node, Frame frame) {
        return JSScope.createLocalScopes(node, frame.materialize());
    }

    @Override
    protected Iterable<Scope> findTopScopes(JSRealm realm) {
        return JSScope.createGlobalScopes(realm);
    }

    public static JSContext getJSContext(Context context) {
        return getJSRealm(context).getContext();
    }

    public static JSRealm getJSRealm(Context context) {
        context.enter();
        try {
            context.initialize(ID);
            return getCurrentContext(JavaScriptLanguage.class);
        } finally {
            context.leave();
        }
    }
}
