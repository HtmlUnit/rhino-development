/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// API class

package org.mozilla.javascript;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.mozilla.classfile.ClassFileWriter.ClassFileFormatException;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.ScriptNode;
import org.mozilla.javascript.debug.DebuggableScript;
import org.mozilla.javascript.debug.Debugger;
import org.mozilla.javascript.lc.type.TypeInfo;
import org.mozilla.javascript.lc.type.TypeInfoFactory;
import org.mozilla.javascript.xml.XMLLib;

/**
 * This class represents the runtime context of an executing script.
 *
 * <p>Before executing a script, an instance of Context must be created and associated with the
 * thread that will be executing the script. The Context will be used to store information about the
 * executing of the script such as the call stack. Contexts are associated with the current thread
 * using the {@link #call(ContextAction)} or {@link #enter()} methods.
 *
 * <p>Different forms of script execution are supported. Scripts may be evaluated from the source
 * directly, or first compiled and then later executed. Interactive execution is also supported.
 *
 * <p>Some aspects of script execution, such as type conversions and object creation, may be
 * accessed directly through methods of Context.
 *
 * @see Scriptable
 * @author Norris Boyd
 * @author Brendan Eich
 */
public class Context implements Closeable {
    /**
     * Language versions.
     *
     * <p>All integral values are reserved for future version numbers.
     */

    /**
     * The unknown version.
     *
     * <p>Be aware, this version will not support many of the newer language features and will not
     * change in the future. In practice, for historical reasons, this is an odd version that
     * doesn't necessarily match any particular version of JavaScript and should not be used.
     *
     * <p>Please use one of the other constants like {@link #VERSION_ECMASCRIPT} to get support for
     * recent language features.
     */
    public static final int VERSION_UNKNOWN = -1;

    /**
     * The default version for older versions of Rhino.
     *
     * <p>Be aware, this version will not support many of the newer language features and will not
     * change in the future. In practice, for historical reasons, this is an odd version that
     * doesn't necessarily match any particular version of JavaScript and should not be used,
     * although many older scripts and many tests may depend on it.
     *
     * <p>Please use one of the other constants like {@link #VERSION_ECMASCRIPT} to get support for
     * recent language features.
     */
    public static final int VERSION_DEFAULT = 0;

    /** JavaScript 1.0 */
    public static final int VERSION_1_0 = 100;

    /** JavaScript 1.1 */
    public static final int VERSION_1_1 = 110;

    /** JavaScript 1.2 */
    public static final int VERSION_1_2 = 120;

    /** JavaScript 1.3 */
    public static final int VERSION_1_3 = 130;

    /** JavaScript 1.4 */
    public static final int VERSION_1_4 = 140;

    /** JavaScript 1.5 */
    public static final int VERSION_1_5 = 150;

    /** JavaScript 1.6 */
    public static final int VERSION_1_6 = 160;

    /** JavaScript 1.7 */
    public static final int VERSION_1_7 = 170;

    /** JavaScript 1.8 */
    public static final int VERSION_1_8 = 180;

    /**
     * ECMAScript 6 and after. This constant refers to the latest version of the language, and is
     * mostly identical to {@link #VERSION_ECMASCRIPT}. The difference is that features that may
     * cause existing code to break, such as enforcement of "const" and strict mode checks, are not
     * enabled.
     *
     * <p>As of version 1.8, this is the default language version.
     */
    public static final int VERSION_ES6 = 200;

    /**
     * The current version of JavaScript as defined by ECMAScript. This version will always refer to
     * the latest version of the language that Rhino supports. This will include everything in
     * {@link #VERSION_ES6}, plus features that may cause backward incompatibility, such as
     * enforcement of "const" and strict mode checks.
     *
     * <p>As of version 1.8, the Rhino community has no plans to continue adding new language
     * versions, but instead plans to track the ECMAScript specification as it evolves and add new
     * features in new versions of Rhino using this version number.
     */
    public static final int VERSION_ECMASCRIPT = 250;

    /**
     * Controls behaviour of <code>Date.prototype.getYear()</code>. If <code>
     * hasFeature(FEATURE_NON_ECMA_GET_YEAR)</code> returns true, Date.prototype.getYear subtructs
     * 1900 only if 1900 &lt;= date &lt; 2000. The default behavior of {@link #hasFeature(int)} is
     * always to subtract 1900 as required by ECMAScript B.2.4.
     */
    public static final int FEATURE_NON_ECMA_GET_YEAR = 1;

    /**
     * Control if member expression as function name extension is available. If <code>
     * hasFeature(FEATURE_MEMBER_EXPR_AS_FUNCTION_NAME)</code> returns true, allow <code>
     * function memberExpression(args) { body }</code> to be syntax sugar for <code>
     * memberExpression = function(args) { body }</code>, when memberExpression is not a simple
     * identifier. See ECMAScript-262, section 11.2 for definition of memberExpression. By default
     * {@link #hasFeature(int)} returns false.
     */
    public static final int FEATURE_MEMBER_EXPR_AS_FUNCTION_NAME = 2;

    /**
     * Control if reserved keywords are treated as identifiers. If <code>
     * hasFeature(RESERVED_KEYWORD_AS_IDENTIFIER)</code> returns true, treat future reserved keyword
     * (see Ecma-262, section 7.5.3) as ordinary identifiers but warn about this usage.
     *
     * <p>By default {@link #hasFeature(int)} returns false.
     */
    public static final int FEATURE_RESERVED_KEYWORD_AS_IDENTIFIER = 3;

    /**
     * Control if <code>toString()</code> should returns the same result as <code>toSource()</code>
     * when applied to objects and arrays. If <code>hasFeature(FEATURE_TO_STRING_AS_SOURCE)</code>
     * returns true, calling <code>toString()</code> on JS objects gives the same result as calling
     * <code>toSource()</code>. That is it returns JS source with code to create an object with all
     * enumerable fields of the original object instead of printing <code>[object <i>result of
     * {@link Scriptable#getClassName()}</i>]</code>.
     *
     * <p>By default {@link #hasFeature(int)} returns true only if the current JS version is set to
     * {@link #VERSION_1_2}.
     */
    public static final int FEATURE_TO_STRING_AS_SOURCE = 4;

    /**
     * Control if properties <code>__proto__</code> and <code>__parent__</code> are treated
     * specially. If <code>hasFeature(FEATURE_PARENT_PROTO_PROPERTIES)</code> returns true, treat
     * <code>__parent__</code> and <code>__proto__</code> as special properties.
     *
     * <p>The properties allow to query and set scope and prototype chains for the objects. The
     * special meaning of the properties is available only when they are used as the right hand side
     * of the dot operator. For example, while <code>x.__proto__ = y</code> changes the prototype
     * chain of the object <code>x</code> to point to <code>y</code>, <code>x["__proto__"] = y
     * </code> simply assigns a new value to the property <code>__proto__</code> in <code>x</code>
     * even when the feature is on.
     *
     * <p>By default {@link #hasFeature(int)} returns true.
     */
    public static final int FEATURE_PARENT_PROTO_PROPERTIES = 5;

    /**
     * @deprecated In previous releases, this name was given to FEATURE_PARENT_PROTO_PROPERTIES.
     */
    @Deprecated public static final int FEATURE_PARENT_PROTO_PROPRTIES = 5;

    /**
     * Control if support for E4X(ECMAScript for XML) extension is available. If
     * hasFeature(FEATURE_E4X) returns true, the XML syntax is available.
     *
     * <p>By default {@link #hasFeature(int)} returns true if the current JS version is set to
     * {@link #VERSION_DEFAULT} or is at least {@link #VERSION_1_6}.
     *
     * @since 1.6 Release 1
     */
    public static final int FEATURE_E4X = 6;

    /**
     * Control if dynamic scope should be used for name access. If hasFeature(FEATURE_DYNAMIC_SCOPE)
     * returns true, then the name lookup during name resolution will use the top scope of the
     * script or function which is at the top of JS execution stack instead of the top scope of the
     * script or function from the current stack frame if the top scope of the top stack frame
     * contains the top scope of the current stack frame on its prototype chain.
     *
     * <p>This is useful to define shared scope containing functions that can be called from scripts
     * and functions using private scopes.
     *
     * <p>By default {@link #hasFeature(int)} returns false.
     *
     * @since 1.6 Release 1
     */
    public static final int FEATURE_DYNAMIC_SCOPE = 7;

    /**
     * Control if strict variable mode is enabled. When the feature is on Rhino reports runtime
     * errors if assignment to a global variable that does not exist is executed. When the feature
     * is off such assignments create a new variable in the global scope as required by ECMA 262.
     *
     * <p>By default {@link #hasFeature(int)} returns false.
     *
     * @since 1.6 Release 1
     */
    public static final int FEATURE_STRICT_VARS = 8;

    /**
     * Control if strict eval mode is enabled. When the feature is on Rhino reports runtime errors
     * if non-string argument is passed to the eval function. When the feature is off eval simply
     * return non-string argument as is without performing any evaluation as required by ECMA 262.
     *
     * <p>By default {@link #hasFeature(int)} returns false.
     *
     * @since 1.6 Release 1
     */
    public static final int FEATURE_STRICT_EVAL = 9;

    /**
     * When the feature is on Rhino will add a "fileName" and "lineNumber" properties to Error
     * objects automatically. When the feature is off, you have to explicitly pass them as the
     * second and third argument to the Error constructor. Note that neither behavior is fully ECMA
     * 262 compliant (as 262 doesn't specify a three-arg constructor), but keeping the feature off
     * results in Error objects that don't have additional non-ECMA properties when constructed
     * using the ECMA-defined single-arg constructor and is thus desirable if a stricter ECMA
     * compliance is desired, specifically adherence to the point 15.11.5. of the standard.
     *
     * <p>By default {@link #hasFeature(int)} returns false.
     *
     * @since 1.6 Release 6
     */
    public static final int FEATURE_LOCATION_INFORMATION_IN_ERROR = 10;

    /**
     * Controls whether JS 1.5 'strict mode' is enabled. When the feature is on, Rhino reports more
     * than a dozen different warnings. When the feature is off, these warnings are not generated.
     * FEATURE_STRICT_MODE implies FEATURE_STRICT_VARS and FEATURE_STRICT_EVAL.
     *
     * <p>By default {@link #hasFeature(int)} returns false.
     *
     * @since 1.6 Release 6
     */
    public static final int FEATURE_STRICT_MODE = 11;

    /**
     * Controls whether a warning should be treated as an error.
     *
     * @since 1.6 Release 6
     */
    public static final int FEATURE_WARNING_AS_ERROR = 12;

    /**
     * Enables enhanced access to Java. Specifically, controls whether private and protected members
     * can be accessed, and whether scripts can catch all Java exceptions.
     *
     * <p>Note that this feature should only be enabled for trusted scripts.
     *
     * <p>By default {@link #hasFeature(int)} returns false.
     *
     * @since 1.7 Release 1
     */
    public static final int FEATURE_ENHANCED_JAVA_ACCESS = 13;

    /**
     * Enables access to JavaScript features from ECMAscript 6 that are present in JavaScript
     * engines that do not yet support version 6, such as V8. This includes support for typed
     * arrays. Default is true.
     *
     * @since 1.7 Release 3
     */
    public static final int FEATURE_V8_EXTENSIONS = 14;

    /**
     * Defines how an undefined "this" parameter is handled in certain calls. Previously Rhino would
     * convert an undefined "this" to null, whereas recent specs call for it to be treated
     * differently. Default is to be set if language version &lt;= 1.7.
     *
     * @since 1.7.7
     */
    public static final int FEATURE_OLD_UNDEF_NULL_THIS = 15;

    /**
     * If set, then the order of property key enumeration will be first numeric keys in numeric
     * order, followed by string keys in order of creation, and finally Symbol keys, as specified in
     * ES6. Default is true for language version &gt;= "ES6" and false otherwise.
     *
     * @since 1.7.7.1
     */
    public static final int FEATURE_ENUMERATE_IDS_FIRST = 16;

    /**
     * If set, then all objects will have a thread-safe property map. (Note that this doesn't make
     * everything else that they do thread-safe -- that depends on the specific implementation. If
     * not set, users should not share Rhino objects between threads, unless the "sync" function is
     * used to wrap them with an explicit synchronizer. The default is false, which means that by
     * default, individual objects are not thread-safe.
     *
     * @since 1.7.8
     */
    public static final int FEATURE_THREAD_SAFE_OBJECTS = 17;

    /**
     * If set, then all integer numbers will be returned without decimal place. For instance assume
     * there is a function like this: <code>function foo() {return 5;}</code> 5 will be returned if
     * feature is set, 5.0 otherwise.
     */
    public static final int FEATURE_INTEGER_WITHOUT_DECIMAL_PLACE = 18;

    /**
     * TypedArray buffer uses little/big endian depending on the platform. The default is big endian
     * for Rhino.
     *
     * @since 1.7 Release 11
     */
    public static final int FEATURE_LITTLE_ENDIAN = 19;

    /**
     * Configure the XMLProcessor to parse XML with security features or not. Security features
     * include not fetching remote entity references and disabling XIncludes
     *
     * @since 1.7 Release 12
     */
    public static final int FEATURE_ENABLE_XML_SECURE_PARSING = 20;

    /**
     * Configure whether the entries in a Java Map can be accessed by properties.
     *
     * <p>Not enabled:
     *
     * <p>var map = new java.util.HashMap(); map.put('foo', 1); map.foo; // undefined
     *
     * <p>Enabled:
     *
     * <p>var map = new java.util.HashMap(); map.put('foo', 1); map.foo; // 1
     *
     * <p>WARNING: This feature is similar to the one in Nashorn, but incomplete.
     *
     * <p>1. A entry has priority over method.
     *
     * <p>map.put("put", "abc"); map.put; // abc map.put("put", "efg"); // ERROR
     *
     * <p>2. The distinction between numeric keys and string keys is ambiguous.
     *
     * <p>map.put('1', 123); map['1']; // Not found. This means `map[1]`.
     *
     * @since 1.7 Release 14
     */
    public static final int FEATURE_ENABLE_JAVA_MAP_ACCESS = 21;

    /**
     * Internationalization API implementation (see <a
     * href="https://tc39.github.io/ecma402">ECMA-402</a>) can be activated using this feature.
     *
     * @since 1.7 Release 15
     */
    public static final int FEATURE_INTL_402 = 22;

    public static final String languageVersionProperty = "language version";
    public static final String errorReporterProperty = "error reporter";

    private static final RegExpLoader regExpLoader =
            ScriptRuntime.loadOneServiceImplementation(RegExpLoader.class);

    /**
     * Convenient value to use as zero-length array of objects.
     *
     * @deprecated As of 1.8.1, use {@link ScriptRuntime#emptyArgs} instead.
     */
    @Deprecated public static final Object[] emptyArgs = ScriptRuntime.emptyArgs;

    /**
     * Stores the current context per thread.
     *
     * <p>Note: former methods (VMBridge) have used an Object[] for performance reasons. This seems
     * to be outdated. ThreadLocal.get/set gives better performance. (But do not use
     * ThreadLocal.remove.) See ContextThreadLocalBenchmark
     */
    private static final ThreadLocal<Context> currentContext = new ThreadLocal<>();

    /**
     * Creates a new Context. The context will be associated with the {@link
     * ContextFactory#getGlobal() global context factory}. By default, the new context will run in
     * compiled mode and use the {@link #VERSION_ES6} language version, which supports features as
     * defined in the most recent ECMAScript standard. This default behavior can be changed by
     * overriding the ContextFactory class and installing the new implementation as the global
     * ContextFactory.
     *
     * <p>Note that the Context must be associated with a thread before it can be used to execute a
     * script.
     *
     * @deprecated this constructor is deprecated because it creates a dependency on a static
     *     singleton context factory. Use {@link ContextFactory#enter()} or {@link
     *     ContextFactory#call(ContextAction)} instead. If you subclass this class, consider using
     *     {@link #Context(ContextFactory)} constructor instead in the subclasses' constructors.
     */
    @Deprecated
    public Context() {
        this(ContextFactory.getGlobal());
    }

    /**
     * Creates a new context. Provided as a preferred super constructor for subclasses in place of
     * the deprecated default public constructor. By default, the new context will run in compiled
     * mode and use the {@link #VERSION_ES6} language version, which supports features as defined in
     * the most recent ECMAScript standard. This default behavior can be changed by overriding the
     * ContextFactory class
     *
     * @param factory the context factory associated with this context (most likely, the one that
     *     created the context). Can not be null. The context features are inherited from the
     *     factory, and the context will also otherwise use its factory's services.
     * @throws IllegalArgumentException if factory parameter is null.
     */
    protected Context(ContextFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("factory == null");
        }
        this.factory = factory;
        version = VERSION_ES6;
        interpretedMode = codegenClass == null;
        maximumInterpreterStackDepth = Integer.MAX_VALUE;
    }

    /**
     * Get the current Context.
     *
     * <p>The current Context is per-thread; this method looks up the Context associated with the
     * current thread.
     *
     * @return the Context associated with the current thread, or null if no context is associated
     *     with the current thread.
     * @see ContextFactory#enterContext()
     * @see ContextFactory#call(ContextAction)
     */
    public static Context getCurrentContext() {
        return currentContext.get();
    }

    /**
     * Same as calling {@link ContextFactory#enterContext()} on the global ContextFactory instance.
     *
     * @return a Context associated with the current thread
     * @see #getCurrentContext()
     * @see #exit()
     * @see #call(ContextAction)
     */
    public static Context enter() {
        return enter(null, ContextFactory.getGlobal());
    }

    /**
     * Get a Context associated with the current thread, using the given Context if need be.
     *
     * <p>The same as <code>enter()</code> except that <code>cx</code> is associated with the
     * current thread and returned if the current thread has no associated context and <code>cx
     * </code> is not associated with any other thread.
     *
     * @param cx a Context to associate with the thread if possible
     * @return a Context associated with the current thread
     * @deprecated use {@link ContextFactory#enterContext(Context)} instead as this method relies on
     *     usage of a static singleton "global" ContextFactory.
     * @see ContextFactory#enterContext(Context)
     * @see ContextFactory#call(ContextAction)
     */
    @Deprecated
    public static Context enter(Context cx) {
        return enter(cx, ContextFactory.getGlobal());
    }

    static final Context enter(Context cx, ContextFactory factory) {
        Context old = currentContext.get();
        if (old != null) {
            cx = old;
        } else {
            if (cx == null) {
                cx = factory.makeContext();
                if (cx.enterCount != 0) {
                    throw new IllegalStateException(
                            "factory.makeContext() returned Context instance already associated with some thread");
                }
                factory.onContextCreated(cx);
                if (factory.isSealed() && !cx.isSealed()) {
                    cx.seal(null);
                }
            } else {
                if (cx.enterCount != 0) {
                    throw new IllegalStateException(
                            "can not use Context instance already associated with some thread");
                }
            }
            currentContext.set(cx);
        }
        ++cx.enterCount;
        return cx;
    }

    /**
     * Exit a block of code requiring a Context.
     *
     * <p>Calling <code>exit()</code> will remove the association between the current thread and a
     * Context if the prior call to {@link ContextFactory#enterContext()} on this thread newly
     * associated a Context with this thread. Once the current thread no longer has an associated
     * Context, it cannot be used to execute JavaScript until it is again associated with a Context.
     *
     * @see ContextFactory#enterContext()
     */
    public static void exit() {
        Context cx = currentContext.get();
        if (cx == null) {
            throw new IllegalStateException("Calling Context.exit without previous Context.enter");
        }
        if (cx.enterCount < 1) Kit.codeBug();
        if (--cx.enterCount == 0) {
            releaseContext(cx);
        }
    }

    @Override
    public void close() {
        if (enterCount < 1) Kit.codeBug();
        if (--enterCount == 0) {
            assert (currentContext.get() == this)
                    : "currentContext: " + currentContext.get() + ", this: " + this;
            releaseContext(this);
        }
    }

    private static void releaseContext(Context cx) {
        // do not use contextLocal.remove() here, as this might be much slower, when the same thread
        // creates a new context. See ContextThreadLocalBenchmark.
        currentContext.set(null);
        cx.factory.onContextReleased(cx);
    }

    /**
     * Call {@link ContextAction#run(Context cx)} using the Context instance associated with the
     * current thread. If no Context is associated with the thread, then <code>
     * ContextFactory.getGlobal().makeContext()</code> will be called to construct new Context
     * instance. The instance will be temporary associated with the thread during call to {@link
     * ContextAction#run(Context)}.
     *
     * @deprecated use {@link ContextFactory#call(ContextAction)} instead as this method relies on
     *     usage of a static singleton "global" ContextFactory.
     * @return The result of {@link ContextAction#run(Context)}.
     */
    @Deprecated
    public static <T> T call(ContextAction<T> action) {
        return call(ContextFactory.getGlobal(), action);
    }

    /**
     * Call {@link Callable#call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args)}
     * using the Context instance associated with the current thread. If no Context is associated
     * with the thread, then {@link ContextFactory#makeContext()} will be called to construct new
     * Context instance. The instance will be temporary associated with the thread during call to
     * {@link ContextAction#run(Context)}.
     *
     * <p>It is allowed but not advisable to use null for <code>factory</code> argument in which
     * case the global static singleton ContextFactory instance will be used to create new context
     * instances.
     *
     * @see ContextFactory#call(ContextAction)
     */
    public static Object call(
            ContextFactory factory,
            final Callable callable,
            final Scriptable scope,
            final Scriptable thisObj,
            final Object[] args) {
        if (factory == null) {
            factory = ContextFactory.getGlobal();
        }
        return call(factory, cx -> callable.call(cx, scope, thisObj, args));
    }

    /** The method implements {@link ContextFactory#call(ContextAction)} logic. */
    static <T> T call(ContextFactory factory, ContextAction<T> action) {
        try (Context cx = enter(null, factory)) {
            return action.run(cx);
        }
    }

    /**
     * @deprecated
     * @see ContextFactory#addListener(org.mozilla.javascript.ContextFactory.Listener)
     * @see ContextFactory#getGlobal()
     */
    @Deprecated
    public static void addContextListener(ContextListener listener) {
        // Special workaround for the debugger
        String DBG = "org.mozilla.javascript.tools.debugger.Main";
        if (DBG.equals(listener.getClass().getName())) {
            Class<?> cl = listener.getClass();
            Class<?> factoryClass = Kit.classOrNull("org.mozilla.javascript.ContextFactory");
            Class<?>[] sig = {factoryClass};
            Object[] args = {ContextFactory.getGlobal()};
            try {
                Method m = cl.getMethod("attachTo", sig);
                m.invoke(listener, args);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            return;
        }

        ContextFactory.getGlobal().addListener(listener);
    }

    /**
     * @deprecated
     * @see ContextFactory#removeListener(org.mozilla.javascript.ContextFactory.Listener)
     * @see ContextFactory#getGlobal()
     */
    @Deprecated
    public static void removeContextListener(ContextListener listener) {
        ContextFactory.getGlobal().addListener(listener);
    }

    /** Return {@link ContextFactory} instance used to create this Context. */
    public final ContextFactory getFactory() {
        return factory;
    }

    /**
     * Checks if this is a sealed Context. A sealed Context instance does not allow to modify any of
     * its properties and will throw an exception on any such attempt.
     *
     * @see #seal(Object sealKey)
     */
    public final boolean isSealed() {
        return sealed;
    }

    /**
     * Seal this Context object so any attempt to modify any of its properties including calling
     * {@link #enter()} and {@link #exit()} methods will throw an exception.
     *
     * <p>If <code>sealKey</code> is not null, calling {@link #unseal(Object sealKey)} with the same
     * key unseals the object. If <code>sealKey</code> is null, unsealing is no longer possible.
     *
     * @see #isSealed()
     * @see #unseal(Object)
     */
    public final void seal(Object sealKey) {
        if (sealed) onSealedMutation();
        sealed = true;
        this.sealKey = sealKey;
    }

    /**
     * Unseal previously sealed Context object. The <code>sealKey</code> argument should not be null
     * and should match <code>sealKey</code> supplied with the last call to {@link #seal(Object)} or
     * an exception will be thrown.
     *
     * @see #isSealed()
     * @see #seal(Object sealKey)
     */
    public final void unseal(Object sealKey) {
        if (sealKey == null) throw new IllegalArgumentException();
        if (this.sealKey != sealKey) throw new IllegalArgumentException();
        if (!sealed) throw new IllegalStateException();
        sealed = false;
        this.sealKey = null;
    }

    @SuppressWarnings("DoNotCallSuggester")
    static void onSealedMutation() {
        throw new IllegalStateException();
    }

    /**
     * Get the current language version.
     *
     * <p>The language version number affects JavaScript semantics as detailed in the overview
     * documentation.
     *
     * @return an integer that is one of VERSION_1_0, VERSION_1_1, etc.
     */
    public final int getLanguageVersion() {
        return version;
    }

    /**
     * Set the language version.
     *
     * <p>Setting the language version will affect functions and scripts compiled subsequently. See
     * the overview documentation for version-specific behavior. The default version is {@link
     * #VERSION_ES6}, which represents the newest ECMAScript features implemented by Rhino, minus
     * some "const" and strict mode checks.
     *
     * <p>New projects should use either {@link #VERSION_ES6} or {@link #VERSION_ECMASCRIPT} unless
     * a project needs backwards compatibility with older Rhino scripts that may depend on behaviors
     * from the earlier history of JavaScript.
     *
     * <p>As of version 1.8, the Rhino community has no plans to continue to add new language
     * versions, but instead plans to track the ECMAScript standard and add new features as the
     * language evolves in new versions of Rhino, like other JavaScript engines. Projects that use
     * Rhino are encouraged to migrate to the {@link #VERSION_ECMASCRIPT} version and stop relying
     * on older behaviors of Rhino that are no longer compatible with ECMAScript.
     *
     * @param version the version as specified by VERSION_1_0, VERSION_1_1, etc.
     */
    public void setLanguageVersion(int version) {
        if (sealed) onSealedMutation();
        checkLanguageVersion(version);
        Object listeners = propertyListeners;
        if (listeners != null && version != this.version) {
            firePropertyChangeImpl(
                    listeners,
                    languageVersionProperty,
                    Integer.valueOf(this.version),
                    Integer.valueOf(version));
        }
        this.version = version;
    }

    public static boolean isValidLanguageVersion(int version) {
        switch (version) {
            case VERSION_DEFAULT:
            case VERSION_1_0:
            case VERSION_1_1:
            case VERSION_1_2:
            case VERSION_1_3:
            case VERSION_1_4:
            case VERSION_1_5:
            case VERSION_1_6:
            case VERSION_1_7:
            case VERSION_1_8:
            case VERSION_ES6:
            case VERSION_ECMASCRIPT:
                return true;
        }
        return false;
    }

    public static void checkLanguageVersion(int version) {
        if (isValidLanguageVersion(version)) {
            return;
        }
        throw new IllegalArgumentException("Bad language version: " + version);
    }

    /**
     * Get the implementation version.
     *
     * <p>The implementation version is of the form
     *
     * <pre>
     *    "<i>name langVer</i> <code>release</code> <i>relNum date</i>"
     * </pre>
     *
     * where <i>name</i> is the name of the product, <i>langVer</i> is the language version,
     * <i>relNum</i> is the release number, and <i>date</i> is the release date for that specific
     * release in the form "yyyy mm dd".
     *
     * @return a string that encodes the product, language version, release number, and date.
     */
    public final String getImplementationVersion() {
        return ImplementationVersion.get();
    }

    /**
     * Get the current error reporter.
     *
     * @see org.mozilla.javascript.ErrorReporter
     */
    public final ErrorReporter getErrorReporter() {
        if (errorReporter == null) {
            return DefaultErrorReporter.instance;
        }
        return errorReporter;
    }

    /**
     * Change the current error reporter.
     *
     * @return the previous error reporter
     * @see org.mozilla.javascript.ErrorReporter
     */
    public final ErrorReporter setErrorReporter(ErrorReporter reporter) {
        if (sealed) onSealedMutation();
        if (reporter == null) throw new IllegalArgumentException();
        ErrorReporter old = getErrorReporter();
        if (reporter == old) {
            return old;
        }
        Object listeners = propertyListeners;
        if (listeners != null) {
            firePropertyChangeImpl(
                    listeners, errorReporterProperty,
                    old, reporter);
        }
        this.errorReporter = reporter;
        return old;
    }

    /**
     * Get the current locale. Returns the default locale if none has been set.
     *
     * @see java.util.Locale
     */
    public final Locale getLocale() {
        if (locale == null) locale = Locale.getDefault();
        return locale;
    }

    /**
     * Set the current locale.
     *
     * @return the old value of the locale
     * @see java.util.Locale
     */
    public final Locale setLocale(Locale loc) {
        if (sealed) onSealedMutation();
        Locale result = locale;
        locale = loc;
        return result;
    }

    /**
     * Get the current timezone. Returns the default timezone if none has been set.
     *
     * @return the old value of the timezone
     * @see java.util.TimeZone
     */
    public final TimeZone getTimeZone() {
        if (timezone == null) timezone = TimeZone.getDefault();
        return timezone;
    }

    /**
     * Set the current timezone.
     *
     * @see java.util.TimeZone
     */
    public final TimeZone setTimeZone(TimeZone tz) {
        if (sealed) onSealedMutation();
        TimeZone result = timezone;
        timezone = tz;
        return result;
    }

    /**
     * Register an object to receive notifications when a bound property has changed
     *
     * @see java.beans.PropertyChangeEvent
     * @see #removePropertyChangeListener(java.beans.PropertyChangeListener)
     * @param l the listener
     */
    public final void addPropertyChangeListener(PropertyChangeListener l) {
        if (sealed) onSealedMutation();
        propertyListeners = Kit.addListener(propertyListeners, l);
    }

    /**
     * Remove an object from the list of objects registered to receive notification of changes to a
     * bounded property
     *
     * @see java.beans.PropertyChangeEvent
     * @see #addPropertyChangeListener(java.beans.PropertyChangeListener)
     * @param l the listener
     */
    public final void removePropertyChangeListener(PropertyChangeListener l) {
        if (sealed) onSealedMutation();
        propertyListeners = Kit.removeListener(propertyListeners, l);
    }

    /**
     * Notify any registered listeners that a bounded property has changed
     *
     * @see #addPropertyChangeListener(java.beans.PropertyChangeListener)
     * @see #removePropertyChangeListener(java.beans.PropertyChangeListener)
     * @see java.beans.PropertyChangeListener
     * @see java.beans.PropertyChangeEvent
     * @param property the bound property
     * @param oldValue the old value
     * @param newValue the new value
     */
    final void firePropertyChange(String property, Object oldValue, Object newValue) {
        Object listeners = propertyListeners;
        if (listeners != null) {
            firePropertyChangeImpl(listeners, property, oldValue, newValue);
        }
    }

    private void firePropertyChangeImpl(
            Object listeners, String property, Object oldValue, Object newValue) {
        for (int i = 0; ; ++i) {
            Object l = Kit.getListener(listeners, i);
            if (l == null) break;
            if (l instanceof PropertyChangeListener) {
                PropertyChangeListener pcl = (PropertyChangeListener) l;
                pcl.propertyChange(new PropertyChangeEvent(this, property, oldValue, newValue));
            }
        }
    }

    /**
     * Report a warning using the error reporter for the current thread.
     *
     * @param message the warning message to report
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number
     * @param lineSource the text of the line (may be null)
     * @param lineOffset the offset into lineSource where problem was detected
     * @see org.mozilla.javascript.ErrorReporter
     */
    public static void reportWarning(
            String message, String sourceName, int lineno, String lineSource, int lineOffset) {
        Context cx = Context.getContext();
        if (cx.hasFeature(FEATURE_WARNING_AS_ERROR))
            reportError(message, sourceName, lineno, lineSource, lineOffset);
        else cx.getErrorReporter().warning(message, sourceName, lineno, lineSource, lineOffset);
    }

    /**
     * Report a warning using the error reporter for the current thread.
     *
     * @param message the warning message to report
     * @see org.mozilla.javascript.ErrorReporter
     */
    public static void reportWarning(String message) {
        int[] linep = {0};
        String filename = getSourcePositionFromStack(linep);
        Context.reportWarning(message, filename, linep[0], null, 0);
    }

    public static void reportWarning(String message, Throwable t) {
        int[] linep = {0};
        String filename = getSourcePositionFromStack(linep);
        Writer sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println(message);
        t.printStackTrace(pw);
        pw.flush();
        Context.reportWarning(sw.toString(), filename, linep[0], null, 0);
    }

    /**
     * Report an error using the error reporter for the current thread.
     *
     * @param message the error message to report
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number
     * @param lineSource the text of the line (may be null)
     * @param lineOffset the offset into lineSource where problem was detected
     * @see org.mozilla.javascript.ErrorReporter
     */
    public static void reportError(
            String message, String sourceName, int lineno, String lineSource, int lineOffset) {
        Context cx = getCurrentContext();
        if (cx != null) {
            cx.getErrorReporter().error(message, sourceName, lineno, lineSource, lineOffset);
        } else {
            throw new EvaluatorException(message, sourceName, lineno, lineSource, lineOffset);
        }
    }

    /**
     * Report an error using the error reporter for the current thread.
     *
     * @param message the error message to report
     * @see org.mozilla.javascript.ErrorReporter
     */
    public static void reportError(String message) {
        int[] linep = {0};
        String filename = getSourcePositionFromStack(linep);
        Context.reportError(message, filename, linep[0], null, 0);
    }

    /**
     * Report a runtime error using the error reporter for the current thread.
     *
     * @param message the error message to report
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number
     * @param lineSource the text of the line (may be null)
     * @param lineOffset the offset into lineSource where problem was detected
     * @return a runtime exception that will be thrown to terminate the execution of the script
     * @see org.mozilla.javascript.ErrorReporter
     */
    public static EvaluatorException reportRuntimeError(
            String message, String sourceName, int lineno, String lineSource, int lineOffset) {
        Context cx = getCurrentContext();
        if (cx != null) {
            return cx.getErrorReporter()
                    .runtimeError(message, sourceName, lineno, lineSource, lineOffset);
        }
        throw new EvaluatorException(message, sourceName, lineno, lineSource, lineOffset);
    }

    static EvaluatorException reportRuntimeErrorById(String messageId, Object... args) {
        String msg = ScriptRuntime.getMessageById(messageId, args);
        return reportRuntimeError(msg);
    }

    /**
     * @deprecated Use {@link #reportRuntimeErrorById(String messageId, Object... args)} instead
     */
    @Deprecated
    static EvaluatorException reportRuntimeError0(String messageId) {
        String msg = ScriptRuntime.getMessageById(messageId);
        return reportRuntimeError(msg);
    }

    /**
     * @deprecated Use {@link #reportRuntimeErrorById(String messageId, Object... args)} instead
     */
    @Deprecated
    static EvaluatorException reportRuntimeError1(String messageId, Object arg1) {
        String msg = ScriptRuntime.getMessageById(messageId, arg1);
        return reportRuntimeError(msg);
    }

    /**
     * @deprecated Use {@link #reportRuntimeErrorById(String messageId, Object... args)} instead
     */
    @Deprecated
    static EvaluatorException reportRuntimeError2(String messageId, Object arg1, Object arg2) {
        String msg = ScriptRuntime.getMessageById(messageId, arg1, arg2);
        return reportRuntimeError(msg);
    }

    /**
     * @deprecated Use {@link #reportRuntimeErrorById(String messageId, Object... args)} instead
     */
    @Deprecated
    static EvaluatorException reportRuntimeError3(
            String messageId, Object arg1, Object arg2, Object arg3) {
        String msg = ScriptRuntime.getMessageById(messageId, arg1, arg2, arg3);
        return reportRuntimeError(msg);
    }

    /**
     * @deprecated Use {@link #reportRuntimeErrorById(String messageId, Object... args)} instead
     */
    @Deprecated
    static EvaluatorException reportRuntimeError4(
            String messageId, Object arg1, Object arg2, Object arg3, Object arg4) {
        String msg = ScriptRuntime.getMessageById(messageId, arg1, arg2, arg3, arg4);
        return reportRuntimeError(msg);
    }

    /**
     * Report a runtime error using the error reporter for the current thread.
     *
     * @param message the error message to report
     * @see org.mozilla.javascript.ErrorReporter
     */
    public static EvaluatorException reportRuntimeError(String message) {
        int[] linep = {0};
        String filename = getSourcePositionFromStack(linep);
        return Context.reportRuntimeError(message, filename, linep[0], null, 0);
    }

    /**
     * Initialize the standard objects.
     *
     * <p>Creates instances of the standard objects and their constructors (Object, String, Number,
     * Date, etc.), setting up 'scope' to act as a global object as in ECMA 15.1.
     *
     * <p>This method must be called to initialize a scope before scripts can be evaluated in that
     * scope.
     *
     * <p>This method does not affect the Context it is called upon.
     *
     * @return the initialized scope
     */
    public final ScriptableObject initStandardObjects() {
        return initStandardObjects(null, false);
    }

    /**
     * Initialize the standard objects, leaving out those that offer access directly to Java
     * classes. This sets up "scope" to have access to all the standard JavaScript classes, but does
     * not create global objects for any top-level Java packages. In addition, the "Packages,"
     * "JavaAdapter," and "JavaImporter" classes, and the "getClass" function, are not initialized.
     *
     * <p>The result of this function is a scope that may be safely used in a "sandbox" environment
     * where it is not desirable to give access to Java code from JavaScript.
     *
     * <p>Creates instances of the standard objects and their constructors (Object, String, Number,
     * Date, etc.), setting up 'scope' to act as a global object as in ECMA 15.1.
     *
     * <p>This method must be called to initialize a scope before scripts can be evaluated in that
     * scope.
     *
     * <p>This method does not affect the Context it is called upon.
     *
     * @return the initialized scope
     */
    public final ScriptableObject initSafeStandardObjects() {
        return initSafeStandardObjects(null, false);
    }

    /**
     * Initialize the standard objects.
     *
     * <p>Creates instances of the standard objects and their constructors (Object, String, Number,
     * Date, etc.), setting up 'scope' to act as a global object as in ECMA 15.1.
     *
     * <p>This method must be called to initialize a scope before scripts can be evaluated in that
     * scope.
     *
     * <p>This method does not affect the Context it is called upon.
     *
     * @param scope the scope to initialize, or null, in which case a new object will be created to
     *     serve as the scope
     * @return the initialized scope. The method returns the value of the scope argument if it is
     *     not null or newly allocated scope object which is an instance {@link ScriptableObject}.
     */
    public final Scriptable initStandardObjects(ScriptableObject scope) {
        return initStandardObjects(scope, false);
    }

    /**
     * Initialize the standard objects, leaving out those that offer access directly to Java
     * classes. This sets up "scope" to have access to all the standard JavaScript classes, but does
     * not create global objects for any top-level Java packages. In addition, the "Packages,"
     * "JavaAdapter," and "JavaImporter" classes, and the "getClass" function, are not initialized.
     *
     * <p>The result of this function is a scope that may be safely used in a "sandbox" environment
     * where it is not desirable to give access to Java code from JavaScript.
     *
     * <p>Creates instances of the standard objects and their constructors (Object, String, Number,
     * Date, etc.), setting up 'scope' to act as a global object as in ECMA 15.1.
     *
     * <p>This method must be called to initialize a scope before scripts can be evaluated in that
     * scope.
     *
     * <p>This method does not affect the Context it is called upon.
     *
     * @param scope the scope to initialize, or null, in which case a new object will be created to
     *     serve as the scope
     * @return the initialized scope. The method returns the value of the scope argument if it is
     *     not null or newly allocated scope object which is an instance {@link ScriptableObject}.
     */
    public final Scriptable initSafeStandardObjects(ScriptableObject scope) {
        return initSafeStandardObjects(scope, false);
    }

    /**
     * Initialize the standard objects.
     *
     * <p>Creates instances of the standard objects and their constructors (Object, String, Number,
     * Date, etc.), setting up 'scope' to act as a global object as in ECMA 15.1.
     *
     * <p>This method must be called to initialize a scope before scripts can be evaluated in that
     * scope.
     *
     * <p>This method does not affect the Context it is called upon.
     *
     * <p>This form of the method also allows for creating "sealed" standard objects. An object that
     * is sealed cannot have properties added, changed, or removed. This is useful to create a
     * "superglobal" that can be shared among several top-level objects. Note that sealing is not
     * allowed in the current ECMA/ISO language specification, but is likely for the next version.
     *
     * @param scope the scope to initialize, or null, in which case a new object will be created to
     *     serve as the scope
     * @param sealed whether or not to create sealed standard objects that cannot be modified.
     * @return the initialized scope. The method returns the value of the scope argument if it is
     *     not null or newly allocated scope object.
     * @since 1.4R3
     */
    public ScriptableObject initStandardObjects(ScriptableObject scope, boolean sealed) {
        return ScriptRuntime.initStandardObjects(this, scope, sealed);
    }

    /**
     * Initialize the standard objects, leaving out those that offer access directly to Java
     * classes. This sets up "scope" to have access to all the standard JavaScript classes, but does
     * not create global objects for any top-level Java packages. In addition, the "Packages,"
     * "JavaAdapter," and "JavaImporter" classes, and the "getClass" function, are not initialized.
     *
     * <p>The result of this function is a scope that may be safely used in a "sandbox" environment
     * where it is not desirable to give access to Java code from JavaScript.
     *
     * <p>Creates instances of the standard objects and their constructors (Object, String, Number,
     * Date, etc.), setting up 'scope' to act as a global object as in ECMA 15.1.
     *
     * <p>This method must be called to initialize a scope before scripts can be evaluated in that
     * scope.
     *
     * <p>This method does not affect the Context it is called upon.
     *
     * <p>This form of the method also allows for creating "sealed" standard objects. An object that
     * is sealed cannot have properties added, changed, or removed. This is useful to create a
     * "superglobal" that can be shared among several top-level objects. Note that sealing is not
     * allowed in the current ECMA/ISO language specification, but is likely for the next version.
     *
     * @param scope the scope to initialize, or null, in which case a new object will be created to
     *     serve as the scope
     * @param sealed whether or not to create sealed standard objects that cannot be modified.
     * @return the initialized scope. The method returns the value of the scope argument if it is
     *     not null or newly allocated scope object.
     * @since 1.7.6
     */
    public ScriptableObject initSafeStandardObjects(ScriptableObject scope, boolean sealed) {
        return ScriptRuntime.initSafeStandardObjects(this, scope, sealed);
    }

    /** Get the singleton object that represents the JavaScript Undefined value. */
    public static Object getUndefinedValue() {
        return Undefined.instance;
    }

    /**
     * Evaluate a JavaScript source string.
     *
     * <p>The provided source name and line number are used for error messages and for producing
     * debug information.
     *
     * @param scope the scope to execute in
     * @param source the JavaScript source
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number
     * @param securityDomain an arbitrary object that specifies security information about the
     *     origin or owner of the script. For implementations that don't care about security, this
     *     value may be null.
     * @return the result of evaluating the string
     * @see org.mozilla.javascript.SecurityController
     */
    public final Object evaluateString(
            Scriptable scope, String source, String sourceName, int lineno, Object securityDomain) {
        Script script = compileString(source, sourceName, lineno, securityDomain);
        if (script != null) {
            return script.exec(this, scope, scope);
        }
        return null;
    }

    /**
     * Evaluate a reader as JavaScript source.
     *
     * <p>All characters of the reader are consumed.
     *
     * @param scope the scope to execute in
     * @param in the Reader to get JavaScript source from
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number
     * @param securityDomain an arbitrary object that specifies security information about the
     *     origin or owner of the script. For implementations that don't care about security, this
     *     value may be null.
     * @return the result of evaluating the source
     * @exception IOException if an IOException was generated by the Reader
     */
    public final Object evaluateReader(
            Scriptable scope, Reader in, String sourceName, int lineno, Object securityDomain)
            throws IOException {
        Script script = compileReader(in, sourceName, lineno, securityDomain);
        if (script != null) {
            return script.exec(this, scope, scope);
        }
        return null;
    }

    /**
     * Execute script that may pause execution by capturing a continuation. Caller must be prepared
     * to catch a ContinuationPending exception and resume execution by calling {@link
     * #resumeContinuation(Object, Scriptable, Object)}.
     *
     * @param script The script to execute. Script must have been compiled with interpreted mode
     *     (optimization level -1)
     * @param scope The scope to execute the script against
     * @throws ContinuationPending if the script calls a function that results in a call to {@link
     *     #captureContinuation()}
     * @since 1.7 Release 2
     */
    public Object executeScriptWithContinuations(Script script, Scriptable scope)
            throws ContinuationPending {
        if (!(script instanceof InterpretedFunction)
                || !((InterpretedFunction) script).isScript()) {
            // Can only be applied to scripts
            throw new IllegalArgumentException(
                    "Script argument was not"
                            + " a script or was not created by interpreted mode ");
        }
        return callFunctionWithContinuations(
                (InterpretedFunction) script, scope, ScriptRuntime.emptyArgs);
    }

    /**
     * Call function that may pause execution by capturing a continuation. Caller must be prepared
     * to catch a ContinuationPending exception and resume execution by calling {@link
     * #resumeContinuation(Object, Scriptable, Object)}.
     *
     * @param function The function to call. The function must have been compiled with interpreted
     *     mode (optimization level -1)
     * @param scope The scope to execute the script against
     * @param args The arguments for the function
     * @throws ContinuationPending if the script calls a function that results in a call to {@link
     *     #captureContinuation()}
     * @since 1.7 Release 2
     */
    public Object callFunctionWithContinuations(Callable function, Scriptable scope, Object[] args)
            throws ContinuationPending {
        if (!(function instanceof InterpretedFunction)) {
            // Can only be applied to scripts
            throw new IllegalArgumentException(
                    "Function argument was not" + " created by interpreted mode ");
        }
        if (ScriptRuntime.hasTopCall(this)) {
            throw new IllegalStateException(
                    "Cannot have any pending top "
                            + "calls when executing a script with continuations");
        }
        // Annotate so we can check later to ensure no java code in
        // intervening frames
        isContinuationsTopCall = true;
        return ScriptRuntime.doTopCall(function, this, scope, scope, args, isTopLevelStrict);
    }

    /**
     * Capture a continuation from the current execution. The execution must have been started via a
     * call to {@link #executeScriptWithContinuations(Script, Scriptable)} or {@link
     * #callFunctionWithContinuations(Callable, Scriptable, Object[])}. This implies that the code
     * calling this method must have been called as a function from the JavaScript script. Also,
     * there cannot be any non-JavaScript code between the JavaScript frames (e.g., a call to
     * eval()). The ContinuationPending exception returned must be thrown.
     *
     * @return A ContinuationPending exception that must be thrown
     * @since 1.7 Release 2
     */
    public ContinuationPending captureContinuation() {
        return new ContinuationPending(Interpreter.captureContinuation(this));
    }

    /**
     * Restarts execution of the JavaScript suspended at the call to {@link #captureContinuation()}.
     * Execution of the code will resume with the functionResult as the result of the call that
     * captured the continuation. Execution of the script will either conclude normally and the
     * result returned, another continuation will be captured and thrown, or the script will
     * terminate abnormally and throw an exception.
     *
     * @param continuation The value returned by {@link ContinuationPending#getContinuation()}
     * @param functionResult This value will appear to the code being resumed as the result of the
     *     function that captured the continuation
     * @throws ContinuationPending if another continuation is captured before the code terminates
     * @since 1.7 Release 2
     */
    public Object resumeContinuation(Object continuation, Scriptable scope, Object functionResult)
            throws ContinuationPending {
        Object[] args = {functionResult};
        return Interpreter.restartContinuation(
                (org.mozilla.javascript.NativeContinuation) continuation, this, scope, args);
    }

    /**
     * Check whether a string is ready to be compiled.
     *
     * <p>stringIsCompilableUnit is intended to support interactive compilation of JavaScript. If
     * compiling the string would result in an error that might be fixed by appending more source,
     * this method returns false. In every other case, it returns true.
     *
     * <p>Interactive shells may accumulate source lines, using this method after each new line is
     * appended to check whether the statement being entered is complete.
     *
     * @param source the source buffer to check
     * @return whether the source is ready for compilation
     * @since 1.4 Release 2
     */
    public final boolean stringIsCompilableUnit(String source) {
        boolean errorseen = false;
        CompilerEnvirons compilerEnv = new CompilerEnvirons();
        compilerEnv.initFromContext(this);
        // no source name or source text manager, because we're just
        // going to throw away the result.
        compilerEnv.setGeneratingSource(false);
        Parser p = new Parser(compilerEnv, DefaultErrorReporter.instance);
        try {
            p.parse(source, null, 1);
        } catch (EvaluatorException ee) {
            errorseen = true;
        }
        // Return false only if an error occurred as a result of reading past
        // the end of the file, i.e. if the source could be fixed by
        // appending more source.
        return !(errorseen && p.eof());
    }

    /**
     * @deprecated
     * @see #compileReader(Reader in, String sourceName, int lineno, Object securityDomain)
     */
    @Deprecated
    public final Script compileReader(
            Scriptable scope, Reader in, String sourceName, int lineno, Object securityDomain)
            throws IOException {
        return compileReader(in, sourceName, lineno, securityDomain);
    }

    /**
     * Compiles the source in the given reader.
     *
     * <p>Returns a script that may later be executed. Will consume all the source in the reader.
     *
     * @param in the input reader
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number for reporting errors
     * @param securityDomain an arbitrary object that specifies security information about the
     *     origin or owner of the script. For implementations that don't care about security, this
     *     value may be null.
     * @return a script that may later be executed
     * @exception IOException if an IOException was generated by the Reader
     * @see org.mozilla.javascript.Script
     */
    public final Script compileReader(
            Reader in, String sourceName, int lineno, Object securityDomain) throws IOException {
        return compileReader(in, sourceName, lineno, securityDomain, null);
    }

    public Script compileReader(
            Reader in,
            String sourceName,
            int lineno,
            Object securityDomain,
            Consumer<CompilerEnvirons> compilerEnvironsProcessor)
            throws IOException {
        if (lineno < 0) {
            // For compatibility IllegalArgumentException can not be thrown here
            lineno = 0;
        }

        return (Script)
                compileImpl(
                        null,
                        Kit.readReader(in),
                        sourceName,
                        lineno,
                        securityDomain,
                        false,
                        null,
                        null,
                        compilerEnvironsProcessor);
    }

    /**
     * Compiles the source in the given string.
     *
     * <p>Returns a script that may later be executed.
     *
     * @param source the source string
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number for reporting errors. Use 0 if the line number is
     *     unknown.
     * @param securityDomain an arbitrary object that specifies security information about the
     *     origin or owner of the script. For implementations that don't care about security, this
     *     value may be null.
     * @return a script that may later be executed
     * @see org.mozilla.javascript.Script
     */
    public final Script compileString(
            String source, String sourceName, int lineno, Object securityDomain) {
        if (lineno < 0) {
            // For compatibility IllegalArgumentException can not be thrown here
            lineno = 0;
        }
        return compileString(source, null, null, sourceName, lineno, securityDomain, null);
    }

    final Script compileString(
            String source,
            Evaluator compiler,
            ErrorReporter compilationErrorReporter,
            String sourceName,
            int lineno,
            Object securityDomain,
            Consumer<CompilerEnvirons> compilerEnvironsProcessor) {
        return (Script)
                compileImpl(
                        null,
                        source,
                        sourceName,
                        lineno,
                        securityDomain,
                        false,
                        compiler,
                        compilationErrorReporter,
                        compilerEnvironsProcessor);
    }

    /**
     * Compile a JavaScript function.
     *
     * <p>The function source must be a function definition as defined by ECMA (e.g., "function f(a)
     * { return a; }").
     *
     * @param scope the scope to compile relative to
     * @param source the function definition source
     * @param sourceName a string describing the source, such as a filename
     * @param lineno the starting line number
     * @param securityDomain an arbitrary object that specifies security information about the
     *     origin or owner of the script. For implementations that don't care about security, this
     *     value may be null.
     * @return a Function that may later be called
     * @see org.mozilla.javascript.Function
     */
    public final Function compileFunction(
            Scriptable scope, String source, String sourceName, int lineno, Object securityDomain) {
        return compileFunction(scope, source, null, null, sourceName, lineno, securityDomain);
    }

    final Function compileFunction(
            Scriptable scope,
            String source,
            Evaluator compiler,
            ErrorReporter compilationErrorReporter,
            String sourceName,
            int lineno,
            Object securityDomain) {
        return (Function)
                compileImpl(
                        scope,
                        source,
                        sourceName,
                        lineno,
                        securityDomain,
                        true,
                        compiler,
                        compilationErrorReporter,
                        null);
    }

    /**
     * Decompile the script.
     *
     * <p>The canonical source of the script is returned.
     *
     * @param script the script to decompile
     * @param indent the number of spaces to indent the result
     * @return a string representing the script source
     */
    public final String decompileScript(Script script, int indent) {
        NativeFunction scriptImpl = (NativeFunction) script;
        return scriptImpl.decompile(indent, EnumSet.noneOf(DecompilerFlag.class));
    }

    /**
     * Decompile a JavaScript Function.
     *
     * <p>Decompiles a previously compiled JavaScript function object to canonical source.
     *
     * <p>Returns function body of '[native code]' if no decompilation information is available.
     *
     * @param fun the JavaScript function to decompile
     * @param indent the number of spaces to indent the result
     * @return a string representing the function source
     */
    public final String decompileFunction(Function fun, int indent) {
        if (fun instanceof BaseFunction)
            return ((BaseFunction) fun).decompile(indent, EnumSet.noneOf(DecompilerFlag.class));

        return "function " + fun.getClassName() + "() {\n\t[native code]\n}\n";
    }

    /**
     * Decompile the body of a JavaScript Function.
     *
     * <p>Decompiles the body a previously compiled JavaScript Function object to canonical source,
     * omitting the function header and trailing brace.
     *
     * <p>Returns '[native code]' if no decompilation information is available.
     *
     * @param fun the JavaScript function to decompile
     * @param indent the number of spaces to indent the result
     * @return a string representing the function body source.
     */
    public final String decompileFunctionBody(Function fun, int indent) {
        if (fun instanceof BaseFunction) {
            BaseFunction bf = (BaseFunction) fun;
            return bf.decompile(indent, EnumSet.of(DecompilerFlag.ONLY_BODY));
        }
        // ALERT: not sure what the right response here is.
        return "[native code]\n";
    }

    /**
     * Create a new JavaScript object.
     *
     * <p>Equivalent to evaluating "new Object()".
     *
     * @param scope the scope to search for the constructor and to evaluate against
     * @return the new object
     */
    public Scriptable newObject(Scriptable scope) {
        NativeObject result = new NativeObject();
        ScriptRuntime.setBuiltinProtoAndParent(result, scope, TopLevel.Builtins.Object);
        return result;
    }

    /**
     * Create a new JavaScript object by executing the named constructor.
     *
     * <p>The call <code>newObject(scope, "Foo")</code> is equivalent to evaluating "new Foo()".
     *
     * @param scope the scope to search for the constructor and to evaluate against
     * @param constructorName the name of the constructor to call
     * @return the new object
     */
    public Scriptable newObject(Scriptable scope, String constructorName) {
        return newObject(scope, constructorName, ScriptRuntime.emptyArgs);
    }

    /**
     * Creates a new JavaScript object by executing the named constructor.
     *
     * <p>Searches <code>scope</code> for the named constructor, calls it with the given arguments,
     * and returns the result.
     *
     * <p>The code
     *
     * <pre>
     * Object[] args = { "a", "b" };
     * newObject(scope, "Foo", args)</pre>
     *
     * is equivalent to evaluating "new Foo('a', 'b')", assuming that the Foo constructor has been
     * defined in <code>scope</code>.
     *
     * @param scope The scope to search for the constructor and to evaluate against
     * @param constructorName the name of the constructor to call
     * @param args the array of arguments for the constructor
     * @return the new object
     */
    public Scriptable newObject(Scriptable scope, String constructorName, Object[] args) {
        return ScriptRuntime.newObject(this, scope, constructorName, args);
    }

    /**
     * Create an array with a specified initial length.
     *
     * @param scope the scope to create the object in
     * @param length the initial length (JavaScript arrays may have additional properties added
     *     dynamically).
     * @return the new array object
     */
    public Scriptable newArray(Scriptable scope, int length) {
        NativeArray result = new NativeArray(length);
        ScriptRuntime.setBuiltinProtoAndParent(result, scope, TopLevel.Builtins.Array);
        return result;
    }

    /**
     * Create an array with a set of initial elements.
     *
     * @param scope the scope to create the object in.
     * @param elements the initial elements. Each object in this array must be an acceptable
     *     JavaScript type and type of array should be exactly Object[], not SomeObjectSubclass[].
     * @return the new array object.
     */
    public Scriptable newArray(Scriptable scope, Object[] elements) {
        if (elements.getClass().getComponentType() != ScriptRuntime.ObjectClass)
            throw new IllegalArgumentException();
        NativeArray result = new NativeArray(elements);
        ScriptRuntime.setBuiltinProtoAndParent(result, scope, TopLevel.Builtins.Array);
        return result;
    }

    /**
     * Get the elements of a JavaScript array.
     *
     * <p>If the object defines a length property convertible to double number, then the number is
     * converted Uint32 value as defined in Ecma 9.6 and Java array of that size is allocated. The
     * array is initialized with the values obtained by calling get() on object for each value of i
     * in [0,length-1]. If there is not a defined value for a property the Undefined value is used
     * to initialize the corresponding element in the array. The Java array is then returned. If the
     * object doesn't define a length property or it is not a number, empty array is returned.
     *
     * @param object the JavaScript array or array-like object
     * @return a Java array of objects
     * @since 1.4 release 2
     */
    public final Object[] getElements(Scriptable object) {
        return ScriptRuntime.getArrayElements(object);
    }

    /**
     * Convert the value to a JavaScript boolean value.
     *
     * <p>See ECMA 9.2.
     *
     * @param value a JavaScript value
     * @return the corresponding boolean value converted using the ECMA rules
     */
    public static boolean toBoolean(Object value) {
        return ScriptRuntime.toBoolean(value);
    }

    /**
     * Convert the value to a JavaScript Number value.
     *
     * <p>Returns a Java double for the JavaScript Number.
     *
     * <p>See ECMA 9.3.
     *
     * @param value a JavaScript value
     * @return the corresponding double value converted using the ECMA rules
     */
    public static double toNumber(Object value) {
        return ScriptRuntime.toNumber(value);
    }

    /**
     * Convert the value to a JavaScript String value.
     *
     * <p>See ECMA 9.8.
     *
     * @param value a JavaScript value
     * @return the corresponding String value converted using the ECMA rules
     */
    public static String toString(Object value) {
        return ScriptRuntime.toString(value);
    }

    /**
     * Convert the value to an JavaScript object value.
     *
     * <p>Note that a scope must be provided to look up the constructors for Number, Boolean, and
     * String.
     *
     * <p>See ECMA 9.9.
     *
     * <p>Additionally, arbitrary Java objects and classes will be wrapped in a Scriptable object
     * with its Java fields and methods reflected as JavaScript properties of the object.
     *
     * @param value any Java object
     * @param scope global scope containing constructors for Number, Boolean, and String
     * @return new JavaScript object
     */
    public static Scriptable toObject(Object value, Scriptable scope) {
        return ScriptRuntime.toObject(scope, value);
    }

    /**
     * @deprecated
     * @see #toObject(Object, Scriptable)
     */
    @Deprecated
    public static Scriptable toObject(Object value, Scriptable scope, Class<?> staticType) {
        return ScriptRuntime.toObject(scope, value);
    }

    /**
     * Convenient method to convert java value to its closest representation in JavaScript.
     *
     * <p>If value is an instance of String, Number, Boolean, Function or Scriptable, it is returned
     * as it and will be treated as the corresponding JavaScript type of string, number, boolean,
     * function and object.
     *
     * <p>Note that for Number instances during any arithmetic operation in JavaScript the engine
     * will always use the result of <code>Number.doubleValue()</code> resulting in a precision loss
     * if the number can not fit into double.
     *
     * <p>If value is an instance of Character, it will be converted to string of length 1 and its
     * JavaScript type will be string.
     *
     * <p>The rest of values will be wrapped as LiveConnect objects by calling {@link
     * WrapFactory#wrap(Context cx, Scriptable scope, Object obj, Class staticType)} as in:
     *
     * <pre>
     *    Context cx = Context.getCurrentContext();
     *    return cx.getWrapFactory().wrap(cx, scope, value, null);
     * </pre>
     *
     * @param value any Java object
     * @param scope top scope object
     * @return value suitable to pass to any API that takes JavaScript values.
     */
    public static Object javaToJS(Object value, Scriptable scope) {
        return javaToJS(value, scope, null);
    }

    /**
     * Convenient method to convert java value to its closest representation in JavaScript.
     *
     * <p>If value is an instance of String, Number, Boolean, Function or Scriptable, it is returned
     * as it and will be treated as the corresponding JavaScript type of string, number, boolean,
     * function and object.
     *
     * <p>Note that for Number instances during any arithmetic operation in JavaScript the engine
     * will always use the result of <code>Number.doubleValue()</code> resulting in a precision loss
     * if the number can not fit into double.
     *
     * <p>If value is an instance of Character, it will be converted to string of length 1 and its
     * JavaScript type will be string.
     *
     * <p>The rest of values will be wrapped as LiveConnect objects by calling {@link
     * WrapFactory#wrap(Context cx, Scriptable scope, Object obj, Class staticType)} as in:
     *
     * <pre>
     *    return cx.getWrapFactory().wrap(cx, scope, value, null);
     * </pre>
     *
     * @param value any Java object
     * @param scope top scope object
     * @param cx context to use for wrapping LiveConnect objects
     * @return value suitable to pass to any API that takes JavaScript values.
     */
    public static Object javaToJS(Object value, Scriptable scope, Context cx) {
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Scriptable) {
            return value;
        } else if (value instanceof Character) {
            return String.valueOf(((Character) value).charValue());
        } else {
            if (cx == null) {
                cx = Context.getContext();
            }
            return cx.getWrapFactory().wrap(cx, scope, value, null);
        }
    }

    /**
     * Convert a JavaScript value into the desired type. Uses the semantics defined with
     * LiveConnect3 and throws an Illegal argument exception if the conversion cannot be performed.
     *
     * @param value the JavaScript value to convert
     * @param desiredType the Java type to convert to. Primitive Java types are represented using
     *     the TYPE fields in the corresponding wrapper class in java.lang.
     * @return the converted value
     * @throws EvaluatorException if the conversion cannot be performed
     */
    public static Object jsToJava(Object value, Class<?> desiredType) throws EvaluatorException {
        return jsToJava(value, TypeInfoFactory.GLOBAL.create(desiredType));
    }

    public static Object jsToJava(Object value, TypeInfo desiredType) throws EvaluatorException {
        return NativeJavaObject.coerceTypeImpl(desiredType, value);
    }

    /**
     * @deprecated
     * @see #jsToJava(Object, Class)
     * @throws IllegalArgumentException if the conversion cannot be performed. Note that {@link
     *     #jsToJava(Object, Class)} throws {@link EvaluatorException} instead.
     */
    @Deprecated
    public static Object toType(Object value, Class<?> desiredType)
            throws IllegalArgumentException {
        try {
            return jsToJava(value, desiredType);
        } catch (EvaluatorException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    /**
     * Returns the javaToJSONConverter for this Context.
     *
     * <p>The converter is used by the JSON.stringify method for Java objects other than instances
     * of {@link java.util.Map Map}, {@link java.util.Collection Collection}, or {@link
     * java.lang.Object Object[]}.
     *
     * <p>The default converter if unset will convert Java Objects to their toString() value.
     *
     * @return javaToJSONConverter for this Context
     */
    public UnaryOperator<Object> getJavaToJSONConverter() {
        if (javaToJSONConverter == null) {
            return JavaToJSONConverters.STRING;
        }
        return javaToJSONConverter;
    }

    /**
     * Sets the javaToJSONConverter for this Context.
     *
     * <p>The converter is used by the JSON.stringify method for Java objects other than instances
     * of {@link java.util.Map Map}, {@link java.util.Collection Collection}, or {@link
     * java.lang.Object Object[]}.
     *
     * <p>Objects returned by the converter will converted with {@link #javaToJS(Object,
     * Scriptable)} and then stringified themselves.
     *
     * @param javaToJSONConverter
     * @throws IllegalArgumentException if javaToJSONConverter is null
     */
    public void setJavaToJSONConverter(UnaryOperator<Object> javaToJSONConverter)
            throws IllegalArgumentException {
        if (javaToJSONConverter == null) {
            throw new IllegalArgumentException("javaToJSONConverter == null");
        }
        this.javaToJSONConverter = javaToJSONConverter;
    }

    /**
     * Rethrow the exception wrapping it as the script runtime exception. Unless the exception is
     * instance of {@link EcmaError} or {@link EvaluatorException} it will be wrapped as {@link
     * WrappedException}, a subclass of {@link EvaluatorException}. The resulting exception object
     * always contains source name and line number of script that triggered exception.
     *
     * <p>This method always throws an exception, its return value is provided only for convenience
     * to allow a usage like:
     *
     * <pre>
     * throw Context.throwAsScriptRuntimeEx(ex);
     * </pre>
     *
     * to indicate that code after the method is unreachable.
     *
     * @throws EvaluatorException
     * @throws EcmaError
     */
    public static RuntimeException throwAsScriptRuntimeEx(Throwable e) {
        while ((e instanceof InvocationTargetException)) {
            e = ((InvocationTargetException) e).getTargetException();
        }
        // special handling of Error so scripts would not catch them
        if (e instanceof Error) {
            Context cx = getContext();
            if (cx == null || !cx.hasFeature(Context.FEATURE_ENHANCED_JAVA_ACCESS)) {
                throw (Error) e;
            }
        }
        if (e instanceof RhinoException) {
            throw (RhinoException) e;
        }
        throw new WrappedException(e);
    }

    /**
     * Tell whether debug information is being generated.
     *
     * @since 1.3
     */
    public final boolean isGeneratingDebug() {
        return generatingDebug;
    }

    /**
     * Specify whether or not debug information should be generated.
     *
     * @since 1.3
     */
    public final void setGeneratingDebug(boolean generatingDebug) {
        if (sealed) onSealedMutation();
        generatingDebugChanged = true;
        this.generatingDebug = generatingDebug;
    }

    /**
     * Tell whether source information is being generated.
     *
     * @since 1.3
     */
    public final boolean isGeneratingSource() {
        return generatingSource;
    }

    /**
     * Specify whether or not source information should be generated.
     *
     * <p>Without source information, evaluating the "toString" method on JavaScript functions
     * produces only "[native code]" for the body of the function. Note that code generated without
     * source is not fully ECMA conformant.
     *
     * @since 1.3
     */
    public final void setGeneratingSource(boolean generatingSource) {
        if (sealed) onSealedMutation();
        this.generatingSource = generatingSource;
    }

    /**
     * Get the current optimization level.
     *
     * <p>The optimization level is expressed as an integer between -1 and 9. Rhino now has only one
     * optimization level, and we will always return either -1 or 9 here.
     *
     * @since 1.3
     * @deprecated As of 1.8.0, use {@link #isInterpretedMode()} instead.
     */
    @Deprecated
    public final int getOptimizationLevel() {
        return interpretedMode ? -1 : 9;
    }

    /**
     * Return whether Rhino is running in interpreted mode. In this mode, Rhino does not generate
     * bytecode, but runs much more slowly. Some platforms, notably Android, use this mode.
     */
    public final boolean isInterpretedMode() {
        return interpretedMode;
    }

    /**
     * Set the current optimization level.
     *
     * <p>This function previously set multiple modes today. Any value less than zero sets up
     * interpreted mode, and otherwise we run in compiled mode.
     *
     * @param optimizationLevel an integer indicating the level of optimization to perform
     * @since 1.3
     * @deprecated As of 1.8.0, use {@link #setInterpretedMode(boolean)} instead.
     */
    @Deprecated
    public final void setOptimizationLevel(int optimizationLevel) {
        setInterpretedMode(optimizationLevel < 0);
    }

    /**
     * Set Rhino to run in interpreted mode. In this mode, Rhino does not generate bytecode, but
     * runs much more slowly. Some platforms, notably Android, must use this mode because they
     * cannot generate bytecode.
     */
    public final void setInterpretedMode(boolean interpretedMode) {
        if (sealed) onSealedMutation();
        this.interpretedMode = interpretedMode;
    }

    /**
     * @deprecated As of 1.8.0, no longer has any use.
     */
    @Deprecated
    public static boolean isValidOptimizationLevel(int optimizationLevel) {
        return -1 <= optimizationLevel && optimizationLevel <= 9;
    }

    /**
     * @deprecated As of 1.8.0, no longer has any use.
     */
    @Deprecated
    public static void checkOptimizationLevel(int optimizationLevel) {
        if (isValidOptimizationLevel(optimizationLevel)) {
            return;
        }
        throw new IllegalArgumentException(
                "Optimization level outside [-1..9]: " + optimizationLevel);
    }

    /**
     * Returns the maximum stack depth (in terms of number of call frames) allowed in a single
     * invocation of interpreter. If the set depth would be exceeded, the interpreter will throw an
     * EvaluatorException in the script. Defaults to Integer.MAX_VALUE. The setting only has effect
     * for interpreted functions (those compiled with optimization level set to -1). As the
     * interpreter doesn't use the Java stack but rather manages its own stack in the heap memory, a
     * runaway recursion in interpreted code would eventually consume all available memory and cause
     * OutOfMemoryError instead of a StackOverflowError limited to only a single thread. This
     * setting helps prevent such situations.
     *
     * @return The current maximum interpreter stack depth.
     */
    public final int getMaximumInterpreterStackDepth() {
        return maximumInterpreterStackDepth;
    }

    /**
     * Sets the maximum stack depth (in terms of number of call frames) allowed in a single
     * invocation of interpreter. If the set depth would be exceeded, the interpreter will throw an
     * EvaluatorException in the script. Defaults to Integer.MAX_VALUE. The setting only has effect
     * for interpreted functions (those compiled with optimization level set to -1). As the
     * interpreter doesn't use the Java stack but rather manages its own stack in the heap memory, a
     * runaway recursion in interpreted code would eventually consume all available memory and cause
     * OutOfMemoryError instead of a StackOverflowError limited to only a single thread. This
     * setting helps prevent such situations.
     *
     * @param max the new maximum interpreter stack depth
     * @throws IllegalStateException if this context's optimization level is not -1
     * @throws IllegalArgumentException if the new depth is not at least 1
     */
    public final void setMaximumInterpreterStackDepth(int max) {
        if (sealed) onSealedMutation();
        if (!interpretedMode) {
            throw new IllegalStateException(
                    "Cannot set maximumInterpreterStackDepth outside interpreted mode");
        }
        if (max < 1) {
            throw new IllegalArgumentException(
                    "Cannot set maximumInterpreterStackDepth to less than 1");
        }
        maximumInterpreterStackDepth = max;
    }

    /**
     * Set the security controller for this context.
     *
     * <p>SecurityController may only be set if it is currently null and {@link
     * SecurityController#hasGlobal()} is <code>false</code>. Otherwise a SecurityException is
     * thrown.
     *
     * @param controller a SecurityController object
     * @throws SecurityException if there is already a SecurityController object for this Context or
     *     globally installed.
     * @see SecurityController#initGlobal(SecurityController controller)
     * @see SecurityController#hasGlobal()
     */
    public final void setSecurityController(SecurityController controller) {
        if (sealed) onSealedMutation();
        if (controller == null) throw new IllegalArgumentException();
        if (securityController != null) {
            throw new SecurityException("Can not overwrite existing SecurityController object");
        }
        if (SecurityController.hasGlobal()) {
            throw new SecurityException(
                    "Can not overwrite existing global SecurityController object");
        }
        securityController = controller;
    }

    /**
     * Set the LiveConnect access filter for this context.
     *
     * <p>{@link ClassShutter} may only be set if it is currently null. Otherwise a
     * SecurityException is thrown.
     *
     * @param shutter a ClassShutter object
     * @throws SecurityException if there is already a ClassShutter object for this Context
     */
    public final synchronized void setClassShutter(ClassShutter shutter) {
        if (sealed) onSealedMutation();
        if (shutter == null) throw new IllegalArgumentException();
        if (hasClassShutter) {
            throw new SecurityException("Cannot overwrite existing " + "ClassShutter object");
        }
        classShutter = shutter;
        hasClassShutter = true;
    }

    final synchronized ClassShutter getClassShutter() {
        return classShutter;
    }

    public interface ClassShutterSetter {
        public void setClassShutter(ClassShutter shutter);

        public ClassShutter getClassShutter();
    }

    public final synchronized ClassShutterSetter getClassShutterSetter() {
        if (hasClassShutter) return null;
        hasClassShutter = true;
        return new ClassShutterSetter() {

            @Override
            public void setClassShutter(ClassShutter shutter) {
                classShutter = shutter;
            }

            @Override
            public ClassShutter getClassShutter() {
                return classShutter;
            }
        };
    }

    /**
     * Get a value corresponding to a key.
     *
     * <p>Since the Context is associated with a thread it can be used to maintain values that can
     * be later retrieved using the current thread.
     *
     * <p>Note that the values are maintained with the Context, so if the Context is disassociated
     * from the thread the values cannot be retrieved. Also, if private data is to be maintained in
     * this manner the key should be a java.lang.Object whose reference is not divulged to untrusted
     * code.
     *
     * @param key the key used to lookup the value
     * @return a value previously stored using putThreadLocal.
     */
    public final Object getThreadLocal(Object key) {
        if (threadLocalMap == null) return null;
        return threadLocalMap.get(key);
    }

    /**
     * Put a value that can later be retrieved using a given key.
     *
     * @param key the key used to index the value
     * @param value the value to save
     */
    public final synchronized void putThreadLocal(Object key, Object value) {
        if (sealed) onSealedMutation();
        if (threadLocalMap == null) threadLocalMap = new HashMap<>();
        threadLocalMap.put(key, value);
    }

    /**
     * Remove values from thread-local storage.
     *
     * @param key the key for the entry to remove.
     * @since 1.5 release 2
     */
    public final void removeThreadLocal(Object key) {
        if (sealed) onSealedMutation();
        if (threadLocalMap == null) return;
        threadLocalMap.remove(key);
    }

    /**
     * @deprecated
     * @see ClassCache#get(Scriptable)
     * @see ClassCache#setCachingEnabled(boolean)
     */
    @Deprecated
    public static void setCachingEnabled(boolean cachingEnabled) {}

    /**
     * Set a WrapFactory for this Context.
     *
     * <p>The WrapFactory allows custom object wrapping behavior for Java object manipulated with
     * JavaScript.
     *
     * @see WrapFactory
     * @since 1.5 Release 4
     */
    public final void setWrapFactory(WrapFactory wrapFactory) {
        if (sealed) onSealedMutation();
        if (wrapFactory == null) throw new IllegalArgumentException();
        this.wrapFactory = wrapFactory;
    }

    /**
     * Return the current WrapFactory, or null if none is defined.
     *
     * @see WrapFactory
     * @since 1.5 Release 4
     */
    public final WrapFactory getWrapFactory() {
        if (wrapFactory == null) {
            wrapFactory = new WrapFactory();
        }
        return wrapFactory;
    }

    /**
     * Return the current debugger.
     *
     * @return the debugger, or null if none is attached.
     */
    public final Debugger getDebugger() {
        return debugger;
    }

    /**
     * Return the debugger context data associated with current context.
     *
     * @return the debugger data, or null if debugger is not attached
     */
    public final Object getDebuggerContextData() {
        return debuggerData;
    }

    /**
     * Set the associated debugger.
     *
     * @param debugger the debugger to be used on callbacks from the engine.
     * @param contextData arbitrary object that debugger can use to store per Context data.
     */
    public final void setDebugger(Debugger debugger, Object contextData) {
        if (sealed) onSealedMutation();
        this.debugger = debugger;
        debuggerData = contextData;
    }

    /**
     * Return DebuggableScript instance if any associated with the script. If callable supports
     * DebuggableScript implementation, the method returns it. Otherwise null is returned.
     */
    public static DebuggableScript getDebuggableView(Script script) {
        if (script instanceof NativeFunction) {
            return ((NativeFunction) script).getDebuggableView();
        }
        return null;
    }

    /**
     * Controls certain aspects of script semantics. Should be overwritten to alter default
     * behavior.
     *
     * <p>The default implementation calls {@link ContextFactory#hasFeature(Context cx, int
     * featureIndex)} that allows to customize Context behavior without introducing Context
     * subclasses. {@link ContextFactory} documentation gives an example of hasFeature
     * implementation.
     *
     * @param featureIndex feature index to check
     * @return true if the <code>featureIndex</code> feature is turned on
     * @see #FEATURE_NON_ECMA_GET_YEAR
     * @see #FEATURE_MEMBER_EXPR_AS_FUNCTION_NAME
     * @see #FEATURE_RESERVED_KEYWORD_AS_IDENTIFIER
     * @see #FEATURE_TO_STRING_AS_SOURCE
     * @see #FEATURE_PARENT_PROTO_PROPRTIES
     * @see #FEATURE_E4X
     * @see #FEATURE_DYNAMIC_SCOPE
     * @see #FEATURE_STRICT_VARS
     * @see #FEATURE_STRICT_EVAL
     * @see #FEATURE_LOCATION_INFORMATION_IN_ERROR
     * @see #FEATURE_STRICT_MODE
     * @see #FEATURE_WARNING_AS_ERROR
     * @see #FEATURE_ENHANCED_JAVA_ACCESS
     */
    public boolean hasFeature(int featureIndex) {
        ContextFactory f = getFactory();
        return f.hasFeature(this, featureIndex);
    }

    /**
     * Returns an object which specifies an E4X implementation to use within this <code>Context
     * </code>. Note that the XMLLib.Factory interface should be considered experimental.
     *
     * <p>The default implementation uses the implementation provided by this <code>Context</code>'s
     * {@link ContextFactory}.
     *
     * <p>This is no longer used in E4X -- an implementation is only provided for backward
     * compatibility.
     *
     * @return An XMLLib.Factory. Should not return <code>null</code> if {@link #FEATURE_E4X} is
     *     enabled. See {@link #hasFeature}.
     */
    @Deprecated
    public XMLLib.Factory getE4xImplementationFactory() {
        if (ScriptRuntime.xmlLoaderImpl != null) {
            return ScriptRuntime.xmlLoaderImpl.getFactory();
        }
        return null;
    }

    /**
     * Get threshold of executed instructions counter that triggers call to <code>
     * observeInstructionCount()</code>. When the threshold is zero, instruction counting is
     * disabled, otherwise each time the run-time executes at least the threshold value of script
     * instructions, <code>observeInstructionCount()</code> will be called.
     */
    public final int getInstructionObserverThreshold() {
        return instructionThreshold;
    }

    /**
     * Set threshold of executed instructions counter that triggers call to <code>
     * observeInstructionCount()</code>. When the threshold is zero, instruction counting is
     * disabled, otherwise each time the run-time executes at least the threshold value of script
     * instructions, <code>observeInstructionCount()</code> will be called.<br>
     * Note that the meaning of "instruction" is not guaranteed to be consistent between compiled
     * and interpretive modes: executing a given script or function in the different modes will
     * result in different instruction counts against the threshold. {@link
     * #setGenerateObserverCount} is called with true if <code>threshold</code> is greater than
     * zero, false otherwise.
     *
     * @param threshold The instruction threshold
     */
    public final void setInstructionObserverThreshold(int threshold) {
        if (sealed) onSealedMutation();
        if (threshold < 0) throw new IllegalArgumentException();
        instructionThreshold = threshold;
        setGenerateObserverCount(threshold > 0);
    }

    /**
     * Turn on or off generation of code with callbacks to track the count of executed instructions.
     * Currently only affects JVM byte code generation: this slows down the generated code, but code
     * generated without the callbacks will not be counted toward instruction thresholds. Rhino's
     * interpretive mode does instruction counting without inserting callbacks, so there is no
     * requirement to compile code differently.
     *
     * @param generateObserverCount if true, generated code will contain calls to accumulate an
     *     estimate of the instructions executed.
     */
    public void setGenerateObserverCount(boolean generateObserverCount) {
        this.generateObserverCount = generateObserverCount;
    }

    /** Determine if observer counts should be generated. */
    public boolean isGenerateObserverCount() {
        return this.generateObserverCount;
    }

    /**
     * Allow application to monitor counter of executed script instructions in Context subclasses.
     * Run-time calls this when instruction counting is enabled and the counter reaches limit set by
     * <code>setInstructionObserverThreshold()</code>. The method is useful to observe long running
     * scripts and if necessary to terminate them.
     *
     * <p>The default implementation calls {@link ContextFactory#observeInstructionCount(Context cx,
     * int instructionCount)} that allows to customize Context behavior without introducing Context
     * subclasses.
     *
     * @param instructionCount amount of script instruction executed since last call to <code>
     *     observeInstructionCount</code>
     * @throws Error to terminate the script
     * @see #setOptimizationLevel(int)
     */
    protected void observeInstructionCount(int instructionCount) {
        ContextFactory f = getFactory();
        f.observeInstructionCount(this, instructionCount);
    }

    /**
     * Create class loader for generated classes. The method calls {@link
     * ContextFactory#createClassLoader(ClassLoader)} using the result of {@link #getFactory()}.
     */
    public GeneratedClassLoader createClassLoader(ClassLoader parent) {
        ContextFactory f = getFactory();
        return f.createClassLoader(parent);
    }

    public final ClassLoader getApplicationClassLoader() {
        if (applicationClassLoader == null) {
            ContextFactory f = getFactory();
            ClassLoader loader = f.getApplicationClassLoader();
            if (loader == null) {
                ClassLoader threadLoader = Thread.currentThread().getContextClassLoader();
                if (threadLoader != null && Kit.testIfCanLoadRhinoClasses(threadLoader)) {
                    // Thread.getContextClassLoader is not cached since
                    // its caching prevents it from GC which may lead to
                    // a memory leak and hides updates to
                    // Thread.getContextClassLoader
                    return threadLoader;
                }
                // Thread.getContextClassLoader can not load Rhino classes,
                // try to use the loader of ContextFactory or Context
                // subclasses.
                Class<?> fClass = f.getClass();
                if (fClass != ScriptRuntime.ContextFactoryClass) {
                    loader = fClass.getClassLoader();
                } else {
                    loader = getClass().getClassLoader();
                }
            }
            applicationClassLoader = loader;
        }
        return applicationClassLoader;
    }

    public final void setApplicationClassLoader(ClassLoader loader) {
        if (sealed) onSealedMutation();
        if (loader == null) {
            // restore default behaviour
            applicationClassLoader = null;
            return;
        }
        if (!Kit.testIfCanLoadRhinoClasses(loader)) {
            throw new IllegalArgumentException("Loader can not resolve Rhino classes");
        }
        applicationClassLoader = loader;
    }

    /**
     * Add a task that will be executed at the end of the current operation. The various "evaluate"
     * functions will all call this before exiting to ensure that all microtasks run to completion.
     * Otherwise, callers should call "processMicrotasks" to run them all. This feature is primarily
     * used to implement Promises. The microtask queue is not thread-safe.
     */
    public void enqueueMicrotask(Runnable task) {
        microtasks.add(task);
    }

    /**
     * Run all the microtasks for the current context to completion. This is called by the various
     * "evaluate" functions. Frameworks that call Function objects directly should call this
     * function to ensure that everything completes if they want all Promises to eventually resolve.
     * This function is idempotent, but the microtask queue is not thread-safe.
     */
    public void processMicrotasks() {
        Runnable head;
        do {
            head = microtasks.poll();
            if (head != null) {
                head.run();
            }
        } while (head != null);
    }

    /**
     * Control whether to track unhandled promise rejections. If "track" is set to true, then the
     * tracker returned by "getUnhandledPromiseTracker" must be periodically used to process the
     * queue of unhandled promise rejections, or a memory leak may result.
     *
     * @param track if true, then track unhandled promise rejections
     */
    public void setTrackUnhandledPromiseRejections(boolean track) {
        unhandledPromises.enable(track);
    }

    /**
     * Return the object used to track unhandled promise rejections.
     *
     * @return the tracker object
     */
    public UnhandledRejectionTracker getUnhandledPromiseTracker() {
        return unhandledPromises;
    }

    /* ******** end of API ********* */

    /** Internal method that reports an error for missing calls to enter(). */
    static Context getContext() {
        Context cx = getCurrentContext();
        if (cx == null) {
            throw new RuntimeException("No Context associated with current Thread");
        }
        return cx;
    }

    protected Object compileImpl(
            Scriptable scope,
            String sourceString,
            String sourceName,
            int lineno,
            Object securityDomain,
            boolean returnFunction,
            Evaluator compiler,
            ErrorReporter compilationErrorReporter,
            Consumer<CompilerEnvirons> compilerEnvironProcessor) {
        if (sourceName == null) {
            sourceName = "unnamed script";
        }
        if (securityDomain != null && getSecurityController() == null) {
            throw new IllegalArgumentException(
                    "securityDomain should be null if setSecurityController() was never called");
        }

        // scope should be given if and only if compiling function
        if (!(scope == null ^ returnFunction)) Kit.codeBug();

        CompilerEnvirons compilerEnv = new CompilerEnvirons();
        compilerEnv.initFromContext(this);
        if (compilationErrorReporter == null) {
            compilationErrorReporter = compilerEnv.getErrorReporter();
        }
        if (compilerEnvironProcessor != null) {
            compilerEnvironProcessor.accept(compilerEnv);
        }

        ScriptNode tree =
                parse(
                        sourceString,
                        sourceName,
                        lineno,
                        compilerEnv,
                        compilationErrorReporter,
                        returnFunction);

        Object bytecode;
        try {
            if (compiler == null) {
                compiler = createCompiler();
            }

            bytecode = compiler.compile(compilerEnv, tree, sourceString, returnFunction);
        } catch (ClassFileFormatException e) {
            // we hit some class file limit, fall back to interpreter or report

            // we have to recreate the tree because the compile call might have changed the tree
            // already
            tree =
                    parse(
                            sourceString,
                            sourceName,
                            lineno,
                            compilerEnv,
                            compilationErrorReporter,
                            returnFunction);

            compiler = createInterpreter();
            bytecode = compiler.compile(compilerEnv, tree, sourceString, returnFunction);
        }

        if (debugger != null) {
            if (sourceString == null) Kit.codeBug();
            if (bytecode instanceof DebuggableScript) {
                DebuggableScript dscript = (DebuggableScript) bytecode;
                notifyDebugger_r(this, dscript, sourceString);
            } else {
                throw new RuntimeException("NOT SUPPORTED");
            }
        }

        Object result;
        if (returnFunction) {
            result = compiler.createFunctionObject(this, scope, bytecode, securityDomain);
        } else {
            result = compiler.createScriptObject(bytecode, securityDomain);
        }

        return result;
    }

    private ScriptNode parse(
            String sourceString,
            String sourceName,
            int lineno,
            CompilerEnvirons compilerEnv,
            ErrorReporter compilationErrorReporter,
            boolean returnFunction) {
        Parser p = new Parser(compilerEnv, compilationErrorReporter);
        if (returnFunction) {
            p.calledByCompileFunction = true;
        }
        if (isStrictMode()) {
            p.setDefaultUseStrictDirective(true);
        }

        AstRoot ast = p.parse(sourceString, sourceName, lineno);
        if (returnFunction) {
            // parser no longer adds function to script node
            if (!(ast.getFirstChild() != null && ast.getFirstChild().getType() == Token.FUNCTION)) {
                // XXX: the check just looks for the first child
                // and allows for more nodes after it for compatibility
                // with sources like function() {};;;
                throw new IllegalArgumentException(
                        "compileFunction only accepts source with single JS function: "
                                + sourceString);
            }
        }

        IRFactory irf =
                new IRFactory(compilerEnv, sourceName, sourceString, compilationErrorReporter);
        ScriptNode tree = irf.transformTree(ast);

        if (compilerEnv.isGeneratingSource()) {
            tree.setRawSource(sourceString);
            tree.setRawSourceBounds(0, sourceString.length());
        }

        return tree;
    }

    private static void notifyDebugger_r(Context cx, DebuggableScript dscript, String debugSource) {
        cx.debugger.handleCompilationDone(cx, dscript, debugSource);
        for (int i = 0; i != dscript.getFunctionCount(); ++i) {
            notifyDebugger_r(cx, dscript.getFunction(i), debugSource);
        }
    }

    private static Class<?> codegenClass =
            Kit.classOrNull("org.mozilla.javascript.optimizer.Codegen");
    private static Class<?> interpreterClass =
            Kit.classOrNull("org.mozilla.javascript.Interpreter");

    private Evaluator createCompiler() {
        Evaluator result = null;
        if (!interpretedMode && codegenClass != null) {
            result = (Evaluator) Kit.newInstanceOrNull(codegenClass);
        }
        if (result == null) {
            result = createInterpreter();
        }
        return result;
    }

    static Evaluator createInterpreter() {
        return (Evaluator) Kit.newInstanceOrNull(interpreterClass);
    }

    static String getSourcePositionFromStack(int[] linep) {
        Context cx = getCurrentContext();
        if (cx == null) return null;
        if (cx.lastInterpreterFrame != null) {
            Evaluator evaluator = createInterpreter();
            if (evaluator != null) return evaluator.getSourcePositionFromStack(cx, linep);
        }

        return getSourcePositionFromJavaStack(linep);
    }

    /** Returns the current filename in the java stack. */
    static String getSourcePositionFromJavaStack(int[] linep) {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (StackTraceElement e : stack) {
            if (frameMatches(e)) {
                linep[0] = e.getLineNumber();
                return e.getFileName();
            }
        }
        return null;
    }

    private static boolean frameMatches(StackTraceElement e) {
        return (e.getFileName() == null || !e.getFileName().endsWith(".java"))
                && e.getLineNumber() > 0;
    }

    RegExpProxy getRegExpProxy() {
        if (regExpProxy == null && regExpLoader != null) {
            regExpProxy = regExpLoader.newProxy();
        }
        return regExpProxy;
    }

    final boolean isVersionECMA1() {
        return version == VERSION_DEFAULT || version >= VERSION_1_3;
    }

    // The method must NOT be public or protected
    SecurityController getSecurityController() {
        SecurityController global = SecurityController.global();
        if (global != null) {
            return global;
        }
        return securityController;
    }

    public final boolean isGeneratingDebugChanged() {
        return generatingDebugChanged;
    }

    /**
     * Add a name to the list of names forcing the creation of real activation objects for
     * functions.
     *
     * @param name the name of the object to add to the list
     */
    public void addActivationName(String name) {
        if (sealed) onSealedMutation();
        if (activationNames == null) activationNames = new HashSet<>();
        activationNames.add(name);
    }

    /**
     * Check whether the name is in the list of names of objects forcing the creation of activation
     * objects.
     *
     * @param name the name of the object to test
     * @return true if an function activation object is needed.
     */
    public final boolean isActivationNeeded(String name) {
        return activationNames != null && activationNames.contains(name);
    }

    /**
     * Remove a name from the list of names forcing the creation of real activation objects for
     * functions.
     *
     * @param name the name of the object to remove from the list
     */
    public void removeActivationName(String name) {
        if (sealed) onSealedMutation();
        if (activationNames != null) activationNames.remove(name);
    }

    public final boolean isStrictMode() {
        return isTopLevelStrict
                || (currentActivationCall != null && currentActivationCall.isStrict);
    }

    public static boolean isCurrentContextStrict() {
        Context cx = getCurrentContext();
        if (cx == null) {
            return false;
        }
        return cx.isStrictMode();
    }

    private final ContextFactory factory;
    private boolean sealed;
    private Object sealKey;

    Scriptable topCallScope;
    boolean isContinuationsTopCall;
    NativeCall currentActivationCall;
    XMLLib cachedXMLLib;
    BaseFunction typeErrorThrower;

    // for Objects, Arrays to tag themselves as being printed out,
    // so they don't print themselves out recursively.
    // Use ObjToIntMap instead of java.util.HashSet for JDK 1.1 compatibility
    Set<Scriptable> iterating;

    Object interpreterSecurityDomain;

    int version;

    private SecurityController securityController;
    private boolean hasClassShutter;
    private ClassShutter classShutter;
    private ErrorReporter errorReporter;
    RegExpProxy regExpProxy;
    private Locale locale;
    private TimeZone timezone;
    private boolean generatingDebug;
    private boolean generatingDebugChanged;
    private boolean generatingSource = true;
    boolean useDynamicScope;
    private boolean interpretedMode;
    private int maximumInterpreterStackDepth;
    private WrapFactory wrapFactory;
    Debugger debugger;
    private Object debuggerData;
    private int enterCount;
    private Object propertyListeners;
    private Map<Object, Object> threadLocalMap;
    private ClassLoader applicationClassLoader;
    private UnaryOperator<Object> javaToJSONConverter;
    private final ArrayDeque<Runnable> microtasks = new ArrayDeque<>();
    private final UnhandledRejectionTracker unhandledPromises = new UnhandledRejectionTracker();

    /** This is the list of names of objects forcing the creation of function activation records. */
    Set<String> activationNames;

    // For the interpreter to store the last frame for error reports
    // etc. Previous frames can all be derived from this.
    Object lastInterpreterFrame;

    // For instruction counting (interpreter only)
    int instructionCount;
    int instructionThreshold;

    // It can be used to return the second uint32 result from function
    long scratchUint32;

    // It can be used to return the second Scriptable result from function
    Scriptable scratchScriptable;

    // Generate an observer count on compiled code
    boolean generateObserverCount = false;

    boolean isTopLevelStrict;
}
