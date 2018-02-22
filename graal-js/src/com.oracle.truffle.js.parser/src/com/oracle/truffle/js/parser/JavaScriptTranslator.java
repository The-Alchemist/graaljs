/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.parser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.oracle.js.parser.ir.Block;
import com.oracle.js.parser.ir.FunctionNode;
import com.oracle.js.parser.ir.Module;
import com.oracle.js.parser.ir.Module.ExportEntry;
import com.oracle.js.parser.ir.Module.ImportEntry;
import com.oracle.js.parser.ir.Symbol;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.NodeFactory;
import com.oracle.truffle.js.nodes.ScriptNode;
import com.oracle.truffle.js.nodes.function.FunctionRootNode;
import com.oracle.truffle.js.nodes.function.JSFunctionExpressionNode;
import com.oracle.truffle.js.parser.env.Environment;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.objects.ExportResolution;
import com.oracle.truffle.js.runtime.objects.JSModuleLoader;
import com.oracle.truffle.js.runtime.objects.JSModuleRecord;

public final class JavaScriptTranslator extends GraalJSTranslator {
    private JSModuleRecord moduleRecord;

    private JavaScriptTranslator(NodeFactory factory, JSContext context, Source source, Environment environment, boolean isParentStrict) {
        super(factory, context, source, environment, isParentStrict);
    }

    public static ScriptNode translateScript(NodeFactory factory, JSContext context, Source source, boolean isParentStrict) {
        return translateScript(factory, context, null, source, isParentStrict, false, false);
    }

    public static ScriptNode translateEvalScript(NodeFactory factory, JSContext context, Environment env, Source source, boolean isParentStrict) {
        boolean evalInGlobalScope = env == null || env.getParent() == null || (env.getParent().function() != null && env.getParent().function().isGlobal());
        return translateScript(factory, context, env, source, isParentStrict, true, evalInGlobalScope);
    }

    private static ScriptNode translateScript(NodeFactory nodeFactory, JSContext context, Environment env, Source source, boolean isParentStrict, boolean isEval, boolean evalInGlobalScope) {
        FunctionNode parserFunctionNode = GraalJSParserHelper.parseScript(source, ((GraalJSParserOptions) context.getParserOptions()).putStrict(isParentStrict), isEval, evalInGlobalScope);
        Source src = source;
        String explicitURL = parserFunctionNode.getSource().getExplicitURL();
        if (explicitURL != null) {
            src = Source.newBuilder(source.getCharacters()).name(explicitURL).mimeType(source.getMimeType()).build();
        }
        return translateFunction(nodeFactory, context, env, src, isParentStrict, parserFunctionNode);
    }

    public static ScriptNode translateFunction(NodeFactory factory, JSContext context, Environment env, Source source, boolean isParentStrict, com.oracle.js.parser.ir.FunctionNode rootNode) {
        return new JavaScriptTranslator(factory, context, source, env, isParentStrict).translateScript(rootNode);
    }

    public static JavaScriptNode translateExpression(NodeFactory factory, JSContext context, Environment env, Source source, boolean isParentStrict, com.oracle.js.parser.ir.Expression expression) {
        return new JavaScriptTranslator(factory, context, source, env, isParentStrict).translateExpression(expression);
    }

    public static JSModuleRecord translateModule(NodeFactory factory, JSContext context, Source source, JSModuleLoader moduleLoader) {
        FunctionNode parsed = GraalJSParserHelper.parseModule(source, ((GraalJSParserOptions) context.getParserOptions()).putStrict(true));
        JavaScriptTranslator translator = new JavaScriptTranslator(factory, context, source, null, true);
        return translator.moduleRecord = new JSModuleRecord(parsed.getModule(), context, moduleLoader, source, () -> translator.translateModule(parsed));
    }

    private JSModuleRecord translateModule(com.oracle.js.parser.ir.FunctionNode functionNode) {
        if (functionNode.getKind() != com.oracle.js.parser.ir.FunctionNode.Kind.MODULE) {
            throw new IllegalArgumentException("root function node is not a module");
        }
        JSFunctionExpressionNode functionExpression = (JSFunctionExpressionNode) transformFunction(functionNode);
        FunctionRootNode functionRoot = functionExpression.getFunctionNode();
        moduleRecord.setFunctionData(functionRoot.getFunctionData());
        return moduleRecord;
    }

    @Override
    protected List<JavaScriptNode> setupModuleEnvironment(FunctionNode functionNode) {
        assert functionNode.isModule();
        final List<JavaScriptNode> declarations = new ArrayList<>();

        Block moduleBlock = functionNode.getBody();
        Module module = (Module) moduleRecord.getModule();
        GraalJSEvaluator evaluator = (GraalJSEvaluator) context.getEvaluator();
        // Assert: all named exports from module are resolvable.
        for (ImportEntry importEntry : module.getImportEntries()) {
            JSModuleRecord importedModule = evaluator.hostResolveImportedModule(moduleRecord, importEntry.getModuleRequest());
            if (importEntry.getImportName().equals(Module.STAR_NAME)) {
                Symbol symbol = new Symbol(importEntry.getLocalName(), Symbol.IS_CONST | Symbol.HAS_BEEN_DECLARED);
                moduleBlock.putSymbol(lc, symbol);
                // GetModuleNamespace(importedModule)
                DynamicObject namespace = evaluator.getModuleNamespace(importedModule);
                // envRec.CreateImmutableBinding(in.[[LocalName]], true).
                // Call envRec.InitializeBinding(in.[[LocalName]], namespace).
                declarations.add(factory.createLazyWriteFrameSlot(importEntry.getLocalName(), factory.createConstant(namespace)));
            } else {
                Symbol symbol = new Symbol(importEntry.getLocalName(), Symbol.IS_CONST | Symbol.IS_IMPORT_BINDING);
                moduleBlock.putSymbol(lc, symbol);
                // Let resolution be importedModule.ResolveExport(in.[[ImportName]], << >>, << >>).
                // If resolution is null or resolution is "ambiguous", throw SyntaxError.
                // Call envRec.CreateImportBinding(in.[[LocalName]], resolution.[[module]],
                // resolution.[[bindingName]]).
                ExportResolution resolution = evaluator.resolveExport(importedModule, importEntry.getImportName());
                assert !(resolution.isNull() || resolution.isAmbiguous());
                createImportBinding(importEntry.getLocalName(), resolution.getModule(), resolution.getBindingName());
            }
        }

        // Check for duplicate exports
        verifyModuleExportedNames();

        declarations.add(factory.createSetModuleEnvironment(moduleRecord));
        return declarations;
    }

    private void verifyModuleExportedNames() {
        Module module = (Module) moduleRecord.getModule();
        Set<String> exportedNames = new HashSet<>();
        for (ExportEntry exportEntry : module.getLocalExportEntries()) {
            // Assert: module provides the direct binding for this export.
            if (!exportedNames.add(exportEntry.getExportName())) {
                throw Errors.createSyntaxError("Duplicate export");
            }
        }
        for (ExportEntry exportEntry : module.getIndirectExportEntries()) {
            // Assert: module imports a specific binding for this export.
            if (!exportedNames.add(exportEntry.getExportName())) {
                throw Errors.createSyntaxError("Duplicate export");
            }
        }
    }

    @Override
    protected void verifyModuleLocalExports(Block bodyBlock) {
        Module module = (Module) moduleRecord.getModule();
        for (ExportEntry exportEntry : module.getLocalExportEntries()) {
            if (!bodyBlock.hasSymbol(exportEntry.getLocalName())) {
                throw Errors.createSyntaxError(String.format("Export specifies undeclared identifier: '%s'", exportEntry.getLocalName()));
            }
        }
    }

    @Override
    protected GraalJSTranslator newTranslator(Environment env) {
        return new JavaScriptTranslator(factory, context, source, env, false);
    }
}
